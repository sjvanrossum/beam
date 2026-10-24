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
package org.apache.beam.sdk.util;

import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.jar.asm.ConstantDynamic;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import org.apache.beam.sdk.schemas.Schema.TypeName;
import org.apache.beam.sdk.util.common.ReflectHelpers;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions;
import org.joda.time.ReadableInstant;

/** Utilities for working with Byte Buddy. */
public final class ByteBuddyUtils {
  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
  private static final ConcurrentHashMap<Integer, Object> CONSTANTS = new ConcurrentHashMap<>();
  private static final AtomicInteger NEXT_CONSTANT_ID = new AtomicInteger();
  private static final String INTERNAL_ROW_NAME = Type.getInternalName(Row.class);
  private static final String ROW_DESC = Type.getDescriptor(Row.class);
  private static final String OBJECT_DESC = Type.getDescriptor(Object.class);

  private static final Handle BOOTSTRAP_CONSTANT_HANDLE =
      new Handle(
          Opcodes.H_INVOKESTATIC,
          Type.getInternalName(ByteBuddyUtils.class),
          "bootstrapConstant",
          "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;I)Ljava/lang/Object;",
          false);

  private ByteBuddyUtils() {} // Non-instantiable

  /** Bootstrap method for resolving dynamically-computed constants (JVMS 5.4.3.6). */
  public static Object bootstrapConstant(
      MethodHandles.Lookup lookup, String name, Class<?> type, int id) {
    return Preconditions.checkNotNull(CONSTANTS.get(id), "No constant registered for id %s", id);
  }

  /** Creates a {@link ConstantDynamic} representing the given live object reference. */
  public static ConstantDynamic createConstantDynamic(
      String name, Class<?> constantType, Object value) {
    int id = NEXT_CONSTANT_ID.getAndIncrement();
    CONSTANTS.put(id, value);
    return new ConstantDynamic(
        name, Type.getDescriptor(constantType), BOOTSTRAP_CONSTANT_HANDLE, id);
  }

  /** Emits an {@code LDC} instruction loading the given live object via {@link ConstantDynamic}. */
  public static void emitLdcConstant(
      MethodVisitor mv, String name, Class<?> constantType, Object value) {
    mv.visitLdcInsn(createConstantDynamic(name, constantType, value));
  }

  /**
   * Returns a Byte Buddy {@link StackManipulation} that pushes the given live object onto the
   * operand stack via {@link ConstantDynamic}.
   */
  public static StackManipulation loadConstantDynamic(
      String name, Class<?> constantType, Object value) {
    ConstantDynamic condy = createConstantDynamic(name, constantType, value);
    StackManipulation.Size size =
        TypeDescription.ForLoadedType.of(constantType).getStackSize().toIncreasingSize();
    return new StackManipulation.AbstractBase() {
      @Override
      public Size apply(MethodVisitor mv, Implementation.Context implementationContext) {
        mv.visitLdcInsn(condy);
        return size;
      }
    };
  }

  /** Emits the most compact bytecode instruction to push an {@code int} constant onto the stack. */
  public static void pushInt(MethodVisitor mv, int value) {
    if (value >= -1 && value <= 5) {
      mv.visitInsn(Opcodes.ICONST_0 + value);
    } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
      mv.visitIntInsn(Opcodes.BIPUSH, value);
    } else {
      mv.visitIntInsn(Opcodes.SIPUSH, value);
    }
  }

  /** Returns true if the given schema {@link TypeName} is stored as an unboxed Java primitive. */
  public static boolean isPrimitiveField(TypeName typeName) {
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

  /** Returns the JVM field {@link Class} used by generated {@link Row} subclasses for a field. */
  public static Class<?> fieldJvmType(TypeName typeName) {
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

  /** Returns the JVM field descriptor corresponding to {@link #fieldJvmType(TypeName)}. */
  public static String getFieldDescriptor(TypeName typeName) {
    switch (typeName) {
      case BYTE:
        return "B";
      case INT16:
        return "S";
      case INT32:
        return "I";
      case INT64:
        return "J";
      case FLOAT:
        return "F";
      case DOUBLE:
        return "D";
      case BOOLEAN:
        return "Z";
      case STRING:
        return "Ljava/lang/String;";
      case BYTES:
        return "[B";
      case DECIMAL:
        return "Ljava/math/BigDecimal;";
      case DATETIME:
        return "Lorg/joda/time/ReadableInstant;";
      case ROW:
        return ROW_DESC;
      case ARRAY:
        return "Ljava/util/Collection;";
      case ITERABLE:
        return "Ljava/lang/Iterable;";
      case MAP:
        return "Ljava/util/Map;";
      case LOGICAL_TYPE:
      default:
        return OBJECT_DESC;
    }
  }

  /** Emits bytecode to unbox a boxed primitive or cast a reference to its field JVM type. */
  public static void unboxOrCast(MethodVisitor mv, TypeName typeName) {
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

  /** Emits bytecode to box an unboxed primitive on the top of the operand stack. */
  public static void boxPrimitive(MethodVisitor mv, TypeName typeName) {
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

  /** Returns the unboxed primitive getter method name on {@link Row} for a primitive type. */
  public static String primitiveGetterName(TypeName typeName) {
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

  /** Returns the boxed primitive getter method name on {@link Row} for a primitive type. */
  public static String boxedGetterName(TypeName typeName) {
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

  /** Returns the JVM descriptor for the boxed wrapper class of a primitive {@link TypeName}. */
  public static String boxedDescriptor(TypeName typeName) {
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

  /** Returns the typed return opcode ({@code IRETURN}, {@code LRETURN}, etc.) for a primitive. */
  public static int primitiveReturnOpcode(TypeName typeName) {
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

  /** Returns a class loading strategy that is compatible with Java 11+. */
  public static ClassLoadingStrategy<ClassLoader> getClassLoadingStrategy(Class<?> targetClass) {
    ClassLoader classLoader = ReflectHelpers.findClassLoader(targetClass);
    // Use a Lookup-based strategy for user classes for compatibility with Java 17+, but use
    // a legacy strategy to allow proxying some built-in JDK classes (e.g. interfaces, public
    // abstract classes) since we don't have permissions to get a private lookup for the
    // java.base module
    boolean systemClass = classLoader == null;
    if (!systemClass) {
      try {
        Module thisModule = ByteBuddyUtils.class.getModule();
        Module targetModule = targetClass.getModule();
        if (!thisModule.canRead(targetModule)) {
          thisModule.addReads(targetModule);
        }
        return ClassLoadingStrategy.UsingLookup.of(
            MethodHandles.privateLookupIn(targetClass, LOOKUP));
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(
            "No code generation strategy available " + targetClass + " " + classLoader, e);
      }
    } else if (ClassInjector.UsingReflection.isAvailable()) {
      return ClassLoadingStrategy.Default.INJECTION;
    } else {
      throw new IllegalStateException("No code generation strategy available");
    }
  }
}
