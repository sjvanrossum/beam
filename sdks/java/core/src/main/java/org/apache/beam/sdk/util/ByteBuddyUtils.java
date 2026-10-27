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
import org.apache.beam.sdk.util.common.ReflectHelpers;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions;

/** Utilities for working with Byte Buddy. */
public final class ByteBuddyUtils {
  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
  private static final ConcurrentHashMap<Integer, Object> CONSTANTS = new ConcurrentHashMap<>();
  private static final AtomicInteger NEXT_CONSTANT_ID = new AtomicInteger();

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
