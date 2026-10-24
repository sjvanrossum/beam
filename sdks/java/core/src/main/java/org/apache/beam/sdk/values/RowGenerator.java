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
package org.apache.beam.sdk.values;

import static org.apache.beam.sdk.util.ByteBuddyUtils.getClassLoadingStrategy;

import java.io.ObjectStreamException;
import java.io.Serializable;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.AbstractList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.modifier.TypeManifestation;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.scaffold.InstrumentedType;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.ByteCodeAppender;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.matcher.ElementMatchers;
import org.apache.beam.sdk.annotations.Internal;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.Schema.Field;
import org.apache.beam.sdk.schemas.Schema.FieldType;
import org.apache.beam.sdk.schemas.Schema.TypeName;
import org.apache.beam.sdk.util.common.ReflectHelpers;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joda.time.ReadableInstant;

/**
 * Generates schema-specific {@link Row} subclasses using ByteBuddy where primitive fields are
 * stored unboxed alongside a 64-bit {@code nullMask} and reference fields are stored in typed
 * member fields.
 */
@Internal
@SuppressWarnings({
  "nullness", // TODO(https://github.com/apache/beam/issues/20497)
  "rawtypes"
})
public final class RowGenerator {
  private static final ByteBuddy BYTE_BUDDY = new ByteBuddy(ClassFileVersion.JAVA_V11);
  private static final String INTERNAL_ROW_NAME = Type.getInternalName(Row.class);
  private static final String INTERNAL_GENERATOR_NAME = Type.getInternalName(RowGenerator.class);
  private static final String SCHEMA_DESC = Type.getDescriptor(Schema.class);
  private static final String LIST_DESC = Type.getDescriptor(List.class);
  private static final String OBJECT_ARRAY_DESC = Type.getDescriptor(Object[].class);
  private static final String OBJECT_DESC = Type.getDescriptor(Object.class);

  /** Factory that instantiates a schema-typed {@link Row}. */
  @Internal
  public interface RowFactory {
    Row create(Schema schema);

    Row fromList(Schema schema, List<@Nullable Object> values);

    Row fromArray(Schema schema, @Nullable Object[] values);

    Row captureFromList(Schema schema, List<@Nullable Object> rawValues);

    Row nullRow(Schema schema);

    Class<? extends Row> rowClass();
  }

  private static final class SchemaTypeKey {
    private final TypeName[] typeNames;
    private final boolean[] nullables;
    private final int hashCode;

    SchemaTypeKey(Schema schema) {
      int n = schema.getFieldCount();
      this.typeNames = new TypeName[n];
      this.nullables = new boolean[n];
      int h = 1;
      for (int i = 0; i < n; i++) {
        FieldType ft = schema.getField(i).getType();
        TypeName tn = ft.getTypeName();
        boolean nul = ft.getNullable();
        this.typeNames[i] = tn;
        this.nullables[i] = nul;
        h = 31 * h + (tn.ordinal() << 1) + (nul ? 1 : 0);
      }
      this.hashCode = h;
    }

    @Override
    public int hashCode() {
      return hashCode;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof SchemaTypeKey)) {
        return false;
      }
      SchemaTypeKey other = (SchemaTypeKey) obj;
      if (this.hashCode != other.hashCode || this.typeNames.length != other.typeNames.length) {
        return false;
      }
      for (int i = 0; i < typeNames.length; i++) {
        if (typeNames[i] != other.typeNames[i] || nullables[i] != other.nullables[i]) {
          return false;
        }
      }
      return true;
    }
  }

  private static final ConcurrentHashMap<SchemaTypeKey, RowFactory> FACTORIES_BY_SHAPE =
      new ConcurrentHashMap<>();

  private RowGenerator() {}

  /** Returns true if this schema can use a generated schema-typed {@link Row} subclass. */
  public static boolean isSupported(Schema schema) {
    int count = schema.getFieldCount();
    return count > 0 && count <= 64;
  }

  public static RowFactory getFactory(Schema schema) {
    Object cached = schema.getRowFactory();
    if (cached != null) {
      return (RowFactory) cached;
    }
    RowFactory factory =
        FACTORIES_BY_SHAPE.computeIfAbsent(new SchemaTypeKey(schema), k -> generateFactory(schema));
    schema.setRowFactory(factory);
    return factory;
  }

  public static Row createFromList(Schema schema, List<@Nullable Object> values) {
    if (!isSupported(schema)) {
      return new RowWithStorage(schema, values);
    }
    return getFactory(schema).fromList(schema, values);
  }

  public static Row createFromArray(Schema schema, @Nullable Object[] values) {
    if (!isSupported(schema)) {
      return new RowWithStorage(schema, java.util.Arrays.asList(values));
    }
    return getFactory(schema).fromArray(schema, values);
  }

  public static Row captureRow(Schema schema, List<@Nullable Object> rawValues) {
    if (!isSupported(schema)) {
      return RowUtils.captureRowFallback(schema, rawValues);
    }
    return getFactory(schema).captureFromList(schema, rawValues);
  }

  public static Row createNullRow(Schema schema) {
    if (!isSupported(schema)) {
      return new RowWithStorage(schema, java.util.Collections.nCopies(schema.getFieldCount(), null));
    }
    return getFactory(schema).nullRow(schema);
  }

  /** Serialization proxy so generated Row instances serialize across JVMs cleanly. */
  private static final class SerializedRow implements Serializable {
    private final Schema schema;
    private final Object[] values;

    SerializedRow(Schema schema, Object[] values) {
      this.schema = schema;
      this.values = values;
    }

    Object readResolve() throws ObjectStreamException {
      return createFromArray(schema, values);
    }
  }

  public static Object toSerializedRow(Row row) {
    int n = row.getFieldCount();
    Object[] values = new Object[n];
    for (int i = 0; i < n; i++) {
      values[i] = row.getValue(i);
    }
    return new SerializedRow(row.getSchema(), values);
  }

  /** Lightweight view over a generated Row for {@link Row#getValues()}. */
  public static List<@Nullable Object> createValuesView(Row row) {
    return new RowValuesList(row);
  }

  private static final class RowValuesList extends AbstractList<@Nullable Object>
      implements RandomAccess, Serializable {
    private final Row row;

    RowValuesList(Row row) {
      this.row = row;
    }

    @Override
    public @Nullable Object get(int index) {
      return row.getValue(index);
    }

    @Override
    public int size() {
      return row.getFieldCount();
    }
  }

  /** Helper called by generated captureFromList for fields that require validation/conversion. */
  public static @Nullable Object captureFieldValue(
      Schema schema, int fieldIdx, @Nullable Object rawValue) {
    Field field = schema.getField(fieldIdx);
    return RowUtils.captureValue(field.getType(), rawValue, field.getName());
  }

  public static IllegalArgumentException nullNotAllowedException(Schema schema, int fieldIdx) {
    Field field = schema.getField(fieldIdx);
    return new IllegalArgumentException(
        String.format("%s is not nullable in field %s", field.getType(), field.getName()));
  }

  public static IllegalArgumentException invalidFieldIndexException(int fieldIdx) {
    return new IllegalArgumentException("No field at index " + fieldIdx);
  }

  public static boolean deepEqualsField(
      Row row, int fieldIdx, @Nullable Object a, @Nullable Object b) {
    return Row.Equals.deepEquals(a, b, row.getSchema().getField(fieldIdx).getType());
  }

  public static int deepHashCodeField(Row row, int fieldIdx, @Nullable Object a) {
    return Row.Equals.deepHashCode(a, row.getSchema().getField(fieldIdx).getType());
  }

  static Class<?> fieldJvmType(TypeName typeName) {
    switch (typeName) {
      case BYTE:
        return byte.class;
      case INT16:
        return short.class;
      case INT32:
        return int.class;
      case INT64:
        return long.class;
      case FLOAT:
        return float.class;
      case DOUBLE:
        return double.class;
      case BOOLEAN:
        return boolean.class;
      case STRING:
        return String.class;
      case BYTES:
        return byte[].class;
      case DECIMAL:
        return BigDecimal.class;
      case DATETIME:
        return ReadableInstant.class;
      case ROW:
        return Row.class;
      case ARRAY:
        return Collection.class;
      case ITERABLE:
        return Iterable.class;
      case MAP:
        return Map.class;
      case LOGICAL_TYPE:
      default:
        return Object.class;
    }
  }

  static String fieldName(int idx) {
    return "f" + idx;
  }

  @SuppressWarnings("unchecked")
  private static RowFactory generateFactory(Schema schema) {
    int fieldCount = schema.getFieldCount();
    TypeName[] typeNames = new TypeName[fieldCount];
    boolean[] nullables = new boolean[fieldCount];
    Class<?>[] jvmTypes = new Class<?>[fieldCount];
    String[] descriptors = new String[fieldCount];

    for (int i = 0; i < fieldCount; i++) {
      FieldType ft = schema.getField(i).getType();
      typeNames[i] = ft.getTypeName();
      nullables[i] = ft.getNullable();
      jvmTypes[i] = fieldJvmType(typeNames[i]);
      descriptors[i] = Type.getDescriptor(jvmTypes[i]);
    }

    DynamicType.Builder<Row> rowBuilder =
        BYTE_BUDDY
            .subclass(Row.class)
            .modifiers(Visibility.PUBLIC, TypeManifestation.FINAL)
            .defineField("nullMask", long.class, Visibility.PUBLIC);

    for (int i = 0; i < fieldCount; i++) {
      rowBuilder = rowBuilder.defineField(fieldName(i), jvmTypes[i], Visibility.PUBLIC);
    }

    rowBuilder =
        rowBuilder
            .defineConstructor(Modifier.PUBLIC)
            .withParameters(Schema.class, List.class)
            .intercept(
                new FromListConstructorImplementation(
                    fieldCount, typeNames, nullables, descriptors, false))
            .defineConstructor(Modifier.PUBLIC)
            .withParameters(Schema.class, List.class, boolean.class)
            .intercept(
                new FromListConstructorImplementation(
                    fieldCount, typeNames, nullables, descriptors, true))
            .defineConstructor(Modifier.PUBLIC)
            .withParameters(Schema.class, Object[].class)
            .intercept(new FromArrayConstructorImplementation(fieldCount, typeNames, descriptors))
            .method(ElementMatchers.named("getFieldCount"))
            .intercept(new GetFieldCountImplementation(fieldCount))
            .method(ElementMatchers.named("getValues"))
            .intercept(new GetValuesImplementation())
            .method(ElementMatchers.named("isNull").and(ElementMatchers.takesArguments(int.class)))
            .intercept(new IsNullImplementation(fieldCount))
            .method(ElementMatchers.named("getValue").and(ElementMatchers.takesArguments(int.class)))
            .intercept(new GetValueImplementation(fieldCount, typeNames, descriptors))
            .method(ElementMatchers.named("equals").and(ElementMatchers.takesArguments(Object.class)))
            .intercept(new EqualsImplementation(fieldCount, typeNames, descriptors))
            .method(ElementMatchers.named("hashCode").and(ElementMatchers.takesNoArguments()))
            .intercept(new HashCodeImplementation(fieldCount, typeNames, descriptors))
            .defineMethod("writeReplace", Object.class, Visibility.PRIVATE)
            .intercept(new WriteReplaceImplementation());

    for (TypeName primitiveType : PRIMITIVE_TYPES) {
      int count = 0;
      for (int i = 0; i < fieldCount; i++) {
        if (typeNames[i] == primitiveType) {
          count++;
        }
      }
      if (count > 0) {
        int[] matchingIndices = new int[count];
        int pos = 0;
        for (int i = 0; i < fieldCount; i++) {
          if (typeNames[i] == primitiveType) {
            matchingIndices[pos++] = i;
          }
        }
        rowBuilder =
            rowBuilder
                .method(
                    ElementMatchers.named(primitiveGetterName(primitiveType))
                        .and(ElementMatchers.takesArguments(int.class)))
                .intercept(new GetPrimitiveImplementation(primitiveType, matchingIndices))
                .method(
                    ElementMatchers.named(boxedGetterName(primitiveType))
                        .and(ElementMatchers.takesArguments(int.class)))
                .intercept(new GetBoxedPrimitiveImplementation(primitiveType, matchingIndices));
      }
    }

    Class<? extends Row> loadedRowClass =
        rowBuilder
            .visit(
                new AsmVisitorWrapper.ForDeclaredMethods()
                    .writerFlags(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS))
            .make()
            .load(
                ReflectHelpers.findClassLoader(Row.class.getClassLoader()),
                getClassLoadingStrategy(Row.class))
            .getLoaded();

    String rowInternalName = Type.getInternalName(loadedRowClass);
    long allNullMask = fieldCount == 64 ? -1L : (1L << fieldCount) - 1L;

    DynamicType.Builder<RowFactory> factoryBuilder =
        BYTE_BUDDY
            .subclass(RowFactory.class)
            .modifiers(Visibility.PUBLIC, TypeManifestation.FINAL)
            .method(ElementMatchers.named("create"))
            .intercept(new FactoryCreateImplementation(rowInternalName))
            .method(ElementMatchers.named("fromList"))
            .intercept(new FactoryFromListImplementation(rowInternalName))
            .method(ElementMatchers.named("fromArray"))
            .intercept(new FactoryFromArrayImplementation(rowInternalName))
            .method(ElementMatchers.named("captureFromList"))
            .intercept(new FactoryCaptureFromListImplementation(rowInternalName))
            .method(ElementMatchers.named("nullRow"))
            .intercept(new FactoryNullRowImplementation(rowInternalName, allNullMask))
            .method(ElementMatchers.named("rowClass"))
            .intercept(net.bytebuddy.implementation.FixedValue.value(loadedRowClass));

    try {
      return factoryBuilder
          .visit(
              new AsmVisitorWrapper.ForDeclaredMethods()
                  .writerFlags(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS))
          .make()
          .load(
              ReflectHelpers.findClassLoader(loadedRowClass.getClassLoader()),
              getClassLoadingStrategy(loadedRowClass))
          .getLoaded()
          .getDeclaredConstructor()
          .newInstance();
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException("Unable to generate RowFactory for schema " + schema, e);
    }
  }

  private static final class FromListConstructorImplementation implements Implementation {
    private final int fieldCount;
    private final TypeName[] typeNames;
    private final boolean[] nullables;
    private final String[] descriptors;
    private final boolean capture;

    FromListConstructorImplementation(
        int fieldCount,
        TypeName[] typeNames,
        boolean[] nullables,
        String[] descriptors,
        boolean capture) {
      this.fieldCount = fieldCount;
      this.typeNames = typeNames;
      this.nullables = nullables;
      this.descriptors = descriptors;
      this.capture = capture;
    }

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      return instrumentedType;
    }

    @Override
    public ByteCodeAppender appender(Target implementationTarget) {
      String owner = implementationTarget.getInstrumentedType().getInternalName();
      return (mv, context, method) -> {
        // super(schema)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL, INTERNAL_ROW_NAME, "<init>", "(" + SCHEMA_DESC + ")V", false);

        // Local 4 (or 3 if !capture): long mask = 0L;
        int maskLocal = capture ? 4 : 3;
        int valLocal = maskLocal + 2;
        mv.visitInsn(Opcodes.LCONST_0);
        mv.visitVarInsn(Opcodes.LSTORE, maskLocal);

        for (int i = 0; i < fieldCount; i++) {
          // Object val = list.get(i);
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          pushInt(mv, i);
          mv.visitMethodInsn(
              Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);

          if (capture && needsCaptureHelper(typeNames[i])) {
            // val = RowGenerator.captureFieldValue(schema, i, val);
            mv.visitVarInsn(Opcodes.ASTORE, valLocal);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            pushInt(mv, i);
            mv.visitVarInsn(Opcodes.ALOAD, valLocal);
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                INTERNAL_GENERATOR_NAME,
                "captureFieldValue",
                "(" + SCHEMA_DESC + "I" + OBJECT_DESC + ")" + OBJECT_DESC,
                false);
          }

          mv.visitVarInsn(Opcodes.ASTORE, valLocal);
          mv.visitVarInsn(Opcodes.ALOAD, valLocal);

          Label nonNullLabel = new Label();
          Label endFieldLabel = new Label();
          mv.visitJumpInsn(Opcodes.IFNONNULL, nonNullLabel);

          // Value is null
          if (capture && !nullables[i]) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            pushInt(mv, i);
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                INTERNAL_GENERATOR_NAME,
                "nullNotAllowedException",
                "(" + SCHEMA_DESC + "I)Ljava/lang/IllegalArgumentException;",
                false);
            mv.visitInsn(Opcodes.ATHROW);
          } else {
            // mask |= (1L << i)
            mv.visitVarInsn(Opcodes.LLOAD, maskLocal);
            mv.visitLdcInsn(1L << i);
            mv.visitInsn(Opcodes.LOR);
            mv.visitVarInsn(Opcodes.LSTORE, maskLocal);
            mv.visitJumpInsn(Opcodes.GOTO, endFieldLabel);
          }

          mv.visitLabel(nonNullLabel);
          // this.f_i = unbox/cast(val)
          mv.visitVarInsn(Opcodes.ALOAD, 0);
          mv.visitVarInsn(Opcodes.ALOAD, valLocal);
          if (capture && typeNames[i] == TypeName.DATETIME) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, "org/joda/time/base/AbstractInstant");
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "org/joda/time/base/AbstractInstant",
                "toInstant",
                "()Lorg/joda/time/Instant;",
                false);
          } else {
            unboxOrCast(mv, typeNames[i]);
          }
          mv.visitFieldInsn(Opcodes.PUTFIELD, owner, fieldName(i), descriptors[i]);

          mv.visitLabel(endFieldLabel);
        }

        // this.nullMask = mask;
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.LLOAD, maskLocal);
        mv.visitFieldInsn(Opcodes.PUTFIELD, owner, "nullMask", "J");
        mv.visitInsn(Opcodes.RETURN);
        return new ByteCodeAppender.Size(6, valLocal + 1);
      };
    }

    private static boolean needsCaptureHelper(TypeName typeName) {
      switch (typeName) {
        case ARRAY:
        case ITERABLE:
        case MAP:
        case ROW:
        case LOGICAL_TYPE:
        case BYTES:
          return true;
        default:
          return false;
      }
    }
  }

  private static final class FromArrayConstructorImplementation implements Implementation {
    private final int fieldCount;
    private final TypeName[] typeNames;
    private final String[] descriptors;

    FromArrayConstructorImplementation(
        int fieldCount, TypeName[] typeNames, String[] descriptors) {
      this.fieldCount = fieldCount;
      this.typeNames = typeNames;
      this.descriptors = descriptors;
    }

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      return instrumentedType;
    }

    @Override
    public ByteCodeAppender appender(Target implementationTarget) {
      String owner = implementationTarget.getInstrumentedType().getInternalName();
      return (mv, context, method) -> {
        // super(schema)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL, INTERNAL_ROW_NAME, "<init>", "(" + SCHEMA_DESC + ")V", false);

        int maskLocal = 3;
        int valLocal = 5;
        mv.visitInsn(Opcodes.LCONST_0);
        mv.visitVarInsn(Opcodes.LSTORE, maskLocal);

        for (int i = 0; i < fieldCount; i++) {
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          pushInt(mv, i);
          mv.visitInsn(Opcodes.AALOAD);
          mv.visitVarInsn(Opcodes.ASTORE, valLocal);
          mv.visitVarInsn(Opcodes.ALOAD, valLocal);

          Label nonNullLabel = new Label();
          Label endFieldLabel = new Label();
          mv.visitJumpInsn(Opcodes.IFNONNULL, nonNullLabel);

          mv.visitVarInsn(Opcodes.LLOAD, maskLocal);
          mv.visitLdcInsn(1L << i);
          mv.visitInsn(Opcodes.LOR);
          mv.visitVarInsn(Opcodes.LSTORE, maskLocal);
          mv.visitJumpInsn(Opcodes.GOTO, endFieldLabel);

          mv.visitLabel(nonNullLabel);
          mv.visitVarInsn(Opcodes.ALOAD, 0);
          mv.visitVarInsn(Opcodes.ALOAD, valLocal);
          unboxOrCast(mv, typeNames[i]);
          mv.visitFieldInsn(Opcodes.PUTFIELD, owner, fieldName(i), descriptors[i]);

          mv.visitLabel(endFieldLabel);
        }

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.LLOAD, maskLocal);
        mv.visitFieldInsn(Opcodes.PUTFIELD, owner, "nullMask", "J");
        mv.visitInsn(Opcodes.RETURN);
        return new ByteCodeAppender.Size(6, 6);
      };
    }
  }

  private static final class GetFieldCountImplementation implements Implementation {
    private final int fieldCount;

    GetFieldCountImplementation(int fieldCount) {
      this.fieldCount = fieldCount;
    }

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      return instrumentedType;
    }

    @Override
    public ByteCodeAppender appender(Target implementationTarget) {
      return (mv, context, method) -> {
        pushInt(mv, fieldCount);
        mv.visitInsn(Opcodes.IRETURN);
        return new ByteCodeAppender.Size(1, 1);
      };
    }
  }

  private static final class GetValuesImplementation implements Implementation {
    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      return instrumentedType;
    }

    @Override
    public ByteCodeAppender appender(Target implementationTarget) {
      return (mv, context, method) -> {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            INTERNAL_GENERATOR_NAME,
            "createValuesView",
            "(L" + INTERNAL_ROW_NAME + ";)" + LIST_DESC,
            false);
        mv.visitInsn(Opcodes.ARETURN);
        return new ByteCodeAppender.Size(1, 1);
      };
    }
  }

  private static final class WriteReplaceImplementation implements Implementation {
    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      return instrumentedType;
    }

    @Override
    public ByteCodeAppender appender(Target implementationTarget) {
      return (mv, context, method) -> {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            INTERNAL_GENERATOR_NAME,
            "toSerializedRow",
            "(L" + INTERNAL_ROW_NAME + ";)" + OBJECT_DESC,
            false);
        mv.visitInsn(Opcodes.ARETURN);
        return new ByteCodeAppender.Size(1, 1);
      };
    }
  }

  private static final TypeName[] PRIMITIVE_TYPES = {
    TypeName.BYTE,
    TypeName.INT16,
    TypeName.INT32,
    TypeName.INT64,
    TypeName.FLOAT,
    TypeName.DOUBLE,
    TypeName.BOOLEAN
  };

  private static final class IsNullImplementation implements Implementation {
    private final int fieldCount;

    IsNullImplementation(int fieldCount) {
      this.fieldCount = fieldCount;
    }

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      return instrumentedType;
    }

    @Override
    public ByteCodeAppender appender(Target implementationTarget) {
      String owner = implementationTarget.getInstrumentedType().getInternalName();
      return (mv, context, method) -> {
        Label invalidIdx = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitJumpInsn(Opcodes.IFLT, invalidIdx);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        pushInt(mv, fieldCount);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, invalidIdx);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "nullMask", "J");
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitInsn(Opcodes.LUSHR);
        mv.visitInsn(Opcodes.L2I);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IAND);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitLabel(invalidIdx);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            INTERNAL_GENERATOR_NAME,
            "invalidFieldIndexException",
            "(I)Ljava/lang/IllegalArgumentException;",
            false);
        mv.visitInsn(Opcodes.ATHROW);
        return new ByteCodeAppender.Size(3, 2);
      };
    }
  }

  private static void emitIndexDispatch(
      MethodVisitor mv, int[] matchingIndices, Label[] caseLabels, Label fallbackLabel) {
    int count = matchingIndices.length;
    if (count == 1) {
      mv.visitVarInsn(Opcodes.ILOAD, 1);
      int idx0 = matchingIndices[0];
      if (idx0 == 0) {
        mv.visitJumpInsn(Opcodes.IFNE, fallbackLabel);
      } else {
        pushInt(mv, idx0);
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, fallbackLabel);
      }
    } else if (count <= 3) {
      for (int k = 0; k < count; k++) {
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        int idx = matchingIndices[k];
        if (idx == 0) {
          mv.visitJumpInsn(Opcodes.IFEQ, caseLabels[k]);
        } else {
          pushInt(mv, idx);
          mv.visitJumpInsn(Opcodes.IF_ICMPEQ, caseLabels[k]);
        }
      }
      mv.visitJumpInsn(Opcodes.GOTO, fallbackLabel);
    } else {
      int minIdx = matchingIndices[0];
      int maxIdx = matchingIndices[count - 1];
      mv.visitVarInsn(Opcodes.ILOAD, 1);
      if (maxIdx - minIdx + 1 <= 2 * count) {
        Label[] table = new Label[maxIdx - minIdx + 1];
        java.util.Arrays.fill(table, fallbackLabel);
        for (int k = 0; k < count; k++) {
          table[matchingIndices[k] - minIdx] = caseLabels[k];
        }
        mv.visitTableSwitchInsn(minIdx, maxIdx, fallbackLabel, table);
      } else {
        mv.visitLookupSwitchInsn(fallbackLabel, matchingIndices, caseLabels);
      }
    }
  }

  public static RuntimeException invalidPrimitiveAccessException(
      Row row, int fieldIdx, Class<?> boxedClass) {
    Object val = row.getValue(fieldIdx);
    if (val == null) {
      return new NullPointerException("Null value at field index " + fieldIdx);
    }
    return new ClassCastException(
        "Cannot cast "
            + val.getClass().getName()
            + " to "
            + boxedClass.getName()
            + " at field index "
            + fieldIdx);
  }

  private static final class GetPrimitiveImplementation implements Implementation {
    private final TypeName targetType;
    private final int[] matchingIndices;

    GetPrimitiveImplementation(TypeName targetType, int[] matchingIndices) {
      this.targetType = targetType;
      this.matchingIndices = matchingIndices;
    }

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      return instrumentedType;
    }

    @Override
    public ByteCodeAppender appender(Target implementationTarget) {
      String owner = implementationTarget.getInstrumentedType().getInternalName();
      String desc = Type.getDescriptor(fieldJvmType(targetType));
      String boxedDesc = boxedDescriptor(targetType);
      int returnOpcode = primitiveReturnOpcode(targetType);
      return (mv, context, method) -> {
        int count = matchingIndices.length;
        Label fallbackLabel = new Label();
        Label[] caseLabels = new Label[count];
        for (int k = 0; k < count; k++) {
          caseLabels[k] = new Label();
        }

        emitIndexDispatch(mv, matchingIndices, caseLabels, fallbackLabel);

        for (int k = 0; k < count; k++) {
          int fieldIdx = matchingIndices[k];
          mv.visitLabel(caseLabels[k]);
          mv.visitVarInsn(Opcodes.ALOAD, 0);
          mv.visitFieldInsn(Opcodes.GETFIELD, owner, "nullMask", "J");
          mv.visitLdcInsn(1L << fieldIdx);
          mv.visitInsn(Opcodes.LAND);
          mv.visitInsn(Opcodes.LCONST_0);
          mv.visitInsn(Opcodes.LCMP);
          mv.visitJumpInsn(Opcodes.IFNE, fallbackLabel);
          mv.visitVarInsn(Opcodes.ALOAD, 0);
          mv.visitFieldInsn(Opcodes.GETFIELD, owner, fieldName(fieldIdx), desc);
          mv.visitInsn(returnOpcode);
        }

        mv.visitLabel(fallbackLabel);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitLdcInsn(Type.getType(boxedDesc));
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            INTERNAL_GENERATOR_NAME,
            "invalidPrimitiveAccessException",
            "(L" + INTERNAL_ROW_NAME + ";ILjava/lang/Class;)Ljava/lang/RuntimeException;",
            false);
        mv.visitInsn(Opcodes.ATHROW);
        return new ByteCodeAppender.Size(4, 2);
      };
    }
  }

  private static final class GetBoxedPrimitiveImplementation implements Implementation {
    private final TypeName targetType;
    private final int[] matchingIndices;

    GetBoxedPrimitiveImplementation(TypeName targetType, int[] matchingIndices) {
      this.targetType = targetType;
      this.matchingIndices = matchingIndices;
    }

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      return instrumentedType;
    }

    @Override
    public ByteCodeAppender appender(Target implementationTarget) {
      String owner = implementationTarget.getInstrumentedType().getInternalName();
      String methodName = boxedGetterName(targetType);
      String primDesc = Type.getDescriptor(fieldJvmType(targetType));
      String boxedDesc = boxedDescriptor(targetType);
      return (mv, context, method) -> {
        int count = matchingIndices.length;
        Label returnNullLabel = new Label();
        Label fallbackLabel = new Label();
        Label[] caseLabels = new Label[count];
        for (int k = 0; k < count; k++) {
          caseLabels[k] = new Label();
        }

        emitIndexDispatch(mv, matchingIndices, caseLabels, fallbackLabel);

        for (int k = 0; k < count; k++) {
          int fieldIdx = matchingIndices[k];
          mv.visitLabel(caseLabels[k]);
          mv.visitVarInsn(Opcodes.ALOAD, 0);
          mv.visitFieldInsn(Opcodes.GETFIELD, owner, "nullMask", "J");
          mv.visitLdcInsn(1L << fieldIdx);
          mv.visitInsn(Opcodes.LAND);
          mv.visitInsn(Opcodes.LCONST_0);
          mv.visitInsn(Opcodes.LCMP);
          mv.visitJumpInsn(Opcodes.IFNE, returnNullLabel);
          mv.visitVarInsn(Opcodes.ALOAD, 0);
          mv.visitFieldInsn(Opcodes.GETFIELD, owner, fieldName(fieldIdx), primDesc);
          boxPrimitive(mv, targetType);
          mv.visitInsn(Opcodes.ARETURN);
        }

        mv.visitLabel(returnNullLabel);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(fallbackLabel);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL, INTERNAL_ROW_NAME, methodName, "(I)" + boxedDesc, false);
        mv.visitInsn(Opcodes.ARETURN);
        return new ByteCodeAppender.Size(4, 2);
      };
    }
  }

  private static final class GetValueImplementation implements Implementation {
    private final int fieldCount;
    private final TypeName[] typeNames;
    private final String[] descriptors;

    GetValueImplementation(int fieldCount, TypeName[] typeNames, String[] descriptors) {
      this.fieldCount = fieldCount;
      this.typeNames = typeNames;
      this.descriptors = descriptors;
    }

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      return instrumentedType;
    }

    @Override
    public ByteCodeAppender appender(Target implementationTarget) {
      String owner = implementationTarget.getInstrumentedType().getInternalName();
      return (mv, context, method) -> {
        Label defaultLabel = new Label();
        Label[] caseLabels = new Label[fieldCount];
        for (int i = 0; i < fieldCount; i++) {
          caseLabels[i] = new Label();
        }

        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitTableSwitchInsn(0, fieldCount - 1, defaultLabel, caseLabels);

        for (int i = 0; i < fieldCount; i++) {
          mv.visitLabel(caseLabels[i]);
          if (isPrimitiveField(typeNames[i])) {
            Label notNull = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, owner, "nullMask", "J");
            mv.visitLdcInsn(1L << i);
            mv.visitInsn(Opcodes.LAND);
            mv.visitInsn(Opcodes.LCONST_0);
            mv.visitInsn(Opcodes.LCMP);
            mv.visitJumpInsn(Opcodes.IFEQ, notNull);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitLabel(notNull);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, owner, fieldName(i), descriptors[i]);
            boxPrimitive(mv, typeNames[i]);
            mv.visitInsn(Opcodes.ARETURN);
          } else {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, owner, fieldName(i), descriptors[i]);
            mv.visitInsn(Opcodes.ARETURN);
          }
        }

        mv.visitLabel(defaultLabel);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            INTERNAL_GENERATOR_NAME,
            "invalidFieldIndexException",
            "(I)Ljava/lang/IllegalArgumentException;",
            false);
        mv.visitInsn(Opcodes.ATHROW);
        return new ByteCodeAppender.Size(4, 2);
      };
    }
  }

  private static final class EqualsImplementation implements Implementation {
    private final int fieldCount;
    private final TypeName[] typeNames;
    private final String[] descriptors;

    EqualsImplementation(int fieldCount, TypeName[] typeNames, String[] descriptors) {
      this.fieldCount = fieldCount;
      this.typeNames = typeNames;
      this.descriptors = descriptors;
    }

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      return instrumentedType;
    }

    @Override
    public ByteCodeAppender appender(Target implementationTarget) {
      String owner = implementationTarget.getInstrumentedType().getInternalName();
      return (mv, context, method) -> {
        // if (this == o) return true;
        Label notSame = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitJumpInsn(Opcodes.IF_ACMPNE, notSame);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(notSame);

        // if (!(o instanceof Owner)) return super.equals(o);
        Label isSameClass = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, owner);
        mv.visitJumpInsn(Opcodes.IFNE, isSameClass);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL, INTERNAL_ROW_NAME, "equals", "(Ljava/lang/Object;)Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(isSameClass);

        // Owner other = (Owner) o; (local 2)
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, owner);
        mv.visitVarInsn(Opcodes.ASTORE, 2);

        Label returnFalse = new Label();

        // if (this.nullMask != other.nullMask) return false;
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "nullMask", "J");
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "nullMask", "J");
        mv.visitInsn(Opcodes.LCMP);
        mv.visitJumpInsn(Opcodes.IFNE, returnFalse);

        // if (!Objects.equals(this.getSchema(), other.getSchema())) return false;
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL, owner, "getSchema", "()" + SCHEMA_DESC, false);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL, owner, "getSchema", "()" + SCHEMA_DESC, false);
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "java/util/Objects",
            "equals",
            "(Ljava/lang/Object;Ljava/lang/Object;)Z",
            false);
        mv.visitJumpInsn(Opcodes.IFEQ, returnFalse);

        // Compare each field directly (since nullMask matched, any null primitive field is 0 in both)
        for (int i = 0; i < fieldCount; i++) {
          String fName = fieldName(i);
          String fDesc = descriptors[i];
          switch (typeNames[i]) {
            case BYTE:
            case INT16:
            case INT32:
            case BOOLEAN:
              mv.visitVarInsn(Opcodes.ALOAD, 0);
              mv.visitFieldInsn(Opcodes.GETFIELD, owner, fName, fDesc);
              mv.visitVarInsn(Opcodes.ALOAD, 2);
              mv.visitFieldInsn(Opcodes.GETFIELD, owner, fName, fDesc);
              mv.visitJumpInsn(Opcodes.IF_ICMPNE, returnFalse);
              break;
            case INT64:
              mv.visitVarInsn(Opcodes.ALOAD, 0);
              mv.visitFieldInsn(Opcodes.GETFIELD, owner, fName, fDesc);
              mv.visitVarInsn(Opcodes.ALOAD, 2);
              mv.visitFieldInsn(Opcodes.GETFIELD, owner, fName, fDesc);
              mv.visitInsn(Opcodes.LCMP);
              mv.visitJumpInsn(Opcodes.IFNE, returnFalse);
              break;
            case FLOAT:
              mv.visitVarInsn(Opcodes.ALOAD, 0);
              mv.visitFieldInsn(Opcodes.GETFIELD, owner, fName, fDesc);
              mv.visitMethodInsn(
                  Opcodes.INVOKESTATIC, "java/lang/Float", "floatToIntBits", "(F)I", false);
              mv.visitVarInsn(Opcodes.ALOAD, 2);
              mv.visitFieldInsn(Opcodes.GETFIELD, owner, fName, fDesc);
              mv.visitMethodInsn(
                  Opcodes.INVOKESTATIC, "java/lang/Float", "floatToIntBits", "(F)I", false);
              mv.visitJumpInsn(Opcodes.IF_ICMPNE, returnFalse);
              break;
            case DOUBLE:
              mv.visitVarInsn(Opcodes.ALOAD, 0);
              mv.visitFieldInsn(Opcodes.GETFIELD, owner, fName, fDesc);
              mv.visitMethodInsn(
                  Opcodes.INVOKESTATIC, "java/lang/Double", "doubleToLongBits", "(D)J", false);
              mv.visitVarInsn(Opcodes.ALOAD, 2);
              mv.visitFieldInsn(Opcodes.GETFIELD, owner, fName, fDesc);
              mv.visitMethodInsn(
                  Opcodes.INVOKESTATIC, "java/lang/Double", "doubleToLongBits", "(D)J", false);
              mv.visitInsn(Opcodes.LCMP);
              mv.visitJumpInsn(Opcodes.IFNE, returnFalse);
              break;
            case BYTES:
              mv.visitVarInsn(Opcodes.ALOAD, 0);
              mv.visitFieldInsn(Opcodes.GETFIELD, owner, fName, fDesc);
              mv.visitVarInsn(Opcodes.ALOAD, 2);
              mv.visitFieldInsn(Opcodes.GETFIELD, owner, fName, fDesc);
              mv.visitMethodInsn(
                  Opcodes.INVOKESTATIC, "java/util/Arrays", "equals", "([B[B)Z", false);
              mv.visitJumpInsn(Opcodes.IFEQ, returnFalse);
              break;
            case STRING:
            case DECIMAL:
            case DATETIME:
            case ROW:
              mv.visitVarInsn(Opcodes.ALOAD, 0);
              mv.visitFieldInsn(Opcodes.GETFIELD, owner, fName, fDesc);
              mv.visitVarInsn(Opcodes.ALOAD, 2);
              mv.visitFieldInsn(Opcodes.GETFIELD, owner, fName, fDesc);
              mv.visitMethodInsn(
                  Opcodes.INVOKESTATIC,
                  "java/util/Objects",
                  "equals",
                  "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                  false);
              mv.visitJumpInsn(Opcodes.IFEQ, returnFalse);
              break;
            default:
              // ARRAY, ITERABLE, MAP, LOGICAL_TYPE
              mv.visitVarInsn(Opcodes.ALOAD, 0);
              pushInt(mv, i);
              mv.visitVarInsn(Opcodes.ALOAD, 0);
              mv.visitFieldInsn(Opcodes.GETFIELD, owner, fName, fDesc);
              mv.visitVarInsn(Opcodes.ALOAD, 2);
              mv.visitFieldInsn(Opcodes.GETFIELD, owner, fName, fDesc);
              mv.visitMethodInsn(
                  Opcodes.INVOKESTATIC,
                  INTERNAL_GENERATOR_NAME,
                  "deepEqualsField",
                  "(L" + INTERNAL_ROW_NAME + ";ILjava/lang/Object;Ljava/lang/Object;)Z",
                  false);
              mv.visitJumpInsn(Opcodes.IFEQ, returnFalse);
              break;
          }
        }

        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitLabel(returnFalse);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        return new ByteCodeAppender.Size(6, 3);
      };
    }
  }

  private static final class HashCodeImplementation implements Implementation {
    private final int fieldCount;
    private final TypeName[] typeNames;
    private final String[] descriptors;

    HashCodeImplementation(int fieldCount, TypeName[] typeNames, String[] descriptors) {
      this.fieldCount = fieldCount;
      this.typeNames = typeNames;
      this.descriptors = descriptors;
    }

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      return instrumentedType;
    }

    @Override
    public ByteCodeAppender appender(Target implementationTarget) {
      String owner = implementationTarget.getInstrumentedType().getInternalName();
      return (mv, context, method) -> {
        // int h = 1; (kept on operand stack across fields)
        mv.visitInsn(Opcodes.ICONST_1);

        for (int i = 0; i < fieldCount; i++) {
          String fName = fieldName(i);
          String fDesc = descriptors[i];
          mv.visitIntInsn(Opcodes.BIPUSH, 31);
          mv.visitInsn(Opcodes.IMUL);

          switch (typeNames[i]) {
            case BYTE:
            case INT16:
            case INT32:
              mv.visitVarInsn(Opcodes.ALOAD, 0);
              mv.visitFieldInsn(Opcodes.GETFIELD, owner, fName, fDesc);
              break;
            case INT64:
              mv.visitVarInsn(Opcodes.ALOAD, 0);
              mv.visitFieldInsn(Opcodes.GETFIELD, owner, fName, fDesc);
              mv.visitMethodInsn(
                  Opcodes.INVOKESTATIC, "java/lang/Long", "hashCode", "(J)I", false);
              break;
            case FLOAT:
              mv.visitVarInsn(Opcodes.ALOAD, 0);
              mv.visitFieldInsn(Opcodes.GETFIELD, owner, fName, fDesc);
              mv.visitMethodInsn(
                  Opcodes.INVOKESTATIC, "java/lang/Float", "hashCode", "(F)I", false);
              break;
            case DOUBLE:
              mv.visitVarInsn(Opcodes.ALOAD, 0);
              mv.visitFieldInsn(Opcodes.GETFIELD, owner, fName, fDesc);
              mv.visitMethodInsn(
                  Opcodes.INVOKESTATIC, "java/lang/Double", "hashCode", "(D)I", false);
              break;
            case BOOLEAN:
              {
                Label notNull = new Label();
                Label doneBool = new Label();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, owner, "nullMask", "J");
                mv.visitLdcInsn(1L << i);
                mv.visitInsn(Opcodes.LAND);
                mv.visitInsn(Opcodes.LCONST_0);
                mv.visitInsn(Opcodes.LCMP);
                mv.visitJumpInsn(Opcodes.IFEQ, notNull);
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitJumpInsn(Opcodes.GOTO, doneBool);
                mv.visitLabel(notNull);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, owner, fName, fDesc);
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC, "java/lang/Boolean", "hashCode", "(Z)I", false);
                mv.visitLabel(doneBool);
                break;
              }
            case BYTES:
              mv.visitVarInsn(Opcodes.ALOAD, 0);
              mv.visitFieldInsn(Opcodes.GETFIELD, owner, fName, fDesc);
              mv.visitMethodInsn(
                  Opcodes.INVOKESTATIC, "java/util/Arrays", "hashCode", "([B)I", false);
              break;
            case STRING:
            case DECIMAL:
            case DATETIME:
            case ROW:
              mv.visitVarInsn(Opcodes.ALOAD, 0);
              mv.visitFieldInsn(Opcodes.GETFIELD, owner, fName, fDesc);
              mv.visitMethodInsn(
                  Opcodes.INVOKESTATIC,
                  "java/util/Objects",
                  "hashCode",
                  "(Ljava/lang/Object;)I",
                  false);
              break;
            default:
              mv.visitVarInsn(Opcodes.ALOAD, 0);
              pushInt(mv, i);
              mv.visitVarInsn(Opcodes.ALOAD, 0);
              mv.visitFieldInsn(Opcodes.GETFIELD, owner, fName, fDesc);
              mv.visitMethodInsn(
                  Opcodes.INVOKESTATIC,
                  INTERNAL_GENERATOR_NAME,
                  "deepHashCodeField",
                  "(L" + INTERNAL_ROW_NAME + ";ILjava/lang/Object;)I",
                  false);
              break;
          }
          mv.visitInsn(Opcodes.IADD);
        }

        mv.visitInsn(Opcodes.IRETURN);
        return new ByteCodeAppender.Size(6, 1);
      };
    }
  }

  private static final class FactoryCreateImplementation implements Implementation {
    private final String rowInternalName;

    FactoryCreateImplementation(String rowInternalName) {
      this.rowInternalName = rowInternalName;
    }

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      return instrumentedType;
    }

    @Override
    public ByteCodeAppender appender(Target implementationTarget) {
      return (mv, context, method) -> {
        mv.visitTypeInsn(Opcodes.NEW, rowInternalName);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL, rowInternalName, "<init>", "(" + SCHEMA_DESC + ")V", false);
        mv.visitInsn(Opcodes.ARETURN);
        return new ByteCodeAppender.Size(3, 2);
      };
    }
  }

  private static final class FactoryFromListImplementation implements Implementation {
    private final String rowInternalName;

    FactoryFromListImplementation(String rowInternalName) {
      this.rowInternalName = rowInternalName;
    }

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      return instrumentedType;
    }

    @Override
    public ByteCodeAppender appender(Target implementationTarget) {
      return (mv, context, method) -> {
        mv.visitTypeInsn(Opcodes.NEW, rowInternalName);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            rowInternalName,
            "<init>",
            "(" + SCHEMA_DESC + LIST_DESC + ")V",
            false);
        mv.visitInsn(Opcodes.ARETURN);
        return new ByteCodeAppender.Size(4, 3);
      };
    }
  }

  private static final class FactoryCaptureFromListImplementation implements Implementation {
    private final String rowInternalName;

    FactoryCaptureFromListImplementation(String rowInternalName) {
      this.rowInternalName = rowInternalName;
    }

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      return instrumentedType;
    }

    @Override
    public ByteCodeAppender appender(Target implementationTarget) {
      return (mv, context, method) -> {
        mv.visitTypeInsn(Opcodes.NEW, rowInternalName);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            rowInternalName,
            "<init>",
            "(" + SCHEMA_DESC + LIST_DESC + "Z)V",
            false);
        mv.visitInsn(Opcodes.ARETURN);
        return new ByteCodeAppender.Size(5, 3);
      };
    }
  }

  private static final class FactoryFromArrayImplementation implements Implementation {
    private final String rowInternalName;

    FactoryFromArrayImplementation(String rowInternalName) {
      this.rowInternalName = rowInternalName;
    }

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      return instrumentedType;
    }

    @Override
    public ByteCodeAppender appender(Target implementationTarget) {
      return (mv, context, method) -> {
        mv.visitTypeInsn(Opcodes.NEW, rowInternalName);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            rowInternalName,
            "<init>",
            "(" + SCHEMA_DESC + OBJECT_ARRAY_DESC + ")V",
            false);
        mv.visitInsn(Opcodes.ARETURN);
        return new ByteCodeAppender.Size(4, 3);
      };
    }
  }

  private static final class FactoryNullRowImplementation implements Implementation {
    private final String rowInternalName;
    private final long allNullMask;

    FactoryNullRowImplementation(String rowInternalName, long allNullMask) {
      this.rowInternalName = rowInternalName;
      this.allNullMask = allNullMask;
    }

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      return instrumentedType;
    }

    @Override
    public ByteCodeAppender appender(Target implementationTarget) {
      return (mv, context, method) -> {
        mv.visitTypeInsn(Opcodes.NEW, rowInternalName);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL, rowInternalName, "<init>", "(" + SCHEMA_DESC + ")V", false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(allNullMask);
        mv.visitFieldInsn(Opcodes.PUTFIELD, rowInternalName, "nullMask", "J");
        mv.visitInsn(Opcodes.ARETURN);
        return new ByteCodeAppender.Size(4, 2);
      };
    }
  }

  static boolean isPrimitiveField(TypeName typeName) {
    switch (typeName) {
      case BYTE:
      case INT16:
      case INT32:
      case INT64:
      case FLOAT:
      case DOUBLE:
      case BOOLEAN:
        return true;
      default:
        return false;
    }
  }

  static void pushInt(MethodVisitor mv, int value) {
    if (value >= -1 && value <= 5) {
      mv.visitInsn(Opcodes.ICONST_0 + value);
    } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
      mv.visitIntInsn(Opcodes.BIPUSH, value);
    } else {
      mv.visitIntInsn(Opcodes.SIPUSH, value);
    }
  }

  static void unboxOrCast(MethodVisitor mv, TypeName typeName) {
    switch (typeName) {
      case BYTE:
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Byte");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false);
        break;
      case INT16:
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Short");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false);
        break;
      case INT32:
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Integer");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
        break;
      case INT64:
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Long");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false);
        break;
      case FLOAT:
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Float");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false);
        break;
      case DOUBLE:
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Double");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false);
        break;
      case BOOLEAN:
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
        break;
      case STRING:
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
        break;
      case BYTES:
        mv.visitTypeInsn(Opcodes.CHECKCAST, "[B");
        break;
      case DECIMAL:
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/math/BigDecimal");
        break;
      case DATETIME:
        mv.visitTypeInsn(Opcodes.CHECKCAST, "org/joda/time/ReadableInstant");
        break;
      case ROW:
        mv.visitTypeInsn(Opcodes.CHECKCAST, INTERNAL_ROW_NAME);
        break;
      case ARRAY:
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Collection");
        break;
      case ITERABLE:
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Iterable");
        break;
      case MAP:
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Map");
        break;
      case LOGICAL_TYPE:
      default:
        break;
    }
  }

  static void boxPrimitive(MethodVisitor mv, TypeName typeName) {
    switch (typeName) {
      case BYTE:
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
        break;
      case INT16:
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
        break;
      case INT32:
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        break;
      case INT64:
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
        break;
      case FLOAT:
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
        break;
      case DOUBLE:
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
        break;
      case BOOLEAN:
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
        break;
      default:
        throw new IllegalArgumentException("Not a primitive type: " + typeName);
    }
  }

  static String primitiveGetterName(TypeName typeName) {
    switch (typeName) {
      case BYTE:
        return "getPrimitiveByte";
      case INT16:
        return "getPrimitiveInt16";
      case INT32:
        return "getPrimitiveInt32";
      case INT64:
        return "getPrimitiveInt64";
      case FLOAT:
        return "getPrimitiveFloat";
      case DOUBLE:
        return "getPrimitiveDouble";
      case BOOLEAN:
        return "getPrimitiveBoolean";
      default:
        throw new IllegalArgumentException("Not a primitive type: " + typeName);
    }
  }

  static String boxedGetterName(TypeName typeName) {
    switch (typeName) {
      case BYTE:
        return "getByte";
      case INT16:
        return "getInt16";
      case INT32:
        return "getInt32";
      case INT64:
        return "getInt64";
      case FLOAT:
        return "getFloat";
      case DOUBLE:
        return "getDouble";
      case BOOLEAN:
        return "getBoolean";
      default:
        throw new IllegalArgumentException("Not a primitive type: " + typeName);
    }
  }

  static String boxedDescriptor(TypeName typeName) {
    switch (typeName) {
      case BYTE:
        return "Ljava/lang/Byte;";
      case INT16:
        return "Ljava/lang/Short;";
      case INT32:
        return "Ljava/lang/Integer;";
      case INT64:
        return "Ljava/lang/Long;";
      case FLOAT:
        return "Ljava/lang/Float;";
      case DOUBLE:
        return "Ljava/lang/Double;";
      case BOOLEAN:
        return "Ljava/lang/Boolean;";
      default:
        throw new IllegalArgumentException("Not a primitive type: " + typeName);
    }
  }

  static int primitiveReturnOpcode(TypeName typeName) {
    switch (typeName) {
      case BYTE:
      case INT16:
      case INT32:
      case BOOLEAN:
        return Opcodes.IRETURN;
      case INT64:
        return Opcodes.LRETURN;
      case FLOAT:
        return Opcodes.FRETURN;
      case DOUBLE:
        return Opcodes.DRETURN;
      default:
        throw new IllegalArgumentException("Not a primitive type: " + typeName);
    }
  }
}
