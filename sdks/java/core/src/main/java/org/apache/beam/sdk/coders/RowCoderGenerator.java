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

import static org.apache.beam.sdk.util.ByteBuddyUtils.getClassLoadingStrategy;
import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions.checkState;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.FieldManifestation;
import net.bytebuddy.description.modifier.Ownership;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.description.type.TypeDescription.ForLoadedType;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.scaffold.InstrumentedType;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.ByteCodeAppender;
import net.bytebuddy.implementation.bytecode.ByteCodeAppender.Size;
import net.bytebuddy.implementation.bytecode.Duplication;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.member.FieldAccess;
import net.bytebuddy.implementation.bytecode.member.MethodInvocation;
import net.bytebuddy.implementation.bytecode.member.MethodReturn;
import net.bytebuddy.implementation.bytecode.member.MethodVariableAccess;
import net.bytebuddy.matcher.ElementMatchers;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.Schema.Field;
import org.apache.beam.sdk.schemas.Schema.FieldType;
import org.apache.beam.sdk.schemas.SchemaCoder;
import org.apache.beam.sdk.util.StringUtils;
import org.apache.beam.sdk.util.VarInt;
import org.apache.beam.sdk.util.common.ReflectHelpers;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.annotations.VisibleForTesting;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.Maps;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.io.ByteStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A utility for automatically generating a {@link Coder} for {@link Row} objects corresponding to a
 * specific schema. The resulting coder is loaded into the default ClassLoader and returned.
 *
 * <p>When {@link RowCoderGenerator#generate(Schema)} is called, a new subclass of {@literal
 * Coder<Row>} is generated for the specified schema. This class is generated using low-level
 * bytecode generation, and hardcodes encodings for all fields of the Schema. Empirically, this is
 * 30-40% faster than a coder that introspects the schema.
 *
 * <p>The generated class corresponds to the following Java class:
 *
 * <pre><code>
 * class SchemaRowCoder extends{@literal Coder<Row>} {
 *   // Generated array containing a coder for each field in the Schema.
 *   private static final Coder[] FIELD_CODERS;
 *
 *   // Generated method to return the schema this class corresponds to. Used during code
 *   // generation.
 *   private static getSchema() {
 *     return schema;
 *   }
 *
 *  {@literal @}Override
 *   public void encode(T value, OutputStream outStream) {
 *     // Delegate to a method that evaluates each coder in the static array.
 *     encodeDelegate(FIELD_CODERS, value, outStream);
 *   }
 *
 *  {@literal @}Override
 *   public abstract T decode(InputStream inStream) {
 *     // Delegate to a method that evaluates each coder in the static array.
 *     return decodeDelegate(FIELD_CODERS, inStream);
 *   }
 * }
 * </code></pre>
 */
@SuppressWarnings({
  "nullness", // TODO(https://github.com/apache/beam/issues/20497)
  "rawtypes"
})
public abstract class RowCoderGenerator {
  private static final ByteBuddy BYTE_BUDDY = new ByteBuddy();
  private static final BitSetCoder NULL_LIST_CODER = BitSetCoder.of();

  private static final String CODERS_FIELD_NAME = "FIELD_CODERS";
  private static final String POSITIONS_FIELD_NAME = "FIELD_ENCODING_POSITIONS";
  private static final String SCHEMA_OPTION_STATIC_ENCODING = "beam:option:row:static_encoding";

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
      TypeDescription.Generic coderType =
          TypeDescription.Generic.Builder.parameterizedType(Coder.class, Row.class).build();
      DynamicType.Builder<Coder> builder =
          (DynamicType.Builder<Coder>) BYTE_BUDDY.subclass(coderType);
      builder = implementMethods(schema, builder);

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
        componentCoders[i] =
            SchemaCoder.coderForFieldType(schema.getField(rowIndex).getType().withNullable(false));
      }

      builder =
          builder
              .defineField(
                  CODERS_FIELD_NAME, Coder[].class, Visibility.PRIVATE, FieldManifestation.FINAL)
              .defineField(
                  POSITIONS_FIELD_NAME, int[].class, Visibility.PRIVATE, FieldManifestation.FINAL)
              .defineConstructor(Modifier.PUBLIC)
              .withParameters(Coder[].class, int[].class)
              .intercept(new GeneratedCoderConstructor());

      Coder<Row> rowCoder;
      try {
        rowCoder =
            builder
                .make()
                .load(
                    ReflectHelpers.findClassLoader(Coder.class.getClassLoader()),
                    getClassLoadingStrategy(Coder.class))
                .getLoaded()
                .getDeclaredConstructor(Coder[].class, int[].class)
                .newInstance((Object) componentCoders, (Object) encodingPosToRowIndex);
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

  private static class GeneratedCoderConstructor implements Implementation {
    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      return instrumentedType;
    }

    @Override
    public ByteCodeAppender appender(final Target implementationTarget) {
      return (methodVisitor, implementationContext, instrumentedMethod) -> {
        int numLocals = 1 + instrumentedMethod.getParameters().size();
        StackManipulation stackManipulation =
            new StackManipulation.Compound(
                // Call the base constructor.
                MethodVariableAccess.loadThis(),
                Duplication.SINGLE,
                MethodInvocation.invoke(
                    new ForLoadedType(Coder.class)
                        .getDeclaredMethods()
                        .filter(
                            ElementMatchers.isConstructor().and(ElementMatchers.takesArguments(0)))
                        .getOnly()),
                Duplication.SINGLE,
                // Store the list of Coders as a member variable.
                MethodVariableAccess.REFERENCE.loadFrom(1),
                FieldAccess.forField(
                        implementationTarget
                            .getInstrumentedType()
                            .getDeclaredFields()
                            .filter(ElementMatchers.named(CODERS_FIELD_NAME))
                            .getOnly())
                    .write(),
                // Store the list of encoding offsets as a member variable.
                MethodVariableAccess.REFERENCE.loadFrom(2),
                FieldAccess.forField(
                        implementationTarget
                            .getInstrumentedType()
                            .getDeclaredFields()
                            .filter(ElementMatchers.named(POSITIONS_FIELD_NAME))
                            .getOnly())
                    .write(),
                MethodReturn.VOID);
        StackManipulation.Size size = stackManipulation.apply(methodVisitor, implementationContext);
        return new Size(size.getMaximalSize(), numLocals);
      };
    }
  }

  private static DynamicType.Builder<Coder> implementMethods(
      Schema schema, DynamicType.Builder<Coder> builder) {
    boolean hasNullableFields =
        schema.getFields().stream().map(Field::getType).anyMatch(FieldType::getNullable);
    boolean staticEncoding =
        schema.getOptions().getValueOrDefault(SCHEMA_OPTION_STATIC_ENCODING, false);
    return builder
        .defineMethod("getSchema", Schema.class, Visibility.PRIVATE, Ownership.STATIC)
        .intercept(FixedValue.reference(schema))
        .defineMethod("hasNullableFields", boolean.class, Visibility.PRIVATE, Ownership.STATIC)
        .intercept(FixedValue.reference(hasNullableFields))
        .method(ElementMatchers.named("encode"))
        .intercept(new EncodeInstruction(hasNullableFields, staticEncoding))
        .method(ElementMatchers.named("decode"))
        .intercept(new DecodeInstruction(staticEncoding));
  }

  private static class EncodeInstruction implements Implementation {
    static final ForLoadedType LOADED_TYPE = new ForLoadedType(EncodeInstruction.class);
    private final boolean hasNullableFields;
    private final boolean staticEncoding;

    EncodeInstruction(boolean hasNullableFields, boolean staticEncoding) {
      this.hasNullableFields = hasNullableFields;
      this.staticEncoding = staticEncoding;
    }

    @Override
    public ByteCodeAppender appender(Target implementationTarget) {
      String delegateMethodName =
          staticEncoding
              ? "encodeStatic"
              : (hasNullableFields ? "encodeNullable" : "encodeNonNullable");
      return (methodVisitor, implementationContext, instrumentedMethod) -> {
        StackManipulation manipulation =
            new StackManipulation.Compound(
                // Array of coders.
                MethodVariableAccess.loadThis(),
                FieldAccess.forField(
                        implementationContext
                            .getInstrumentedType()
                            .getDeclaredFields()
                            .filter(ElementMatchers.named(CODERS_FIELD_NAME))
                            .getOnly())
                    .read(),
                MethodVariableAccess.loadThis(),
                FieldAccess.forField(
                        implementationContext
                            .getInstrumentedType()
                            .getDeclaredFields()
                            .filter(ElementMatchers.named(POSITIONS_FIELD_NAME))
                            .getOnly())
                    .read(),
                // Element to encode. (offset 1, as offset 0 is always "this").
                MethodVariableAccess.REFERENCE.loadFrom(1),
                // OutputStream.
                MethodVariableAccess.REFERENCE.loadFrom(2),
                // Call EncodeInstruction delegate
                MethodInvocation.invoke(
                    LOADED_TYPE
                        .getDeclaredMethods()
                        .filter(
                            ElementMatchers.isStatic()
                                .and(ElementMatchers.named(delegateMethodName)))
                        .getOnly()),
                MethodReturn.VOID);
        StackManipulation.Size size = manipulation.apply(methodVisitor, implementationContext);
        return new ByteCodeAppender.Size(size.getMaximalSize(), instrumentedMethod.getStackSize());
      };
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
    static final ForLoadedType LOADED_TYPE = new ForLoadedType(DecodeInstruction.class);
    private final boolean staticEncoding;

    DecodeInstruction(boolean staticEncoding) {
      this.staticEncoding = staticEncoding;
    }

    @Override
    public ByteCodeAppender appender(Target implementationTarget) {
      String delegateMethodName = staticEncoding ? "decodeStatic" : "decodeDynamic";
      return (methodVisitor, implementationContext, instrumentedMethod) -> {
        StackManipulation manipulation =
            new StackManipulation.Compound(
                // Schema. Used in generation of DecodeInstruction.
                MethodInvocation.invoke(
                    implementationContext
                        .getInstrumentedType()
                        .getDeclaredMethods()
                        .filter(ElementMatchers.named("getSchema"))
                        .getOnly()),
                // Array of coders.
                MethodVariableAccess.loadThis(),
                FieldAccess.forField(
                        implementationContext
                            .getInstrumentedType()
                            .getDeclaredFields()
                            .filter(ElementMatchers.named(CODERS_FIELD_NAME))
                            .getOnly())
                    .read(),
                MethodVariableAccess.loadThis(),
                FieldAccess.forField(
                        implementationContext
                            .getInstrumentedType()
                            .getDeclaredFields()
                            .filter(ElementMatchers.named(POSITIONS_FIELD_NAME))
                            .getOnly())
                    .read(),
                // read the InputStream. (offset 1, as offset 0 is always "this").
                MethodVariableAccess.REFERENCE.loadFrom(1),
                MethodInvocation.invoke(
                    LOADED_TYPE
                        .getDeclaredMethods()
                        .filter(
                            ElementMatchers.isStatic()
                                .and(ElementMatchers.named(delegateMethodName)))
                        .getOnly()),
                MethodReturn.REFERENCE);
        StackManipulation.Size size = manipulation.apply(methodVisitor, implementationContext);
        return new ByteCodeAppender.Size(size.getMaximalSize(), instrumentedMethod.getStackSize());
      };
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
      // In the case of a schema change going backwards, fieldCount might be > coders.length,
      // in which case we drop the extra fields. If the schema was evolved to contain more fields,
      // the remaining elements of fieldValues are already null.
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
      // We call attachValues instead of setValues. setValues validates every element in the list
      // is of the proper type, potentially converts to the internal type Row stores, and copies
      // all values. Since we assume that decode is always being called on a previously-encoded
      // Row, the values should already be validated and of the correct type. So, we can save
      // some processing by simply transferring ownership of the list to the Row.
      return Row.withSchema(schema).attachValues(fieldValues);
    }

    private static long readNullMask(InputStream inputStream, int nullBytesLength)
        throws IOException {
      long nullMask = 0L;
      for (int i = 0; i < nullBytesLength; ++i) {
        int b = inputStream.read();
        if (b < 0) {
          throw new java.io.EOFException();
        }
        nullMask |= (b & 0xFFL) << (i << 3);
      }
      return nullMask;
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
