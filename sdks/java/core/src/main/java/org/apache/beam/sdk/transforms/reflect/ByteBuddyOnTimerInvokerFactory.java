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
package org.apache.beam.sdk.transforms.reflect;

import static org.apache.beam.sdk.util.ByteBuddyUtils.getClassLoadingStrategy;
import static org.apache.beam.sdk.util.common.ReflectHelpers.findClassLoader;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.modifier.FieldManifestation;
import net.bytebuddy.description.modifier.TypeManifestation;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.VisibilityBridgeStrategy;
import net.bytebuddy.dynamic.scaffold.InstrumentedType;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.ByteCodeAppender;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.member.FieldAccess;
import net.bytebuddy.implementation.bytecode.member.MethodInvocation;
import net.bytebuddy.implementation.bytecode.member.MethodReturn;
import net.bytebuddy.implementation.bytecode.member.MethodVariableAccess;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.matcher.ElementMatchers;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.DoFn.OnTimer;
import org.apache.beam.sdk.transforms.DoFn.TimerId;
import org.apache.beam.sdk.transforms.reflect.ByteBuddyDoFnInvokerFactory.DoFnMethodWithExtraParametersDelegation;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.CharMatcher;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.cache.CacheBuilder;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.cache.CacheLoader;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.cache.LoadingCache;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.io.BaseEncoding;

/**
 * Dynamically generates {@link OnTimerInvoker} instances for invoking a particular {@link TimerId}
 * on a particular {@link DoFn}.
 */
@SuppressWarnings({
  "nullness" // TODO(https://github.com/apache/beam/issues/20497)
})
class ByteBuddyOnTimerInvokerFactory implements OnTimerInvokerFactory {

  private static final ByteBuddy BYTE_BUDDY =
      new ByteBuddy(ClassFileVersion.ofThisVm(ClassFileVersion.JAVA_V11))
          .with(TypeValidation.DISABLED)
          .with(Implementation.Context.Disabled.Factory.INSTANCE)
          .with(VisibilityBridgeStrategy.Default.NEVER);

  private static final MethodDescription OBJECT_DEFAULT_CONSTRUCTOR;

  static {
    try {
      OBJECT_DEFAULT_CONSTRUCTOR =
          new MethodDescription.ForLoadedConstructor(Object.class.getDeclaredConstructor());
    } catch (NoSuchMethodException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @Override
  public <InputT, OutputT> OnTimerInvoker<InputT, OutputT> forTimer(
      DoFn<InputT, OutputT> fn, String timerId) {

    @SuppressWarnings("unchecked")
    Class<? extends DoFn<?, ?>> fnClass = (Class<? extends DoFn<?, ?>>) fn.getClass();
    try {
      OnTimerMethodSpecifier onTimerMethodSpecifier =
          OnTimerMethodSpecifier.forClassAndTimerId(fnClass, timerId);
      Constructor<?> constructor = constructorCache.get(onTimerMethodSpecifier);

      return (OnTimerInvoker<InputT, OutputT>) constructor.newInstance(fn);
    } catch (InstantiationException
        | IllegalAccessException
        | IllegalArgumentException
        | InvocationTargetException
        | SecurityException
        | ExecutionException e) {
      throw new RuntimeException(
          String.format(
              "Unable to construct @%s invoker for %s",
              OnTimer.class.getSimpleName(), fn.getClass().getName()),
          e);
    }
  }

  public <InputT, OutputT> OnTimerInvoker<InputT, OutputT> forTimerFamily(
      DoFn<InputT, OutputT> fn, String timerId) {

    @SuppressWarnings("unchecked")
    Class<? extends DoFn<?, ?>> fnClass = (Class<? extends DoFn<?, ?>>) fn.getClass();
    try {
      OnTimerMethodSpecifier onTimerMethodSpecifier =
          OnTimerMethodSpecifier.forClassAndTimerId(fnClass, timerId);
      Constructor<?> constructor = constructorTimerFamilyCache.get(onTimerMethodSpecifier);

      return (OnTimerInvoker<InputT, OutputT>) constructor.newInstance(fn);
    } catch (InstantiationException
        | IllegalAccessException
        | IllegalArgumentException
        | InvocationTargetException
        | SecurityException
        | ExecutionException e) {
      throw new RuntimeException(
          String.format(
              "Unable to construct @%s invoker for %s",
              DoFn.OnTimerFamily.class.getSimpleName(), fn.getClass().getName()),
          e);
    }
  }

  public static ByteBuddyOnTimerInvokerFactory only() {
    return INSTANCE;
  }

  private static final ByteBuddyOnTimerInvokerFactory INSTANCE =
      new ByteBuddyOnTimerInvokerFactory();

  private ByteBuddyOnTimerInvokerFactory() {}

  /**
   * The field name for the delegate of {@link DoFn} subclass that a bytebuddy invoker will call.
   */
  private static final String FN_DELEGATE_FIELD_NAME = "delegate";

  /**
   * A cache of constructors of generated {@link OnTimerInvoker} classes, keyed by {@link
   * OnTimerMethodSpecifier}.
   *
   * <p>Needed because generating an invoker class is expensive, and to avoid generating an
   * excessive number of classes consuming PermGen memory in Java's that still have PermGen.
   */
  private final LoadingCache<OnTimerMethodSpecifier, Constructor<?>> constructorCache =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<OnTimerMethodSpecifier, Constructor<?>>() {
                @Override
                public Constructor<?> load(final OnTimerMethodSpecifier onTimerMethodSpecifier)
                    throws Exception {
                  DoFnSignature signature =
                      DoFnSignatures.getSignature(onTimerMethodSpecifier.fnClass());
                  Class<? extends OnTimerInvoker<?, ?>> invokerClass =
                      generateOnTimerInvokerClass(signature, onTimerMethodSpecifier.timerId());
                  try {
                    return invokerClass.getConstructor(signature.fnClass());
                  } catch (IllegalArgumentException | NoSuchMethodException | SecurityException e) {
                    throw new RuntimeException(e);
                  }
                }
              });

  /**
   * A cache of constructors of generated {@link OnTimerInvoker} classes, keyed by {@link
   * OnTimerMethodSpecifier}.
   *
   * <p>Needed because generating an invoker class is expensive, and to avoid generating an
   * excessive number of classes consuming PermGen memory in Java's that still have PermGen.
   */
  private final LoadingCache<OnTimerMethodSpecifier, Constructor<?>> constructorTimerFamilyCache =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<OnTimerMethodSpecifier, Constructor<?>>() {
                @Override
                public Constructor<?> load(final OnTimerMethodSpecifier onTimerMethodSpecifier)
                    throws Exception {
                  DoFnSignature signature =
                      DoFnSignatures.getSignature(onTimerMethodSpecifier.fnClass());
                  Class<? extends OnTimerInvoker<?, ?>> invokerClass =
                      generateOnTimerFamilyInvokerClass(
                          signature, onTimerMethodSpecifier.timerId());
                  try {
                    return invokerClass.getConstructor(signature.fnClass());
                  } catch (IllegalArgumentException | NoSuchMethodException | SecurityException e) {
                    throw new RuntimeException(e);
                  }
                }
              });

  /**
   * Generates a {@link OnTimerInvoker} class for the given {@link DoFnSignature} and {@link
   * TimerId}.
   */
  private static Class<? extends OnTimerInvoker<?, ?>> generateOnTimerInvokerClass(
      DoFnSignature signature, String timerId) {
    return generateInvokerClass(signature, timerId, signature.onTimerMethods().get(timerId));
  }

  private static Class<? extends OnTimerInvoker<?, ?>> generateOnTimerFamilyInvokerClass(
      DoFnSignature signature, String timerId) {
    return generateInvokerClass(signature, timerId, signature.onTimerFamilyMethods().get(timerId));
  }

  private static Class<? extends OnTimerInvoker<?, ?>> generateInvokerClass(
      DoFnSignature signature,
      String timerId,
      DoFnSignature.MethodWithExtraParameters methodSignature) {
    Class<? extends DoFn<?, ?>> fnClass = signature.fnClass();

    final TypeDescription clazzDescription = new TypeDescription.ForLoadedType(fnClass);

    final String suffix =
        String.format(
            "%s$%s$%s",
            OnTimerInvoker.class.getSimpleName(),
            CharMatcher.javaLetterOrDigit().retainFrom(timerId),
            BaseEncoding.base64().omitPadding().encode(timerId.getBytes(StandardCharsets.UTF_8)));

    DynamicType.Unloaded<?> unloaded =
        BYTE_BUDDY
            // Create subclasses inside the target class, to have access to
            // private and package-private bits
            .with(StableInvokerNamingStrategy.forDoFnClass(fnClass).withSuffix(suffix))

            // class <invoker class> implements OnTimerInvoker {
            .subclass(OnTimerInvoker.class, ConstructorStrategy.Default.NO_CONSTRUCTORS)
            .modifiers(Visibility.PUBLIC, TypeManifestation.FINAL)

            //   private final <fn class> delegate;
            .defineField(
                FN_DELEGATE_FIELD_NAME, fnClass, Visibility.PRIVATE, FieldManifestation.FINAL)

            //   <invoker class>(<fn class> delegate) { this.delegate = delegate; }
            .defineConstructor(Visibility.PUBLIC)
            .withParameter(fnClass)
            .intercept(InvokerConstructor.INSTANCE)

            //   public invokeOnTimer(DoFn.ArgumentProvider) {
            //     this.delegate.<@OnTimer method>(... pass the right args ...)
            //   }
            .method(ElementMatchers.named("invokeOnTimer"))
            .intercept(
                new DoFnMethodWithExtraParametersDelegation(clazzDescription, methodSignature))
            .visit(
                new AsmVisitorWrapper.ForDeclaredMethods()
                    .writerFlags(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS))
            .make();

    @SuppressWarnings("unchecked")
    Class<? extends OnTimerInvoker<?, ?>> res =
        (Class<? extends OnTimerInvoker<?, ?>>)
            unloaded
                .load(findClassLoader(fnClass.getClassLoader()), getClassLoadingStrategy(fnClass))
                .getLoaded();
    return res;
  }

  /**
   * A constructor {@link Implementation} for an {@link OnTimerInvoker} class. Produces the byte
   * code for a constructor that takes a single argument and assigns it to the delegate field.
   */
  private static final class InvokerConstructor implements Implementation {
    static final InvokerConstructor INSTANCE = new InvokerConstructor();

    private InvokerConstructor() {}

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      return instrumentedType;
    }

    @Override
    public ByteCodeAppender appender(final Target implementationTarget) {
      FieldDescription delegateField =
          implementationTarget
              .getInstrumentedType()
              .getDeclaredFields()
              .filter(ElementMatchers.named(FN_DELEGATE_FIELD_NAME))
              .getOnly();
      return (methodVisitor, implementationContext, instrumentedMethod) -> {
        StackManipulation.Size size =
            new StackManipulation.Compound(
                    // super()
                    MethodVariableAccess.REFERENCE.loadFrom(0),
                    MethodInvocation.invoke(OBJECT_DEFAULT_CONSTRUCTOR),
                    // this.delegate = delegate
                    MethodVariableAccess.REFERENCE.loadFrom(0),
                    MethodVariableAccess.REFERENCE.loadFrom(1),
                    FieldAccess.forField(delegateField).write(),
                    MethodReturn.VOID)
                .apply(methodVisitor, implementationContext);
        return new ByteCodeAppender.Size(size.getMaximalSize(), instrumentedMethod.getStackSize());
      };
    }
  }
}
