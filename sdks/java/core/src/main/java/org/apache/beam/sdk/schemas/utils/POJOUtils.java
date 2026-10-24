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
package org.apache.beam.sdk.schemas.utils;

import static org.apache.beam.sdk.util.ByteBuddyUtils.getClassLoadingStrategy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription.ForLoadedField;
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
import net.bytebuddy.implementation.bytecode.TypeCreation;
import net.bytebuddy.implementation.bytecode.assign.TypeCasting;
import net.bytebuddy.implementation.bytecode.member.FieldAccess;
import net.bytebuddy.implementation.bytecode.member.MethodInvocation;
import net.bytebuddy.implementation.bytecode.member.MethodReturn;
import net.bytebuddy.implementation.bytecode.member.MethodVariableAccess;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.matcher.ElementMatchers;
import org.apache.beam.sdk.schemas.FieldValueGetter;
import org.apache.beam.sdk.schemas.FieldValueSetter;
import org.apache.beam.sdk.schemas.FieldValueTypeInformation;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.SchemaUserTypeCreator;
import org.apache.beam.sdk.schemas.utils.ByteBuddyUtils.ConstructorCreateInstruction;
import org.apache.beam.sdk.schemas.utils.ByteBuddyUtils.InjectPackageStrategy;
import org.apache.beam.sdk.schemas.utils.ByteBuddyUtils.StaticFactoryMethodInstruction;
import org.apache.beam.sdk.schemas.utils.ByteBuddyUtils.TypeConversionsFactory;
import org.apache.beam.sdk.schemas.utils.ReflectUtils.TypeDescriptorWithSchema;
import org.apache.beam.sdk.util.common.ReflectHelpers;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.Maps;
import org.checkerframework.checker.nullness.qual.NonNull;

/** A set of utilities to generate getter and setter classes for POJOs. */
@SuppressWarnings({
  "nullness", // TODO(https://github.com/apache/beam/issues/20497)
  "rawtypes" // TODO(https://github.com/apache/beam/issues/20447)
})
public class POJOUtils {

  public static Schema schemaFromPojoClass(
      TypeDescriptor<?> typeDescriptor, FieldValueTypeSupplier fieldValueTypeSupplier) {
    return StaticSchemaInference.schemaFromClass(typeDescriptor, fieldValueTypeSupplier);
  }

  // Static ByteBuddy instance used by all helpers.
  private static final ByteBuddy BYTE_BUDDY = new ByteBuddy();

  private static final Map<TypeDescriptorWithSchema<?>, List<FieldValueTypeInformation>>
      CACHED_FIELD_TYPES = Maps.newConcurrentMap();

  public static List<FieldValueTypeInformation> getFieldTypes(
      TypeDescriptor<?> typeDescriptor,
      Schema schema,
      FieldValueTypeSupplier fieldValueTypeSupplier) {
    return CACHED_FIELD_TYPES.computeIfAbsent(
        TypeDescriptorWithSchema.create(typeDescriptor, schema),
        c -> fieldValueTypeSupplier.get(typeDescriptor, schema));
  }

  // The list of getters for a class is cached, so we only create the classes the first time
  // getSetters is called.
  private static final Map<TypeDescriptorWithSchema<?>, List<FieldValueGetter<?, ?>>>
      CACHED_GETTERS = Maps.newConcurrentMap();

  public static <T> List<FieldValueGetter<@NonNull T, Object>> getGetters(
      TypeDescriptor<T> typeDescriptor,
      Schema schema,
      FieldValueTypeSupplier fieldValueTypeSupplier,
      TypeConversionsFactory typeConversionsFactory) {
    // Return the getters ordered by their position in the schema.
    return (List)
        CACHED_GETTERS.computeIfAbsent(
            TypeDescriptorWithSchema.create(typeDescriptor, schema),
            c -> {
              List<FieldValueTypeInformation> types =
                  fieldValueTypeSupplier.get(typeDescriptor, schema);
              List<FieldValueGetter<@NonNull T, Object>> getters =
                  types.stream()
                      .<FieldValueGetter<@NonNull T, Object>>map(
                          t -> POJOUtils.createGetter(t, typeConversionsFactory))
                      .collect(Collectors.toList());
              if (getters.size() != schema.getFieldCount()) {
                throw new RuntimeException(
                    "Was not able to generate getters for schema: "
                        + schema
                        + " class: "
                        + typeDescriptor);
              }
              return (List) getters;
            });
  }

  // The list of constructors for a class is cached, so we only create the classes the first time
  // getConstructor is called.
  public static final Map<TypeDescriptorWithSchema<?>, SchemaUserTypeCreator> CACHED_CREATORS =
      Maps.newConcurrentMap();

  public static <T> SchemaUserTypeCreator getSetFieldCreator(
      TypeDescriptor<T> typeDescriptor,
      Schema schema,
      FieldValueTypeSupplier fieldValueTypeSupplier,
      TypeConversionsFactory typeConversionsFactory) {
    return CACHED_CREATORS.computeIfAbsent(
        TypeDescriptorWithSchema.create(typeDescriptor, schema),
        c -> {
          List<FieldValueTypeInformation> types =
              fieldValueTypeSupplier.get(typeDescriptor, schema);
          return createSetFieldCreator(
              typeDescriptor.getRawType(), schema, types, typeConversionsFactory);
        });
  }

  private static <T> SchemaUserTypeCreator createSetFieldCreator(
      Class<T> clazz,
      Schema schema,
      List<FieldValueTypeInformation> types,
      TypeConversionsFactory typeConversionsFactory) {
    try {
      SetFieldCreateInstruction createInstruction =
          new SetFieldCreateInstruction(types, clazz, typeConversionsFactory);
      DynamicType.Builder<SchemaUserTypeCreator> builder =
          BYTE_BUDDY
              .with(new InjectPackageStrategy(clazz))
              .subclass(SchemaUserTypeCreator.class)
              .method(ElementMatchers.named("create"))
              .intercept(createInstruction)
              .method(ElementMatchers.named("fromRow"))
              .intercept(createInstruction.fromRowImplementation());

      return builder
          .visit(new AsmVisitorWrapper.ForDeclaredMethods().writerFlags(ClassWriter.COMPUTE_FRAMES))
          .make()
          .load(
              ReflectHelpers.findClassLoader(clazz.getClassLoader()),
              getClassLoadingStrategy(clazz))
          .getLoaded()
          .getDeclaredConstructor()
          .newInstance();
    } catch (InstantiationException
        | IllegalAccessException
        | IllegalStateException
        | NoSuchMethodException
        | InvocationTargetException e) {
      throw new RuntimeException(
          String.format(
              "Unable to generate a creator for POJO '%s' with inferred schema: %s%nNote POJOs must"
                  + " have a zero-argument constructor, or a constructor annotated with"
                  + " @SchemaCreate.",
              clazz, schema));
    }
  }

  public static <T> SchemaUserTypeCreator getConstructorCreator(
      TypeDescriptor<T> typeDescriptor,
      Constructor<T> constructor,
      Schema schema,
      FieldValueTypeSupplier fieldValueTypeSupplier,
      TypeConversionsFactory typeConversionsFactory) {
    return CACHED_CREATORS.computeIfAbsent(
        TypeDescriptorWithSchema.create(typeDescriptor, schema),
        c -> {
          List<FieldValueTypeInformation> types =
              fieldValueTypeSupplier.get(typeDescriptor, schema);
          return POJOUtils.createConstructorCreator(
              typeDescriptor.getRawType(), constructor, schema, types, typeConversionsFactory);
        });
  }

  public static <T> SchemaUserTypeCreator createConstructorCreator(
      Class<? super T> clazz,
      Constructor<T> constructor,
      Schema schema,
      List<FieldValueTypeInformation> types,
      TypeConversionsFactory typeConversionsFactory) {
    try {
      ConstructorCreateInstruction createInstruction =
          new ConstructorCreateInstruction(types, clazz, constructor, typeConversionsFactory);
      DynamicType.Builder<SchemaUserTypeCreator> builder =
          BYTE_BUDDY
              .with(new InjectPackageStrategy(clazz))
              .subclass(SchemaUserTypeCreator.class)
              .method(ElementMatchers.named("create"))
              .intercept(createInstruction)
              .method(ElementMatchers.named("fromRow"))
              .intercept(createInstruction.fromRowImplementation());

      return builder
          .visit(new AsmVisitorWrapper.ForDeclaredMethods().writerFlags(ClassWriter.COMPUTE_FRAMES))
          .make()
          .load(
              ReflectHelpers.findClassLoader(clazz.getClassLoader()),
              getClassLoadingStrategy(clazz))
          .getLoaded()
          .getDeclaredConstructor()
          .newInstance();
    } catch (InstantiationException
        | IllegalAccessException
        | NoSuchMethodException
        | InvocationTargetException e) {
      throw new RuntimeException(
          "Unable to generate a creator for " + clazz + " with schema " + schema);
    }
  }

  public static SchemaUserTypeCreator getStaticCreator(
      TypeDescriptor<?> typeDescriptor,
      Method creator,
      Schema schema,
      FieldValueTypeSupplier fieldValueTypeSupplier,
      TypeConversionsFactory typeConversionsFactory) {
    return CACHED_CREATORS.computeIfAbsent(
        TypeDescriptorWithSchema.create(typeDescriptor, schema),
        c -> {
          List<FieldValueTypeInformation> types =
              fieldValueTypeSupplier.get(typeDescriptor, schema);
          return createStaticCreator(
              typeDescriptor.getRawType(), creator, schema, types, typeConversionsFactory);
        });
  }

  public static <T> SchemaUserTypeCreator createStaticCreator(
      Class<T> clazz,
      Method creator,
      Schema schema,
      List<FieldValueTypeInformation> types,
      TypeConversionsFactory typeConversionsFactory) {
    try {
      StaticFactoryMethodInstruction createInstruction =
          new StaticFactoryMethodInstruction(types, clazz, creator, typeConversionsFactory);
      DynamicType.Builder<SchemaUserTypeCreator> builder =
          BYTE_BUDDY
              .with(new InjectPackageStrategy(clazz))
              .subclass(SchemaUserTypeCreator.class)
              .method(ElementMatchers.named("create"))
              .intercept(createInstruction)
              .method(ElementMatchers.named("fromRow"))
              .intercept(createInstruction.fromRowImplementation());

      return builder
          .visit(new AsmVisitorWrapper.ForDeclaredMethods().writerFlags(ClassWriter.COMPUTE_FRAMES))
          .make()
          .load(ReflectHelpers.findClassLoader(), getClassLoadingStrategy(clazz))
          .getLoaded()
          .getDeclaredConstructor()
          .newInstance();
    } catch (InstantiationException
        | IllegalAccessException
        | NoSuchMethodException
        | InvocationTargetException e) {
      throw new RuntimeException(
          "Unable to generate a creator for class " + clazz + " with schema " + schema);
    }
  }

  /**
   * Generate the following {@link FieldValueSetter} class for the {@link Field}.
   *
   * <pre><code>
   *   class Getter implements {@literal FieldValueGetter<POJO, FieldType>} {
   *     {@literal @}Override public String name() { return field.getName(); }
   *     {@literal @}Override public Class type() { return field.getType(); }
   *     {@literal @}Override public FieldType get(POJO pojo) {
   *        return convert(pojo.field);
   *      }
   *   }
   * </code></pre>
   */
  static <ObjectT, ValueT> FieldValueGetter<@NonNull ObjectT, ValueT> createGetter(
      FieldValueTypeInformation typeInformation, TypeConversionsFactory typeConversionsFactory) {
    Field field = Preconditions.checkNotNull(typeInformation.getField());
    DynamicType.Builder<FieldValueGetter<@NonNull ObjectT, ValueT>> builder =
        ByteBuddyUtils.subclassGetterInterface(
            BYTE_BUDDY,
            field.getDeclaringClass(),
            typeConversionsFactory.createTypeConversion(false).convert(typeInformation.getType()));
    builder = implementGetterMethods(builder, typeInformation, typeConversionsFactory);
    try {
      return builder
          .visit(new AsmVisitorWrapper.ForDeclaredMethods().writerFlags(ClassWriter.COMPUTE_FRAMES))
          .make()
          .load(
              ReflectHelpers.findClassLoader(field.getDeclaringClass().getClassLoader()),
              getClassLoadingStrategy(field.getDeclaringClass()))
          .getLoaded()
          .getDeclaredConstructor()
          .newInstance();
    } catch (InstantiationException
        | IllegalAccessException
        | NoSuchMethodException
        | InvocationTargetException e) {
      throw new RuntimeException("Unable to generate a getter for field '" + field + "'.", e);
    }
  }

  private static <ObjectT, ValueT>
      DynamicType.Builder<FieldValueGetter<@NonNull ObjectT, ValueT>> implementGetterMethods(
          DynamicType.Builder<FieldValueGetter<@NonNull ObjectT, ValueT>> builder,
          FieldValueTypeInformation typeInformation,
          TypeConversionsFactory typeConversionsFactory) {
    builder =
        builder
            .visit(
                new AsmVisitorWrapper.ForDeclaredMethods().writerFlags(ClassWriter.COMPUTE_FRAMES))
            .method(ElementMatchers.named("name"))
            .intercept(FixedValue.reference(typeInformation.getName()))
            .method(ElementMatchers.named("get"))
            .intercept(new ReadFieldInstruction(typeInformation, typeConversionsFactory));
    Class<?> rawType = typeInformation.getField().getType();
    if (rawType.isPrimitive()) {
      String getterMethodName = ByteBuddyUtils.primitiveGetterMethodName(rawType);
      if (getterMethodName != null) {
        builder =
            builder
                .method(ElementMatchers.named("isNull"))
                .intercept(FixedValue.value(false))
                .method(ElementMatchers.named(getterMethodName))
                .intercept(new ReadPrimitiveFieldInstruction(typeInformation.getField()));
      }
    } else {
      builder =
          builder
              .method(ElementMatchers.named("isNull"))
              .intercept(new IsNullFieldInstruction(typeInformation.getField()));
    }
    return builder;
  }

  static class IsNullFieldInstruction implements Implementation {
    private final Field field;

    IsNullFieldInstruction(Field field) {
      this.field = field;
    }

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
                MethodVariableAccess.REFERENCE.loadFrom(1),
                TypeCasting.to(TypeDescription.ForLoadedType.of(field.getDeclaringClass())),
                FieldAccess.forField(new ForLoadedField(field)).read());
        StackManipulation.Size size = stackManipulation.apply(methodVisitor, implementationContext);
        net.bytebuddy.jar.asm.Label isNullLabel = new net.bytebuddy.jar.asm.Label();
        methodVisitor.visitJumpInsn(net.bytebuddy.jar.asm.Opcodes.IFNULL, isNullLabel);
        methodVisitor.visitInsn(net.bytebuddy.jar.asm.Opcodes.ICONST_0);
        methodVisitor.visitInsn(net.bytebuddy.jar.asm.Opcodes.IRETURN);
        methodVisitor.visitLabel(isNullLabel);
        methodVisitor.visitInsn(net.bytebuddy.jar.asm.Opcodes.ICONST_1);
        methodVisitor.visitInsn(net.bytebuddy.jar.asm.Opcodes.IRETURN);
        return new Size(size.getMaximalSize() + 1, numLocals);
      };
    }
  }

  static class ReadPrimitiveFieldInstruction implements Implementation {
    private final Field field;

    ReadPrimitiveFieldInstruction(Field field) {
      this.field = field;
    }

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
                MethodVariableAccess.REFERENCE.loadFrom(1),
                TypeCasting.to(TypeDescription.ForLoadedType.of(field.getDeclaringClass())),
                FieldAccess.forField(new ForLoadedField(field)).read(),
                MethodReturn.of(TypeDescription.ForLoadedType.of(field.getType())));
        StackManipulation.Size size = stackManipulation.apply(methodVisitor, implementationContext);
        return new Size(size.getMaximalSize(), numLocals);
      };
    }
  }

  // Implements a method to read a public field out of an object.
  static class ReadFieldInstruction implements Implementation {
    // Field that will be read.
    private final FieldValueTypeInformation typeInformation;
    private final TypeConversionsFactory typeConversionsFactory;

    ReadFieldInstruction(
        FieldValueTypeInformation typeInformation, TypeConversionsFactory typeConversionsFactory) {
      this.typeInformation = typeInformation;
      this.typeConversionsFactory = typeConversionsFactory;
    }

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      return instrumentedType;
    }

    @Override
    public ByteCodeAppender appender(final Target implementationTarget) {
      return (methodVisitor, implementationContext, instrumentedMethod) -> {
        // this + method parameters.
        int numLocals = 1 + instrumentedMethod.getParameters().size();

        StackManipulation cast =
            typeInformation.getRawType().isAssignableFrom(typeInformation.getField().getType())
                ? StackManipulation.Trivial.INSTANCE
                : TypeCasting.to(TypeDescription.ForLoadedType.of(typeInformation.getRawType()));

        // StackManipulation that will read the value from the class field.
        StackManipulation readValue =
            new StackManipulation.Compound(
                // Method param is offset 1 (offset 0 is the this parameter).
                MethodVariableAccess.REFERENCE.loadFrom(1),
                // Read the field from the object.
                FieldAccess.forField(new ForLoadedField(typeInformation.getField())).read(),
                cast);

        StackManipulation stackManipulation =
            new StackManipulation.Compound(
                typeConversionsFactory
                    .createGetterConversions(readValue)
                    .convert(typeInformation.getType()),
                MethodReturn.REFERENCE);

        StackManipulation.Size size = stackManipulation.apply(methodVisitor, implementationContext);
        return new Size(size.getMaximalSize(), numLocals);
      };
    }
  }

  // Implements a method to set a public field in an object.
  static class SetFieldInstruction implements Implementation {
    // Field that will be read.
    private Field field;
    private final TypeConversionsFactory typeConversionsFactory;

    SetFieldInstruction(Field field, TypeConversionsFactory typeConversionsFactory) {
      this.field = field;
      this.typeConversionsFactory = typeConversionsFactory;
    }

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      return instrumentedType;
    }

    @Override
    public ByteCodeAppender appender(final Target implementationTarget) {
      return (methodVisitor, implementationContext, instrumentedMethod) -> {
        // this + method parameters.
        int numLocals = 1 + instrumentedMethod.getParameters().size();

        // The instruction to read the field.
        StackManipulation readField = MethodVariableAccess.REFERENCE.loadFrom(2);

        // Read the object onto the stack.
        StackManipulation stackManipulation =
            new StackManipulation.Compound(
                // Object param is offset 1.
                MethodVariableAccess.REFERENCE.loadFrom(1),
                // Do any conversions necessary.
                typeConversionsFactory
                    .createSetterConversions(readField)
                    .convert(TypeDescriptor.of(field.getType())),
                // Now update the field and return void.
                FieldAccess.forField(new ForLoadedField(field)).write(),
                MethodReturn.VOID);

        StackManipulation.Size size = stackManipulation.apply(methodVisitor, implementationContext);
        return new Size(size.getMaximalSize(), numLocals);
      };
    }
  }

  // Implements a method to construct an object.
  static class SetFieldCreateInstruction implements Implementation {
    private final List<FieldValueTypeInformation> typeInformations;
    private final Class<?> pojoClass;
    private final TypeConversionsFactory typeConversionsFactory;

    SetFieldCreateInstruction(
        List<FieldValueTypeInformation> typeInformations,
        Class<?> pojoClass,
        TypeConversionsFactory typeConversionsFactory) {
      this.typeInformations = typeInformations;
      this.pojoClass = pojoClass;
      this.typeConversionsFactory = typeConversionsFactory;
    }

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      return instrumentedType;
    }

    @Override
    public ByteCodeAppender appender(final Target implementationTarget) {
      return buildAppender(false);
    }

    public Implementation fromRowImplementation() {
      return new Implementation.Simple(buildAppender(true));
    }

    private ByteCodeAppender buildAppender(boolean fromRow) {
      return (methodVisitor, implementationContext, instrumentedMethod) -> {
        int numLocals = 1 + instrumentedMethod.getParameters().size();
        ForLoadedType loadedType = new ForLoadedType(pojoClass);
        StackManipulation stackManipulation =
            new StackManipulation.Compound(
                TypeCreation.of(loadedType),
                Duplication.SINGLE,
                MethodInvocation.invoke(
                    loadedType
                        .getDeclaredMethods()
                        .filter(
                            ElementMatchers.isConstructor().and(ElementMatchers.takesArguments(0)))
                        .getOnly()));
        for (int i = 0; i < typeInformations.size(); ++i) {
          FieldValueTypeInformation typeInformation = typeInformations.get(i);
          Field field = typeInformation.getField();
          StackManipulation updateField =
              new StackManipulation.Compound(
                  Duplication.SINGLE,
                  ByteBuddyUtils.readAndConvertParameter(
                      typeInformation, i, typeConversionsFactory, fromRow),
                  FieldAccess.forField(new ForLoadedField(field)).write());
          stackManipulation = new StackManipulation.Compound(stackManipulation, updateField);
        }
        stackManipulation =
            new StackManipulation.Compound(stackManipulation, MethodReturn.REFERENCE);
        StackManipulation.Size size = stackManipulation.apply(methodVisitor, implementationContext);
        return new Size(size.getMaximalSize(), numLocals);
      };
    }
  }
}
