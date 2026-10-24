/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.coders;

import static org.apache.beam.sdk.util.ByteBuddyUtils.emitLdcConstant;
import static org.apache.beam.sdk.util.ByteBuddyUtils.getClassLoadingStrategy;
import static org.apache.beam.sdk.util.ByteBuddyUtils.getFieldDescriptor;
import static org.apache.beam.sdk.util.ByteBuddyUtils.pushInt;
import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions.checkState;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.modifier.TypeManifestation;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.VisibilityBridgeStrategy;
import net.bytebuddy.dynamic.scaffold.InstrumentedType;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.ByteCodeAppender;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.matcher.ElementMatchers;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.Schema.Field;
import org.apache.beam.sdk.schemas.Schema.FieldType;
import org.apache.beam.sdk.schemas.Schema.LogicalType;
import org.apache.beam.sdk.schemas.Schema.TypeName;
import org.apache.beam.sdk.schemas.SchemaCoder;
import org.apache.beam.sdk.util.StringUtils;
import org.apache.beam.sdk.util.VarInt;
import org.apache.beam.sdk.util.common.ReflectHelpers;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.sdk.values.RowGenerator;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.annotations.VisibleForTesting;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.Maps;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.io.ByteStreams;
import org.joda.time.Instant;
import org.joda.time.ReadableInstant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A utility for automatically generating a {@link Coder} for {@link Row} objects corresponding to a
 * specific schema. The resulting coder is loaded into the default ClassLoader and returned.
 *
 * <p>When {@link RowCoderGenerator#generate(Schema)} is called, a new subclass of {@literal
 * Coder<Row>} is generated for the specified schema. This class is generated using low-level
 * bytecode generation, and hardcodes encodings for all fields of the Schema.
 */
@SuppressWarnings({
  "nullness", // TODO(https://github.com/apache/beam/issues/20497)
  "rawtypes"
})
public abstract class RowCoderGenerator {
  private static final ByteBuddy BYTE_BUDDY =
      new ByteBuddy(ClassFileVersion.ofThisVm(ClassFileVersion.JAVA_V11))
          .with(TypeValidation.DISABLED)
          .with(Implementation.Context.Disabled.Factory.INSTANCE)
          .with(VisibilityBridgeStrategy.Default.NEVER);
  private static final BitSetCoder NULL_LIST_CODER = BitSetCoder.of();
  private static final StringUtf8Coder STRING_CODER = StringUtf8Coder.of();
  private static final ByteArrayCoder BYTE_ARRAY_CODER = ByteArrayCoder.of();
  private static final BigDecimalCoder BIG_DECIMAL_CODER = BigDecimalCoder.of();

  private static final String SCHEMA_OPTION_STATIC_ENCODING = "beam:option:row:static_encoding";

  private static final String INTERNAL_GENERATOR_NAME = Type.getInternalName(RowCoderGenerator.class);
  private static final String INTERNAL_ROW_NAME = Type.getInternalName(Row.class);
  private static final String INTERNAL_VARINT_NAME = Type.getInternalName(VarInt.class);
  private static final String SCHEMA_DESC = Type.getDescriptor(Schema.class);
  private static final String LOGICAL_TYPE_DESC = Type.getDescriptor(LogicalType.class);
  private static final String CODER_ARRAY_DESC = Type.getDescriptor(Coder[].class);
  private static final String INT_ARRAY_DESC = Type.getDescriptor(int[].class);
  private static final String OUTPUT_STREAM_DESC = Type.getDescriptor(OutputStream.class);
  private static final String INPUT_STREAM_DESC = Type.getDescriptor(InputStream.class);
  private static final String ROW_DESC = Type.getDescriptor(Row.class);
  private static final String OBJECT_DESC = Type.getDescriptor(Object.class);

  static Class<?> accessibleCoderClass(Class<?> coderClass) {
    if (Modifier.isPublic(coderClass.getModifiers())
        || coderClass.getPackage() == Coder.class.getPackage()) {
      return coderClass;
    }
    return Coder.class;
  }

  static class WithStackTrace<T> {
    private final T value;
    private final String stackTrace;

    public WithStackTrace(T value, String stackTrace) {
      this.value = value;
      this.stackTrace = stackTrace;
    }

    public T getValue() {
      return value;
    }

    public String getStackTrace() {
      return stackTrace;
    }
  }

  // Cache for Coder class that are already generated.
  @GuardedBy("cacheLock")
  private static final Map<UUID, WithStackTrace<Coder<Row>>> GENERATED_CODERS = Maps.newHashMap();

  @GuardedBy("cacheLock")
  private static final Map<UUID, WithStackTrace<Map<String, Integer>>> ENCODING_POSITION_OVERRIDES =
      Maps.newHashMap();

  private static final Object cacheLock = new Object();

  private static final Logger LOG = LoggerFactory.getLogger(RowCoderGenerator.class);

  private static String getStackTrace() {
    return StringUtils.arrayToNewlines(Thread.currentThread().getStackTrace(), 10);
  }

  public static void overrideEncodingPositions(UUID uuid, Map<String, Integer> encodingPositions) {
    final String stackTrace = getStackTrace();
    synchronized (cacheLock) {
      @Nullable
      WithStackTrace<Map<String, Integer>> previousEncodingPositions =
          ENCODING_POSITION_OVERRIDES.put(
              uuid, new WithStackTrace<>(encodingPositions, stackTrace));
      @Nullable WithStackTrace<Coder<Row>> existingCoder = GENERATED_CODERS.get(uuid);
      if (previousEncodingPositions == null) {
        if (existingCoder != null) {
          LOG.error(
              "Received encoding positions for uuid {} too late after creating RowCoder. Created: {}\n Override: {}",
              uuid,
              existingCoder.getStackTrace(),
              stackTrace);
        } else {
          LOG.info("Received encoding positions {} for uuid {}.", encodingPositions, uuid);
        }
      } else if (!previousEncodingPositions.getValue().equals(encodingPositions)) {
        if (existingCoder == null) {
          LOG.error(
              "Received differing encoding positions for uuid {} before coder creation. Was {} at {}\n Now {} at {}",
              uuid,
              previousEncodingPositions.getValue(),
              encodingPositions,
              previousEncodingPositions.getStackTrace(),
              stackTrace);
        } else {
          LOG.error(
              "Received differing encoding positions for uuid {} after coder creation at {}\n. "
                  + "Was {} at {}\n Now {} at {}\n",
              uuid,
              existingCoder.getStackTrace(),
              previousEncodingPositions.getValue(),
              encodingPositions,
              previousEncodingPositions.getStackTrace(),
              stackTrace);
        }
      }
    }
  }

  @VisibleForTesting
  static void clearRowCoderCache() {
    synchronized (cacheLock) {
      GENERATED_CODERS.clear();
    }
  }

  @SuppressWarnings("unchecked")
  public static Coder<Row> generate(Schema schema) {
    UUID uuid = Preconditions.checkNotNull(schema.getUUID());
    // Avoid using computeIfAbsent which may cause issues with nested schemas.
    synchronized (cacheLock) {
      @Nullable WithStackTrace<Coder<Row>> existingRowCoder = GENERATED_CODERS.get(uuid);
      if (existingRowCoder != null) {
        return existingRowCoder.getValue();
      }

      int[] encodingPosToRowIndex = new int[schema.getFieldCount()];
      @Nullable
      WithStackTrace<Map<String, Integer>> existingEncodingPositions =
          ENCODING_POSITION_OVERRIDES.get(uuid);
      Map<String, Integer> encodingPositions =
          existingEncodingPositions == null
              ? schema.getEncodingPositions()
              : existingEncodingPositions.getValue();
      for (int recordIndex = 0; recordIndex < schema.getFieldCount(); ++recordIndex) {
        String name = schema.getField(recordIndex).getName();
        int encodingPosition = encodingPositions.get(name);
        encodingPosToRowIndex[encodingPosition] = recordIndex;
      }
      // There should never be duplicate encoding positions.
      Preconditions.checkState(
          schema.getFieldCount() == Arrays.stream(encodingPosToRowIndex).distinct().count());

      // Component coders are ordered by encoding position, but may encode a field with a different
      // row index.
      Coder[] componentCoders = new Coder[schema.getFieldCount()];
      for (int i = 0; i < schema.getFieldCount(); ++i) {
        int rowIndex = encodingPosToRowIndex[i];
        // We use withNullable(false) as nulls are handled by the RowCoder and the individual
        // component coders therefore do not need to handle nulls.
        FieldType fieldType = schema.getField(rowIndex).getType().withNullable(false);
        if (fieldType.getTypeName() == TypeName.ROW) {
          Schema nestedSchema = SchemaCoder.of(fieldType.getRowSchema()).getSchema();
          componentCoders[i] = generate(nestedSchema);
        } else {
          componentCoders[i] = SchemaCoder.coderForFieldType(fieldType);
        }
      }

      TypeDescription.Generic coderType =
          TypeDescription.Generic.Builder.parameterizedType(Coder.class, Row.class).build();
      DynamicType.Builder<Coder> builder =
          (DynamicType.Builder<Coder>)
              BYTE_BUDDY
                  .subclass(coderType)
                  .modifiers(Visibility.PUBLIC, TypeManifestation.FINAL);
      builder = implementMethods(schema, componentCoders, encodingPosToRowIndex, builder);

      Coder<Row> rowCoder;
      try {
        rowCoder =
            builder
                .visit(
                    new AsmVisitorWrapper.ForDeclaredMethods()
                        .writerFlags(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS))
                .make()
                .load(
                    ReflectHelpers.findClassLoader(Coder.class.getClassLoader()),
                    getClassLoadingStrategy(Coder.class))
                .getLoaded()
                .getDeclaredConstructor()
                .newInstance();
      } catch (InstantiationException
          | IllegalAccessException
          | NoSuchMethodException
          | InvocationTargetException e) {
        throw new RuntimeException("Unable to generate coder for schema " + schema, e);
      }
      String stackTrace = getStackTrace();
      GENERATED_CODERS.put(uuid, new WithStackTrace<>(rowCoder, stackTrace));
      LOG.debug(
          "Created row coder for uuid {} with encoding positions {} at {}",
          uuid,
          encodingPositions,
          stackTrace);
      return rowCoder;
    }
  }

  private static DynamicType.Builder<Coder> implementMethods(
      Schema schema,
      Coder[] componentCoders,
      int[] encodingPosToRowIndex,
      DynamicType.Builder<Coder> builder) {
    boolean hasNullableFields =
        schema.getFields().stream().map(Field::getType).anyMatch(FieldType::getNullable);
    boolean staticEncoding =
        schema.getOptions().getValueOrDefault(SCHEMA_OPTION_STATIC_ENCODING, false);
    boolean useUnrolledGeneratedRow = RowGenerator.isSupported(schema);
    @Nullable
    Class<? extends Row> generatedRowClass =
        useUnrolledGeneratedRow ? RowGenerator.getFactory(schema).rowClass() : null;

    return builder
        .method(ElementMatchers.named("encode").and(ElementMatchers.takesArguments(2)))
        .intercept(
            new EncodeInstruction(
                schema,
                componentCoders,
                encodingPosToRowIndex,
                hasNullableFields,
                staticEncoding,
                generatedRowClass))
        .method(ElementMatchers.named("decode").and(ElementMatchers.takesArguments(1)))
        .intercept(
            new DecodeInstruction(
                schema,
                componentCoders,
                encodingPosToRowIndex,
                hasNullableFields,
                staticEncoding,
                generatedRowClass));
  }

  // Static primitive/built-in coder helpers called directly by unrolled generated bytecode.

  public static void writeByte(byte v, OutputStream out) throws IOException {
    out.write(v);
  }

  public static byte readByte(InputStream in) throws IOException {
    int b = in.read();
    if (b == -1) {
      throw new CoderException(new EOFException("EOF encountered decoding 1 byte from input stream"));
    }
    return (byte) b;
  }

  public static void writeShort(short v, OutputStream out) throws IOException {
    BitConverters.writeBigEndianShort(v, out);
  }

  public static short readShort(InputStream in) throws IOException {
    try {
      return BitConverters.readBigEndianShort(in);
    } catch (EOFException e) {
      throw new CoderException(e);
    }
  }

  public static void writeFloat(float v, OutputStream out) throws IOException {
    BitConverters.writeBigEndianInt(Float.floatToIntBits(v), out);
  }

  public static float readFloat(InputStream in) throws IOException {
    try {
      return Float.intBitsToFloat(BitConverters.readBigEndianInt(in));
    } catch (EOFException e) {
      throw new CoderException(e);
    }
  }

  public static void writeDouble(double v, OutputStream out) throws IOException {
    long bits = Double.doubleToLongBits(v);
    BitConverters.writeBigEndianInt((int) (bits >>> 32), out);
    BitConverters.writeBigEndianInt((int) bits, out);
  }

  public static double readDouble(InputStream in) throws IOException {
    try {
      long hi = BitConverters.readBigEndianInt(in) & 0xFFFFFFFFL;
      long lo = BitConverters.readBigEndianInt(in) & 0xFFFFFFFFL;
      return Double.longBitsToDouble((hi << 32) | lo);
    } catch (EOFException e) {
      throw new CoderException(e);
    }
  }

  public static void writeBoolean(boolean v, OutputStream out) throws IOException {
    out.write(v ? 1 : 0);
  }

  public static boolean readBoolean(InputStream in) throws IOException {
    int b = in.read();
    if (b == 0) {
      return false;
    } else if (b == 1) {
      return true;
    } else if (b == -1) {
      throw new CoderException(new EOFException("EOF encountered decoding 1 byte from input stream"));
    }
    throw new IOException(String.format("Expected 0 or 1, got %d", (byte) b));
  }

  public static void writeString(String v, OutputStream out) throws IOException {
    STRING_CODER.encode(v, out);
  }

  public static String readString(InputStream in) throws IOException {
    return STRING_CODER.decode(in);
  }

  public static void writeBytes(byte[] v, OutputStream out) throws IOException {
    BYTE_ARRAY_CODER.encode(v, out);
  }

  public static byte[] readBytes(InputStream in) throws IOException {
    return BYTE_ARRAY_CODER.decode(in);
  }

  public static void writeDecimal(BigDecimal v, OutputStream out) throws IOException {
    BIG_DECIMAL_CODER.encode(v, out);
  }

  public static BigDecimal readDecimal(InputStream in) throws IOException {
    return BIG_DECIMAL_CODER.decode(in);
  }

  public static void writeDateTime(ReadableInstant v, OutputStream out) throws IOException {
    long shiftedMillis = v.getMillis() - Long.MIN_VALUE;
    BitConverters.writeBigEndianInt((int) (shiftedMillis >>> 32), out);
    BitConverters.writeBigEndianInt((int) shiftedMillis, out);
  }

  public static ReadableInstant readDateTime(InputStream in) throws IOException {
    try {
      long hi = BitConverters.readBigEndianInt(in) & 0xFFFFFFFFL;
      long lo = BitConverters.readBigEndianInt(in) & 0xFFFFFFFFL;
      long shiftedMillis = (hi << 32) | lo;
      return new Instant(shiftedMillis + Long.MIN_VALUE);
    } catch (EOFException e) {
      throw new CoderException(e);
    }
  }

  public static void writeNullMask(long nullMask, OutputStream out) throws IOException {
    if (nullMask == 0L) {
      out.write(0);
    } else {
      int numBytes = (64 - Long.numberOfLeadingZeros(nullMask) + 7) >>> 3;
      out.write(numBytes);
      for (long x = nullMask; x != 0; x >>>= 8) {
        out.write((int) (x & 0xFF));
      }
    }
  }

  public static long readNullMask(InputStream inputStream, int nullBytesLength) throws IOException {
    if (nullBytesLength == 0) {
      return 0L;
    }
    if (nullBytesLength <= 8) {
      long nullMask = 0L;
      for (int i = 0; i < nullBytesLength; ++i) {
        int b = inputStream.read();
        if (b < 0) {
          throw new EOFException();
        }
        nullMask |= (b & 0xFFL) << (i << 3);
      }
      return nullMask;
    }
    byte[] bytes = new byte[nullBytesLength];
    ByteStreams.readFully(inputStream, bytes);
    long nullMask = 0L;
    for (int i = 0; i < 8; ++i) {
      nullMask |= (bytes[i] & 0xFFL) << (i << 3);
    }
    return nullMask;
  }

  @SuppressWarnings("unchecked")
  public static void writeLogicalLong(LogicalType logicalType, Object v, OutputStream out)
      throws IOException {
    VarInt.encode(((Number) logicalType.toBaseType(v)).longValue(), out);
  }

  @SuppressWarnings("unchecked")
  public static Object readLogicalLong(LogicalType logicalType, InputStream in)
      throws IOException {
    return logicalType.toInputType(VarInt.decodeLong(in));
  }

  @SuppressWarnings("unchecked")
  public static void writeLogicalInt(LogicalType logicalType, Object v, OutputStream out)
      throws IOException {
    VarInt.encode(((Number) logicalType.toBaseType(v)).intValue(), out);
  }

  @SuppressWarnings("unchecked")
  public static Object readLogicalInt(LogicalType logicalType, InputStream in)
      throws IOException {
    return logicalType.toInputType(VarInt.decodeInt(in));
  }

  @SuppressWarnings("unchecked")
  public static void writeLogicalString(LogicalType logicalType, Object v, OutputStream out)
      throws IOException {
    STRING_CODER.encode((String) logicalType.toBaseType(v), out);
  }

  @SuppressWarnings("unchecked")
  public static Object readLogicalString(LogicalType logicalType, InputStream in)
      throws IOException {
    return logicalType.toInputType(STRING_CODER.decode(in));
  }

  static boolean isIdentityPermutation(int[] encodingPosToRowIndex) {
    for (int i = 0; i < encodingPosToRowIndex.length; i++) {
      if (encodingPosToRowIndex[i] != i) {
        return false;
      }
    }
    return true;
  }

  private static class EncodeInstruction implements Implementation {
    private final Schema schema;
    private final Coder[] componentCoders;
    private final int[] encodingPosToRowIndex;
    private final boolean hasNullableFields;
    private final boolean staticEncoding;
    private final @Nullable Class<? extends Row> generatedRowClass;

    EncodeInstruction(
        Schema schema,
        Coder[] componentCoders,
        int[] encodingPosToRowIndex,
        boolean hasNullableFields,
        boolean staticEncoding,
        @Nullable Class<? extends Row> generatedRowClass) {
      this.schema = schema;
      this.componentCoders = componentCoders;
      this.encodingPosToRowIndex = encodingPosToRowIndex;
      this.hasNullableFields = hasNullableFields;
      this.staticEncoding = staticEncoding;
      this.generatedRowClass = generatedRowClass;
    }

    @Override
    public ByteCodeAppender appender(Target implementationTarget) {
      String delegateMethodName =
          staticEncoding
              ? "encodeStatic"
              : (hasNullableFields ? "encodeNullable" : "encodeNonNullable");

      return (mv, implementationContext, instrumentedMethod) -> {
        if (generatedRowClass != null) {
          String rowInternal = Type.getInternalName(generatedRowClass);
          Label fallbackLabel = new Label();
          mv.visitVarInsn(Opcodes.ALOAD, 1);
          mv.visitTypeInsn(Opcodes.INSTANCEOF, rowInternal);
          mv.visitJumpInsn(Opcodes.IFEQ, fallbackLabel);

          emitUnrolledEncode(mv, rowInternal);
          mv.visitInsn(Opcodes.RETURN);

          mv.visitLabel(fallbackLabel);
          emitUnrolledGeneralRowEncode(mv);
          mv.visitInsn(Opcodes.RETURN);
          return new ByteCodeAppender.Size(8, 6);
        }

        // Fallback to static delegate for >64 fields
        emitLdcConstant(mv, "CODERS", Coder[].class, componentCoders);
        emitLdcConstant(mv, "POSITIONS", int[].class, encodingPosToRowIndex);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, INTERNAL_ROW_NAME);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            Type.getInternalName(EncodeInstruction.class),
            delegateMethodName,
            "(" + CODER_ARRAY_DESC + INT_ARRAY_DESC + ROW_DESC + OUTPUT_STREAM_DESC + ")V",
            false);
        mv.visitInsn(Opcodes.RETURN);
        return new ByteCodeAppender.Size(8, 6);
      };
    }

    private void emitUnrolledGeneralRowEncode(MethodVisitor mv) {
      int fieldCount = schema.getFieldCount();
      // Local 3: Row row
      mv.visitVarInsn(Opcodes.ALOAD, 1);
      mv.visitTypeInsn(Opcodes.CHECKCAST, INTERNAL_ROW_NAME);
      mv.visitVarInsn(Opcodes.ASTORE, 3);

      // Local 4..5: long wireNullMask (bits in wire encPos order)
      if (hasNullableFields) {
        mv.visitInsn(Opcodes.LCONST_0);
        for (int encPos = 0; encPos < fieldCount; encPos++) {
          int rowIdx = encodingPosToRowIndex[encPos];
          if (schema.getField(rowIdx).getType().getNullable()) {
            Label notNull = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            pushInt(mv, rowIdx);
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, INTERNAL_ROW_NAME, "isNull", "(I)Z", false);
            mv.visitJumpInsn(Opcodes.IFEQ, notNull);
            mv.visitLdcInsn(1L << encPos);
            mv.visitInsn(Opcodes.LOR);
            mv.visitLabel(notNull);
          }
        }
        mv.visitVarInsn(Opcodes.LSTORE, 4);
      }

      if (!staticEncoding) {
        pushInt(mv, fieldCount);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            INTERNAL_VARINT_NAME,
            "encode",
            "(I" + OUTPUT_STREAM_DESC + ")V",
            false);

        if (!hasNullableFields) {
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitInsn(Opcodes.ICONST_0);
          mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/OutputStream", "write", "(I)V", false);
        } else {
          mv.visitVarInsn(Opcodes.LLOAD, 4);
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "writeNullMask",
              "(J" + OUTPUT_STREAM_DESC + ")V",
              false);
        }
      }

      for (int encPos = 0; encPos < fieldCount; encPos++) {
        int rowIdx = encodingPosToRowIndex[encPos];
        Field field = schema.getField(rowIdx);
        FieldType ft = field.getType();

        Label skipField = new Label();
        if (ft.getNullable()) {
          mv.visitVarInsn(Opcodes.LLOAD, 4);
          mv.visitLdcInsn(1L << encPos);
          mv.visitInsn(Opcodes.LAND);
          mv.visitInsn(Opcodes.LCONST_0);
          mv.visitInsn(Opcodes.LCMP);
          mv.visitJumpInsn(Opcodes.IFNE, skipField);
        }

        emitEncodeGeneralRowSingleField(mv, encPos, rowIdx, componentCoders[encPos], ft);

        if (ft.getNullable()) {
          mv.visitLabel(skipField);
        }
      }
    }

    private static void emitEncodeGeneralRowSingleField(
        MethodVisitor mv, int encPos, int rowIdx, Coder<?> fieldCoder, FieldType ft) {
      TypeName typeName = ft.getTypeName();
      switch (typeName) {
        case BYTE:
          mv.visitVarInsn(Opcodes.ALOAD, 3);
          pushInt(mv, rowIdx);
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL, INTERNAL_ROW_NAME, "getPrimitiveByte", "(I)B", false);
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "writeByte",
              "(B" + OUTPUT_STREAM_DESC + ")V",
              false);
          break;
        case INT16:
          mv.visitVarInsn(Opcodes.ALOAD, 3);
          pushInt(mv, rowIdx);
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL, INTERNAL_ROW_NAME, "getPrimitiveInt16", "(I)S", false);
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "writeShort",
              "(S" + OUTPUT_STREAM_DESC + ")V",
              false);
          break;
        case INT32:
          mv.visitVarInsn(Opcodes.ALOAD, 3);
          pushInt(mv, rowIdx);
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL, INTERNAL_ROW_NAME, "getPrimitiveInt32", "(I)I", false);
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_VARINT_NAME,
              "encode",
              "(I" + OUTPUT_STREAM_DESC + ")V",
              false);
          break;
        case INT64:
          mv.visitVarInsn(Opcodes.ALOAD, 3);
          pushInt(mv, rowIdx);
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL, INTERNAL_ROW_NAME, "getPrimitiveInt64", "(I)J", false);
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_VARINT_NAME,
              "encode",
              "(J" + OUTPUT_STREAM_DESC + ")V",
              false);
          break;
        case FLOAT:
          mv.visitVarInsn(Opcodes.ALOAD, 3);
          pushInt(mv, rowIdx);
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL, INTERNAL_ROW_NAME, "getPrimitiveFloat", "(I)F", false);
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "writeFloat",
              "(F" + OUTPUT_STREAM_DESC + ")V",
              false);
          break;
        case DOUBLE:
          mv.visitVarInsn(Opcodes.ALOAD, 3);
          pushInt(mv, rowIdx);
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL, INTERNAL_ROW_NAME, "getPrimitiveDouble", "(I)D", false);
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "writeDouble",
              "(D" + OUTPUT_STREAM_DESC + ")V",
              false);
          break;
        case BOOLEAN:
          mv.visitVarInsn(Opcodes.ALOAD, 3);
          pushInt(mv, rowIdx);
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL, INTERNAL_ROW_NAME, "getPrimitiveBoolean", "(I)Z", false);
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "writeBoolean",
              "(Z" + OUTPUT_STREAM_DESC + ")V",
              false);
          break;
        case STRING:
          mv.visitVarInsn(Opcodes.ALOAD, 3);
          pushInt(mv, rowIdx);
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL, INTERNAL_ROW_NAME, "getValue", "(I)" + OBJECT_DESC, false);
          mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "writeString",
              "(Ljava/lang/String;" + OUTPUT_STREAM_DESC + ")V",
              false);
          break;
        case BYTES:
          mv.visitVarInsn(Opcodes.ALOAD, 3);
          pushInt(mv, rowIdx);
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL, INTERNAL_ROW_NAME, "getValue", "(I)" + OBJECT_DESC, false);
          mv.visitTypeInsn(Opcodes.CHECKCAST, "[B");
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "writeBytes",
              "([B" + OUTPUT_STREAM_DESC + ")V",
              false);
          break;
        case DECIMAL:
          mv.visitVarInsn(Opcodes.ALOAD, 3);
          pushInt(mv, rowIdx);
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL, INTERNAL_ROW_NAME, "getValue", "(I)" + OBJECT_DESC, false);
          mv.visitTypeInsn(Opcodes.CHECKCAST, "java/math/BigDecimal");
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "writeDecimal",
              "(Ljava/math/BigDecimal;" + OUTPUT_STREAM_DESC + ")V",
              false);
          break;
        case DATETIME:
          mv.visitVarInsn(Opcodes.ALOAD, 3);
          pushInt(mv, rowIdx);
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL, INTERNAL_ROW_NAME, "getValue", "(I)" + OBJECT_DESC, false);
          mv.visitTypeInsn(Opcodes.CHECKCAST, "org/joda/time/ReadableInstant");
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "writeDateTime",
              "(Lorg/joda/time/ReadableInstant;" + OUTPUT_STREAM_DESC + ")V",
              false);
          break;
        case ROW:
          {
            Class<?> coderClass = accessibleCoderClass(fieldCoder.getClass());
            emitLdcConstant(mv, "CODER_" + encPos, coderClass, fieldCoder);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            pushInt(mv, rowIdx);
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, INTERNAL_ROW_NAME, "getValue", "(I)" + OBJECT_DESC, false);
            mv.visitTypeInsn(Opcodes.CHECKCAST, INTERNAL_ROW_NAME);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(coderClass),
                "encode",
                "(" + ROW_DESC + OUTPUT_STREAM_DESC + ")V",
                false);
            break;
          }
        case MAP:
          {
            Class<?> coderClass = accessibleCoderClass(fieldCoder.getClass());
            emitLdcConstant(mv, "CODER_" + encPos, coderClass, fieldCoder);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            pushInt(mv, rowIdx);
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, INTERNAL_ROW_NAME, "getValue", "(I)" + OBJECT_DESC, false);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Map");
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(coderClass),
                "encode",
                "(Ljava/util/Map;" + OUTPUT_STREAM_DESC + ")V",
                false);
            break;
          }
        case ARRAY:
        case ITERABLE:
          {
            Class<?> coderClass = accessibleCoderClass(fieldCoder.getClass());
            emitLdcConstant(mv, "CODER_" + encPos, coderClass, fieldCoder);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            pushInt(mv, rowIdx);
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, INTERNAL_ROW_NAME, "getValue", "(I)" + OBJECT_DESC, false);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Iterable");
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(coderClass),
                "encode",
                "(Ljava/lang/Iterable;" + OUTPUT_STREAM_DESC + ")V",
                false);
            break;
          }
        case LOGICAL_TYPE:
          {
            LogicalType<?, ?> logicalType = ft.getLogicalType();
            TypeName baseTypeName = logicalType.getBaseType().getTypeName();
            if (baseTypeName == TypeName.INT64
                || baseTypeName == TypeName.INT32
                || baseTypeName == TypeName.STRING) {
              String helperName =
                  baseTypeName == TypeName.INT64
                      ? "writeLogicalLong"
                      : (baseTypeName == TypeName.INT32 ? "writeLogicalInt" : "writeLogicalString");
              emitLdcConstant(mv, "LOGICAL_" + encPos, LogicalType.class, logicalType);
              mv.visitVarInsn(Opcodes.ALOAD, 3);
              pushInt(mv, rowIdx);
              mv.visitMethodInsn(
                  Opcodes.INVOKEVIRTUAL,
                  INTERNAL_ROW_NAME,
                  "getValue",
                  "(I)" + OBJECT_DESC,
                  false);
              mv.visitVarInsn(Opcodes.ALOAD, 2);
              mv.visitMethodInsn(
                  Opcodes.INVOKESTATIC,
                  INTERNAL_GENERATOR_NAME,
                  helperName,
                  "(" + LOGICAL_TYPE_DESC + OBJECT_DESC + OUTPUT_STREAM_DESC + ")V",
                  false);
              break;
            }
          }
          // Fall through for other logical types
        default:
          {
            Class<?> coderClass = accessibleCoderClass(fieldCoder.getClass());
            emitLdcConstant(mv, "CODER_" + encPos, coderClass, fieldCoder);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            pushInt(mv, rowIdx);
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, INTERNAL_ROW_NAME, "getValue", "(I)" + OBJECT_DESC, false);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(coderClass),
                "encode",
                "(" + OBJECT_DESC + OUTPUT_STREAM_DESC + ")V",
                false);
            break;
          }
      }
    }

    private void emitUnrolledEncode(MethodVisitor mv, String rowInternal) {
      int fieldCount = schema.getFieldCount();
      // Local 3: typedRow
      mv.visitVarInsn(Opcodes.ALOAD, 1);
      mv.visitTypeInsn(Opcodes.CHECKCAST, rowInternal);
      mv.visitVarInsn(Opcodes.ASTORE, 3);

      // Local 4..5: long rowNullMask = typedRow.nullMask;
      mv.visitVarInsn(Opcodes.ALOAD, 3);
      mv.visitFieldInsn(Opcodes.GETFIELD, rowInternal, "nullMask", "J");
      mv.visitVarInsn(Opcodes.LSTORE, 4);

      if (!staticEncoding) {
        // VarInt.encode(fieldCount, outStream);
        pushInt(mv, fieldCount);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            INTERNAL_VARINT_NAME,
            "encode",
            "(I" + OUTPUT_STREAM_DESC + ")V",
            false);

        if (!hasNullableFields) {
          // outStream.write(0);
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitInsn(Opcodes.ICONST_0);
          mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/OutputStream", "write", "(I)V", false);
        } else if (isIdentityPermutation(encodingPosToRowIndex)) {
          // writeNullMask(rowNullMask, outStream);
          mv.visitVarInsn(Opcodes.LLOAD, 4);
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "writeNullMask",
              "(J" + OUTPUT_STREAM_DESC + ")V",
              false);
        } else {
          // Permute rowNullMask into wireMask according to encodingPosToRowIndex
          mv.visitInsn(Opcodes.LCONST_0);
          for (int encPos = 0; encPos < fieldCount; encPos++) {
            int rowIdx = encodingPosToRowIndex[encPos];
            if (schema.getField(rowIdx).getType().getNullable()) {
              Label notNull = new Label();
              mv.visitVarInsn(Opcodes.LLOAD, 4);
              mv.visitLdcInsn(1L << rowIdx);
              mv.visitInsn(Opcodes.LAND);
              mv.visitInsn(Opcodes.LCONST_0);
              mv.visitInsn(Opcodes.LCMP);
              mv.visitJumpInsn(Opcodes.IFEQ, notNull);
              mv.visitLdcInsn(1L << encPos);
              mv.visitInsn(Opcodes.LOR);
              mv.visitLabel(notNull);
            }
          }
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "writeNullMask",
              "(J" + OUTPUT_STREAM_DESC + ")V",
              false);
        }
      }

      // Unrolled per-field encoding in wire encodingPosition order
      for (int encPos = 0; encPos < fieldCount; encPos++) {
        int rowIdx = encodingPosToRowIndex[encPos];
        Field field = schema.getField(rowIdx);
        FieldType ft = field.getType();
        TypeName typeName = ft.getTypeName();
        String fieldName = "f" + rowIdx;
        String fieldDesc = getFieldDescriptor(typeName);

        Label skipField = new Label();
        if (ft.getNullable()) {
          mv.visitVarInsn(Opcodes.LLOAD, 4);
          mv.visitLdcInsn(1L << rowIdx);
          mv.visitInsn(Opcodes.LAND);
          mv.visitInsn(Opcodes.LCONST_0);
          mv.visitInsn(Opcodes.LCMP);
          mv.visitJumpInsn(Opcodes.IFNE, skipField);
        }

        emitEncodeSingleField(
            mv, rowInternal, encPos, componentCoders[encPos], fieldName, fieldDesc, ft);

        if (ft.getNullable()) {
          mv.visitLabel(skipField);
        }
      }
    }

    private static void emitEncodeSingleField(
        MethodVisitor mv,
        String rowInternal,
        int encPos,
        Coder<?> fieldCoder,
        String fieldName,
        String fieldDesc,
        FieldType ft) {
      TypeName typeName = ft.getTypeName();
      switch (typeName) {
        case BYTE:
          mv.visitVarInsn(Opcodes.ALOAD, 3);
          mv.visitFieldInsn(Opcodes.GETFIELD, rowInternal, fieldName, fieldDesc);
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "writeByte",
              "(B" + OUTPUT_STREAM_DESC + ")V",
              false);
          break;
        case INT16:
          mv.visitVarInsn(Opcodes.ALOAD, 3);
          mv.visitFieldInsn(Opcodes.GETFIELD, rowInternal, fieldName, fieldDesc);
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "writeShort",
              "(S" + OUTPUT_STREAM_DESC + ")V",
              false);
          break;
        case INT32:
          mv.visitVarInsn(Opcodes.ALOAD, 3);
          mv.visitFieldInsn(Opcodes.GETFIELD, rowInternal, fieldName, fieldDesc);
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_VARINT_NAME,
              "encode",
              "(I" + OUTPUT_STREAM_DESC + ")V",
              false);
          break;
        case INT64:
          mv.visitVarInsn(Opcodes.ALOAD, 3);
          mv.visitFieldInsn(Opcodes.GETFIELD, rowInternal, fieldName, fieldDesc);
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_VARINT_NAME,
              "encode",
              "(J" + OUTPUT_STREAM_DESC + ")V",
              false);
          break;
        case FLOAT:
          mv.visitVarInsn(Opcodes.ALOAD, 3);
          mv.visitFieldInsn(Opcodes.GETFIELD, rowInternal, fieldName, fieldDesc);
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "writeFloat",
              "(F" + OUTPUT_STREAM_DESC + ")V",
              false);
          break;
        case DOUBLE:
          mv.visitVarInsn(Opcodes.ALOAD, 3);
          mv.visitFieldInsn(Opcodes.GETFIELD, rowInternal, fieldName, fieldDesc);
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "writeDouble",
              "(D" + OUTPUT_STREAM_DESC + ")V",
              false);
          break;
        case BOOLEAN:
          mv.visitVarInsn(Opcodes.ALOAD, 3);
          mv.visitFieldInsn(Opcodes.GETFIELD, rowInternal, fieldName, fieldDesc);
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "writeBoolean",
              "(Z" + OUTPUT_STREAM_DESC + ")V",
              false);
          break;
        case STRING:
          mv.visitVarInsn(Opcodes.ALOAD, 3);
          mv.visitFieldInsn(Opcodes.GETFIELD, rowInternal, fieldName, fieldDesc);
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "writeString",
              "(Ljava/lang/String;" + OUTPUT_STREAM_DESC + ")V",
              false);
          break;
        case BYTES:
          mv.visitVarInsn(Opcodes.ALOAD, 3);
          mv.visitFieldInsn(Opcodes.GETFIELD, rowInternal, fieldName, fieldDesc);
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "writeBytes",
              "([B" + OUTPUT_STREAM_DESC + ")V",
              false);
          break;
        case DECIMAL:
          mv.visitVarInsn(Opcodes.ALOAD, 3);
          mv.visitFieldInsn(Opcodes.GETFIELD, rowInternal, fieldName, fieldDesc);
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "writeDecimal",
              "(Ljava/math/BigDecimal;" + OUTPUT_STREAM_DESC + ")V",
              false);
          break;
        case DATETIME:
          mv.visitVarInsn(Opcodes.ALOAD, 3);
          mv.visitFieldInsn(Opcodes.GETFIELD, rowInternal, fieldName, fieldDesc);
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "writeDateTime",
              "(Lorg/joda/time/ReadableInstant;" + OUTPUT_STREAM_DESC + ")V",
              false);
          break;
        case ROW:
          {
            Class<?> coderClass = accessibleCoderClass(fieldCoder.getClass());
            emitLdcConstant(mv, "CODER_" + encPos, coderClass, fieldCoder);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitFieldInsn(Opcodes.GETFIELD, rowInternal, fieldName, fieldDesc);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(coderClass),
                "encode",
                "(" + ROW_DESC + OUTPUT_STREAM_DESC + ")V",
                false);
            break;
          }
        case MAP:
          {
            Class<?> coderClass = accessibleCoderClass(fieldCoder.getClass());
            emitLdcConstant(mv, "CODER_" + encPos, coderClass, fieldCoder);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitFieldInsn(Opcodes.GETFIELD, rowInternal, fieldName, fieldDesc);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(coderClass),
                "encode",
                "(Ljava/util/Map;" + OUTPUT_STREAM_DESC + ")V",
                false);
            break;
          }
        case ARRAY:
        case ITERABLE:
          {
            Class<?> coderClass = accessibleCoderClass(fieldCoder.getClass());
            emitLdcConstant(mv, "CODER_" + encPos, coderClass, fieldCoder);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitFieldInsn(Opcodes.GETFIELD, rowInternal, fieldName, fieldDesc);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(coderClass),
                "encode",
                "(Ljava/lang/Iterable;" + OUTPUT_STREAM_DESC + ")V",
                false);
            break;
          }
        case LOGICAL_TYPE:
          {
            LogicalType<?, ?> logicalType = ft.getLogicalType();
            TypeName baseTypeName = logicalType.getBaseType().getTypeName();
            if (baseTypeName == TypeName.INT64
                || baseTypeName == TypeName.INT32
                || baseTypeName == TypeName.STRING) {
              String helperName =
                  baseTypeName == TypeName.INT64
                      ? "writeLogicalLong"
                      : (baseTypeName == TypeName.INT32 ? "writeLogicalInt" : "writeLogicalString");
              emitLdcConstant(mv, "LOGICAL_" + encPos, LogicalType.class, logicalType);
              mv.visitVarInsn(Opcodes.ALOAD, 3);
              mv.visitFieldInsn(Opcodes.GETFIELD, rowInternal, fieldName, fieldDesc);
              mv.visitVarInsn(Opcodes.ALOAD, 2);
              mv.visitMethodInsn(
                  Opcodes.INVOKESTATIC,
                  INTERNAL_GENERATOR_NAME,
                  helperName,
                  "(" + LOGICAL_TYPE_DESC + OBJECT_DESC + OUTPUT_STREAM_DESC + ")V",
                  false);
              break;
            }
          }
          // Fall through for other logical types
        default:
          // Complex LOGICAL_TYPE -> condy LDC coder.encode(typedRow.f_rowIdx, out)
          Class<?> coderClass = accessibleCoderClass(fieldCoder.getClass());
          emitLdcConstant(mv, "CODER_" + encPos, coderClass, fieldCoder);
          mv.visitVarInsn(Opcodes.ALOAD, 3);
          mv.visitFieldInsn(Opcodes.GETFIELD, rowInternal, fieldName, fieldDesc);
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL,
              Type.getInternalName(coderClass),
              "encode",
              "(" + OBJECT_DESC + OUTPUT_STREAM_DESC + ")V",
              false);
          break;
      }
    }

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      return instrumentedType;
    }

    @SuppressWarnings("unchecked")
    static void encodeStatic(
        Coder[] coders, int[] encodingPosToIndex, Row value, OutputStream outputStream)
        throws IOException {
      int fieldCount = value.getFieldCount();
      checkState(fieldCount == value.getSchema().getFieldCount());
      checkState(encodingPosToIndex.length == fieldCount);
      for (int encodingPos = 0; encodingPos < fieldCount; ++encodingPos) {
        @Nullable Object fieldValue = value.getValue(encodingPosToIndex[encodingPos]);
        if (fieldValue != null) {
          coders[encodingPos].encode(fieldValue, outputStream);
        }
      }
    }

    @SuppressWarnings("unchecked")
    static void encodeNonNullable(
        Coder[] coders, int[] encodingPosToIndex, Row value, OutputStream outputStream)
        throws IOException {
      int fieldCount = value.getFieldCount();
      checkState(fieldCount == value.getSchema().getFieldCount());
      checkState(encodingPosToIndex.length == fieldCount);
      VarInt.encode(fieldCount, outputStream);
      outputStream.write(0);
      for (int encodingPos = 0; encodingPos < fieldCount; ++encodingPos) {
        @Nullable Object fieldValue = value.getValue(encodingPosToIndex[encodingPos]);
        if (fieldValue != null) {
          coders[encodingPos].encode(fieldValue, outputStream);
        }
      }
    }

    // The encode method of the generated Coder delegates to this method to evaluate all of the
    // per-field Coders when the schema has nullable fields.
    @SuppressWarnings("unchecked")
    static void encodeNullable(
        Coder[] coders, int[] encodingPosToIndex, Row value, OutputStream outputStream)
        throws IOException {
      int fieldCount = value.getFieldCount();
      checkState(fieldCount == value.getSchema().getFieldCount());
      checkState(encodingPosToIndex.length == fieldCount);

      // Encode the field count. This allows us to handle compatible schema changes.
      VarInt.encode(fieldCount, outputStream);

      // Extract the values out once so that both null scanning and encoding share them.
      Object[] fieldValues = new Object[fieldCount];
      if (fieldCount <= 64) {
        long nullMask = 0L;
        for (int encodingPos = 0; encodingPos < fieldCount; ++encodingPos) {
          @Nullable Object fieldValue = value.getValue(encodingPosToIndex[encodingPos]);
          if (fieldValue == null) {
            nullMask |= 1L << encodingPos;
          } else {
            fieldValues[encodingPos] = fieldValue;
          }
        }
        writeNullMask(nullMask, outputStream);
        for (int encodingPos = 0; encodingPos < fieldCount; ++encodingPos) {
          @Nullable Object fieldValue = fieldValues[encodingPos];
          if (fieldValue != null) {
            coders[encodingPos].encode(fieldValue, outputStream);
          }
        }
        return;
      }

      for (int idx = 0; idx < fieldCount; ++idx) {
        fieldValues[idx] = value.getValue(idx);
      }

      // Encode a bitmap for the null fields to save having to encode a bunch of nulls.
      NULL_LIST_CODER.encode(scanNullFields(fieldValues, encodingPosToIndex), outputStream);
      for (int encodingPos = 0; encodingPos < fieldCount; ++encodingPos) {
        @Nullable Object fieldValue = fieldValues[encodingPosToIndex[encodingPos]];
        if (fieldValue != null) {
          coders[encodingPos].encode(fieldValue, outputStream);
        }
      }
    }

    // Figure out which fields of the Row are null, and returns a BitSet. This allows us to save
    // on encoding each null field separately.
    private static BitSet scanNullFields(Object[] fieldValues, int[] encodingPosToIndex) {
      Preconditions.checkState(fieldValues.length == encodingPosToIndex.length);
      BitSet nullFields = new BitSet(fieldValues.length);
      for (int encodingPos = 0; encodingPos < encodingPosToIndex.length; ++encodingPos) {
        int fieldIndex = encodingPosToIndex[encodingPos];
        if (fieldValues[fieldIndex] == null) {
          nullFields.set(encodingPos);
        }
      }
      return nullFields;
    }
  }

  private static class DecodeInstruction implements Implementation {
    private final Schema schema;
    private final Coder[] componentCoders;
    private final int[] encodingPosToRowIndex;
    private final boolean hasNullableFields;
    private final boolean staticEncoding;
    private final @Nullable Class<? extends Row> generatedRowClass;

    DecodeInstruction(
        Schema schema,
        Coder[] componentCoders,
        int[] encodingPosToRowIndex,
        boolean hasNullableFields,
        boolean staticEncoding,
        @Nullable Class<? extends Row> generatedRowClass) {
      this.schema = schema;
      this.componentCoders = componentCoders;
      this.encodingPosToRowIndex = encodingPosToRowIndex;
      this.hasNullableFields = hasNullableFields;
      this.staticEncoding = staticEncoding;
      this.generatedRowClass = generatedRowClass;
    }

    @Override
    public ByteCodeAppender appender(Target implementationTarget) {
      String delegateMethodName = staticEncoding ? "decodeStatic" : "decodeDynamic";

      return (mv, implementationContext, instrumentedMethod) -> {
        if (generatedRowClass != null) {
          String rowInternal = Type.getInternalName(generatedRowClass);
          emitUnrolledDecode(mv, rowInternal);
          mv.visitInsn(Opcodes.ARETURN);
          return new ByteCodeAppender.Size(8, 8);
        }

        emitLdcConstant(mv, "SCHEMA", Schema.class, schema);
        emitLdcConstant(mv, "CODERS", Coder[].class, componentCoders);
        emitLdcConstant(mv, "POSITIONS", int[].class, encodingPosToRowIndex);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            Type.getInternalName(DecodeInstruction.class),
            delegateMethodName,
            "(" + SCHEMA_DESC + CODER_ARRAY_DESC + INT_ARRAY_DESC + INPUT_STREAM_DESC + ")" + ROW_DESC,
            false);
        mv.visitInsn(Opcodes.ARETURN);
        return new ByteCodeAppender.Size(6, 4);
      };
    }

    private void emitUnrolledDecode(MethodVisitor mv, String rowInternal) {
      int fieldCount = schema.getFieldCount();

      // Local 2: GeneratedRow row = new GeneratedRow(SCHEMA);
      mv.visitTypeInsn(Opcodes.NEW, rowInternal);
      mv.visitInsn(Opcodes.DUP);
      emitLdcConstant(mv, "SCHEMA", Schema.class, schema);
      mv.visitMethodInsn(
          Opcodes.INVOKESPECIAL, rowInternal, "<init>", "(" + SCHEMA_DESC + ")V", false);
      mv.visitVarInsn(Opcodes.ASTORE, 2);

      if (staticEncoding) {
        for (int encPos = 0; encPos < fieldCount; encPos++) {
          int rowIdx = encodingPosToRowIndex[encPos];
          Field field = schema.getField(rowIdx);
          FieldType ft = field.getType();
          String fieldName = "f" + rowIdx;
          String fieldDesc = getFieldDescriptor(ft.getTypeName());
          emitDecodeSingleField(
              mv, rowInternal, encPos, componentCoders[encPos], fieldName, fieldDesc, ft);
        }
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        return;
      }

      // Dynamic encoding:
      // Local 3: int wireFieldCount = VarInt.decodeInt(inStream);
      mv.visitVarInsn(Opcodes.ALOAD, 1);
      mv.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          INTERNAL_VARINT_NAME,
          "decodeInt",
          "(" + INPUT_STREAM_DESC + ")I",
          false);
      mv.visitVarInsn(Opcodes.ISTORE, 3);

      // Local 4: int nullBytesLen = VarInt.decodeInt(inStream);
      mv.visitVarInsn(Opcodes.ALOAD, 1);
      mv.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          INTERNAL_VARINT_NAME,
          "decodeInt",
          "(" + INPUT_STREAM_DESC + ")I",
          false);
      mv.visitVarInsn(Opcodes.ISTORE, 4);

      boolean identityOrder = isIdentityPermutation(encodingPosToRowIndex);
      if (!hasNullableFields && identityOrder) {
        // Fast path when schema has no nullable fields and wireFieldCount == fieldCount && nullBytesLen == 0
        Label generalPath = new Label();
        Label doneLabel = new Label();

        mv.visitVarInsn(Opcodes.ILOAD, 3);
        pushInt(mv, fieldCount);
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, generalPath);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitJumpInsn(Opcodes.IFNE, generalPath);

        for (int encPos = 0; encPos < fieldCount; encPos++) {
          int rowIdx = encodingPosToRowIndex[encPos];
          FieldType ft = schema.getField(rowIdx).getType();
          emitDecodeSingleField(
              mv,
              rowInternal,
              encPos,
              componentCoders[encPos],
              "f" + rowIdx,
              getFieldDescriptor(ft.getTypeName()),
              ft);
        }
        mv.visitJumpInsn(Opcodes.GOTO, doneLabel);

        mv.visitLabel(generalPath);
        emitGeneralDynamicDecode(mv, rowInternal, fieldCount, identityOrder);

        mv.visitLabel(doneLabel);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        return;
      }

      emitGeneralDynamicDecode(mv, rowInternal, fieldCount, identityOrder);
      mv.visitVarInsn(Opcodes.ALOAD, 2);
    }

    private void emitGeneralDynamicDecode(
        MethodVisitor mv, String rowInternal, int fieldCount, boolean identityOrder) {
      // Local 5..6: long wireNullMask = readNullMask(inStream, nullBytesLen);
      mv.visitVarInsn(Opcodes.ALOAD, 1);
      mv.visitVarInsn(Opcodes.ILOAD, 4);
      mv.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          INTERNAL_GENERATOR_NAME,
          "readNullMask",
          "(" + INPUT_STREAM_DESC + "I)J",
          false);
      mv.visitVarInsn(Opcodes.LSTORE, 5);

      if (!identityOrder) {
        // Local 7..8: long rowNullMask = 0L;
        mv.visitInsn(Opcodes.LCONST_0);
        mv.visitVarInsn(Opcodes.LSTORE, 7);
      }

      for (int encPos = 0; encPos < fieldCount; encPos++) {
        int rowIdx = encodingPosToRowIndex[encPos];
        Field field = schema.getField(rowIdx);
        FieldType ft = field.getType();
        String fieldName = "f" + rowIdx;
        String fieldDesc = getFieldDescriptor(ft.getTypeName());

        Label isNullLabel = new Label();
        Label endFieldLabel = new Label();

        // if (encPos >= wireFieldCount) goto isNullLabel
        pushInt(mv, encPos);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, isNullLabel);

        // if ((wireNullMask & (1L << encPos)) != 0) goto isNullLabel
        mv.visitVarInsn(Opcodes.LLOAD, 5);
        mv.visitLdcInsn(1L << encPos);
        mv.visitInsn(Opcodes.LAND);
        mv.visitInsn(Opcodes.LCONST_0);
        mv.visitInsn(Opcodes.LCMP);
        mv.visitJumpInsn(Opcodes.IFNE, isNullLabel);

        emitDecodeSingleField(
            mv, rowInternal, encPos, componentCoders[encPos], fieldName, fieldDesc, ft);
        if (!identityOrder) {
          mv.visitJumpInsn(Opcodes.GOTO, endFieldLabel);
        }

        mv.visitLabel(isNullLabel);
        if (!identityOrder) {
          mv.visitVarInsn(Opcodes.LLOAD, 7);
          mv.visitLdcInsn(1L << rowIdx);
          mv.visitInsn(Opcodes.LOR);
          mv.visitVarInsn(Opcodes.LSTORE, 7);
          mv.visitLabel(endFieldLabel);
        }
      }

      if (identityOrder) {
        long allFieldsMask = fieldCount == 64 ? -1L : (1L << fieldCount) - 1L;
        // If wireFieldCount < fieldCount, mark trailing fields [wireFieldCount .. fieldCount-1] as null
        Label maskReady = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        pushInt(mv, fieldCount);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, maskReady);

        // wireNullMask |= (-1L << wireFieldCount)
        mv.visitVarInsn(Opcodes.LLOAD, 5);
        mv.visitLdcInsn(-1L);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.LSHL);
        mv.visitInsn(Opcodes.LOR);
        mv.visitVarInsn(Opcodes.LSTORE, 5);

        mv.visitLabel(maskReady);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.LLOAD, 5);
        if (fieldCount < 64) {
          mv.visitLdcInsn(allFieldsMask);
          mv.visitInsn(Opcodes.LAND);
        }
        mv.visitFieldInsn(Opcodes.PUTFIELD, rowInternal, "nullMask", "J");
      } else {
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.LLOAD, 7);
        mv.visitFieldInsn(Opcodes.PUTFIELD, rowInternal, "nullMask", "J");
      }
    }

    private static void emitDecodeSingleField(
        MethodVisitor mv,
        String rowInternal,
        int encPos,
        Coder<?> fieldCoder,
        String fieldName,
        String fieldDesc,
        FieldType ft) {
      TypeName typeName = ft.getTypeName();
      mv.visitVarInsn(Opcodes.ALOAD, 2);
      switch (typeName) {
        case BYTE:
          mv.visitVarInsn(Opcodes.ALOAD, 1);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "readByte",
              "(" + INPUT_STREAM_DESC + ")B",
              false);
          break;
        case INT16:
          mv.visitVarInsn(Opcodes.ALOAD, 1);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "readShort",
              "(" + INPUT_STREAM_DESC + ")S",
              false);
          break;
        case INT32:
          mv.visitVarInsn(Opcodes.ALOAD, 1);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_VARINT_NAME,
              "decodeInt",
              "(" + INPUT_STREAM_DESC + ")I",
              false);
          break;
        case INT64:
          mv.visitVarInsn(Opcodes.ALOAD, 1);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_VARINT_NAME,
              "decodeLong",
              "(" + INPUT_STREAM_DESC + ")J",
              false);
          break;
        case FLOAT:
          mv.visitVarInsn(Opcodes.ALOAD, 1);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "readFloat",
              "(" + INPUT_STREAM_DESC + ")F",
              false);
          break;
        case DOUBLE:
          mv.visitVarInsn(Opcodes.ALOAD, 1);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "readDouble",
              "(" + INPUT_STREAM_DESC + ")D",
              false);
          break;
        case BOOLEAN:
          mv.visitVarInsn(Opcodes.ALOAD, 1);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "readBoolean",
              "(" + INPUT_STREAM_DESC + ")Z",
              false);
          break;
        case STRING:
          mv.visitVarInsn(Opcodes.ALOAD, 1);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "readString",
              "(" + INPUT_STREAM_DESC + ")Ljava/lang/String;",
              false);
          break;
        case BYTES:
          mv.visitVarInsn(Opcodes.ALOAD, 1);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "readBytes",
              "(" + INPUT_STREAM_DESC + ")[B",
              false);
          break;
        case DECIMAL:
          mv.visitVarInsn(Opcodes.ALOAD, 1);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "readDecimal",
              "(" + INPUT_STREAM_DESC + ")Ljava/math/BigDecimal;",
              false);
          break;
        case DATETIME:
          mv.visitVarInsn(Opcodes.ALOAD, 1);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              INTERNAL_GENERATOR_NAME,
              "readDateTime",
              "(" + INPUT_STREAM_DESC + ")Lorg/joda/time/ReadableInstant;",
              false);
          break;
        case ROW:
          {
            Class<?> coderClass = accessibleCoderClass(fieldCoder.getClass());
            emitLdcConstant(mv, "CODER_" + encPos, coderClass, fieldCoder);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(coderClass),
                "decode",
                "(" + INPUT_STREAM_DESC + ")" + ROW_DESC,
                false);
            break;
          }
        case MAP:
          {
            Class<?> coderClass = accessibleCoderClass(fieldCoder.getClass());
            emitLdcConstant(mv, "CODER_" + encPos, coderClass, fieldCoder);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(coderClass),
                "decode",
                "(" + INPUT_STREAM_DESC + ")Ljava/util/Map;",
                false);
            break;
          }
        case ITERABLE:
          {
            Class<?> coderClass = accessibleCoderClass(fieldCoder.getClass());
            emitLdcConstant(mv, "CODER_" + encPos, coderClass, fieldCoder);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(coderClass),
                "decode",
                "(" + INPUT_STREAM_DESC + ")Ljava/lang/Iterable;",
                false);
            break;
          }
        case ARRAY:
          {
            Class<?> coderClass = accessibleCoderClass(fieldCoder.getClass());
            emitLdcConstant(mv, "CODER_" + encPos, coderClass, fieldCoder);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(coderClass),
                "decode",
                "(" + INPUT_STREAM_DESC + ")Ljava/lang/Iterable;",
                false);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Collection");
            break;
          }
        case LOGICAL_TYPE:
          {
            LogicalType<?, ?> logicalType = ft.getLogicalType();
            TypeName baseTypeName = logicalType.getBaseType().getTypeName();
            if (baseTypeName == TypeName.INT64
                || baseTypeName == TypeName.INT32
                || baseTypeName == TypeName.STRING) {
              String helperName =
                  baseTypeName == TypeName.INT64
                      ? "readLogicalLong"
                      : (baseTypeName == TypeName.INT32 ? "readLogicalInt" : "readLogicalString");
              emitLdcConstant(mv, "LOGICAL_" + encPos, LogicalType.class, logicalType);
              mv.visitVarInsn(Opcodes.ALOAD, 1);
              mv.visitMethodInsn(
                  Opcodes.INVOKESTATIC,
                  INTERNAL_GENERATOR_NAME,
                  helperName,
                  "(" + LOGICAL_TYPE_DESC + INPUT_STREAM_DESC + ")" + OBJECT_DESC,
                  false);
              break;
            }
          }
          // Fall through for other logical types
        default:
          Class<?> coderClass = accessibleCoderClass(fieldCoder.getClass());
          emitLdcConstant(mv, "CODER_" + encPos, coderClass, fieldCoder);
          mv.visitVarInsn(Opcodes.ALOAD, 1);
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL,
              Type.getInternalName(coderClass),
              "decode",
              "(" + INPUT_STREAM_DESC + ")" + OBJECT_DESC,
              false);
          break;
      }
      mv.visitFieldInsn(Opcodes.PUTFIELD, rowInternal, fieldName, fieldDesc);
    }

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      return instrumentedType;
    }

    static Row decodeStatic(
        Schema schema, Coder[] coders, int[] encodingPosToIndex, InputStream inputStream)
        throws IOException {
      Object[] fieldValues = new Object[coders.length];
      for (int encodingPos = 0; encodingPos < coders.length; ++encodingPos) {
        fieldValues[encodingPosToIndex[encodingPos]] = coders[encodingPos].decode(inputStream);
      }
      return Row.withSchema(schema).attachValues(fieldValues);
    }

    // The decode method of the generated Coder delegates to this method to evaluate all of the
    // per-field Coders.
    static Row decodeDynamic(
        Schema schema, Coder[] coders, int[] encodingPosToIndex, InputStream inputStream)
        throws IOException {
      int fieldCount = VarInt.decodeInt(inputStream);
      int nullBytesLength = VarInt.decodeInt(inputStream);
      Object[] fieldValues = new Object[coders.length];
      int limit = Math.min(fieldCount, coders.length);
      if (nullBytesLength == 0) {
        for (int encodingPos = 0; encodingPos < limit; ++encodingPos) {
          fieldValues[encodingPosToIndex[encodingPos]] = coders[encodingPos].decode(inputStream);
        }
      } else if (nullBytesLength <= 8 && limit <= 64) {
        long nullMask = readNullMask(inputStream, nullBytesLength);
        for (int encodingPos = 0; encodingPos < limit; ++encodingPos) {
          if ((nullMask & (1L << encodingPos)) == 0) {
            fieldValues[encodingPosToIndex[encodingPos]] = coders[encodingPos].decode(inputStream);
          }
        }
      } else {
        decodeWithBitSet(
            coders, encodingPosToIndex, inputStream, fieldValues, limit, nullBytesLength);
      }
      return Row.withSchema(schema).attachValues(fieldValues);
    }

    private static void decodeWithBitSet(
        Coder[] coders,
        int[] encodingPosToIndex,
        InputStream inputStream,
        Object[] fieldValues,
        int limit,
        int nullBytesLength)
        throws IOException {
      byte[] bytes = new byte[nullBytesLength];
      ByteStreams.readFully(inputStream, bytes);
      BitSet nullFields = BitSet.valueOf(bytes);
      for (int encodingPos = 0; encodingPos < limit; ++encodingPos) {
        if (!nullFields.get(encodingPos)) {
          fieldValues[encodingPosToIndex[encodingPos]] = coders[encodingPos].decode(inputStream);
        }
      }
    }
  }
}
