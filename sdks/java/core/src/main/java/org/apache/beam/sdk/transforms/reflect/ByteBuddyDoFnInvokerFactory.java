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
import static org.apache.beam.sdk.util.Preconditions.checkStateNotNull;
import static org.apache.beam.sdk.util.common.ReflectHelpers.findClassLoader;
import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions.checkArgument;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.modifier.FieldManifestation;
import net.bytebuddy.description.modifier.TypeManifestation;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.description.type.TypeDescription.ForLoadedType;
import net.bytebuddy.description.type.TypeList;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.VisibilityBridgeStrategy;
import net.bytebuddy.dynamic.scaffold.InstrumentedType;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy;
import net.bytebuddy.implementation.ExceptionMethod;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.Implementation.Context;
import net.bytebuddy.implementation.bytecode.ByteCodeAppender;
import net.bytebuddy.implementation.bytecode.Duplication;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.StackManipulation.Compound;
import net.bytebuddy.implementation.bytecode.Throw;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.implementation.bytecode.assign.Assigner.Typing;
import net.bytebuddy.implementation.bytecode.assign.TypeCasting;
import net.bytebuddy.implementation.bytecode.constant.IntegerConstant;
import net.bytebuddy.implementation.bytecode.constant.TextConstant;
import net.bytebuddy.implementation.bytecode.member.FieldAccess;
import net.bytebuddy.implementation.bytecode.member.MethodInvocation;
import net.bytebuddy.implementation.bytecode.member.MethodReturn;
import net.bytebuddy.implementation.bytecode.member.MethodVariableAccess;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.matcher.ElementMatchers;
import org.apache.beam.sdk.coders.CannotProvideCoderException;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.CoderRegistry;
import org.apache.beam.sdk.state.Timer;
import org.apache.beam.sdk.state.TimerMap;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.DoFn.GetSize;
import org.apache.beam.sdk.transforms.DoFn.ProcessElement;
import org.apache.beam.sdk.transforms.DoFn.TruncateRestriction;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.OnTimerFamilyMethod;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.OnTimerMethod;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.BundleFinalizerParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.Cases;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.CausedByDrainParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.CurrentRecordIdParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.CurrentRecordOffsetParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.ElementParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.FinishBundleContextParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.FireTimestampParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.OnTimerContextParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.OutputReceiverParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.PaneInfoParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.ProcessContextParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.RestrictionParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.RestrictionTrackerParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.SchemaElementParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.SideInputParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.StartBundleContextParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.StateParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.TaggedOutputReceiverParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.TimeDomainParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.TimerFamilyParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.TimerParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.TimestampParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.ValueKindParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.WatermarkEstimatorParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.WatermarkEstimatorStateParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.WindowParameter;
import org.apache.beam.sdk.transforms.splittabledofn.HasDefaultTracker;
import org.apache.beam.sdk.transforms.splittabledofn.HasDefaultWatermarkEstimator;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker.HasProgress;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker.IsBounded;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker.TruncateResult;
import org.apache.beam.sdk.transforms.splittabledofn.WatermarkEstimator;
import org.apache.beam.sdk.transforms.windowing.GlobalWindow;
import org.apache.beam.sdk.util.ByteBuddyUtils;
import org.apache.beam.sdk.util.UserCodeException;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.apache.beam.sdk.values.TypeDescriptors;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.MoreObjects;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.MapMaker;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.Maps;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.primitives.Primitives;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joda.time.Instant;

/** Dynamically generates a {@link DoFnInvoker} instances for invoking a {@link DoFn}. */
@SuppressWarnings({"rawtypes"})
class ByteBuddyDoFnInvokerFactory implements DoFnInvokerFactory {

  private static final ByteBuddy BYTE_BUDDY =
      new ByteBuddy(ClassFileVersion.ofThisVm(ClassFileVersion.JAVA_V11))
          .with(TypeValidation.DISABLED)
          .with(Implementation.Context.Disabled.Factory.INSTANCE)
          .with(VisibilityBridgeStrategy.Default.NEVER);

  public static final String SETUP_CONTEXT_PARAMETER_METHOD = "setupContext";
  public static final String START_BUNDLE_CONTEXT_PARAMETER_METHOD = "startBundleContext";
  public static final String FINISH_BUNDLE_CONTEXT_PARAMETER_METHOD = "finishBundleContext";
  public static final String PROCESS_CONTEXT_PARAMETER_METHOD = "processContext";
  public static final String ELEMENT_PARAMETER_METHOD = "element";
  public static final String SCHEMA_ELEMENT_PARAMETER_METHOD = "schemaElement";
  public static final String TIMESTAMP_PARAMETER_METHOD = "timestamp";
  public static final String CAUSED_BY_DRAIN_PARAMETER_METHOD = "causedByDrain";
  public static final String CURRENT_RECORD_ID_PARAMETER_METHOD = "currentRecordId";
  public static final String CURRENT_RECORD_OFFSET_PARAMETER_METHOD = "currentRecordOffset";
  public static final String FIRE_TIMESTAMP_PARAMETER_METHOD = "fireTimestamp";
  public static final String VALUE_KIND_PARAMETER_METHOD = "valueKind";
  public static final String BUNDLE_FINALIZER_PARAMETER_METHOD = "bundleFinalizer";
  public static final String OUTPUT_ROW_RECEIVER_METHOD = "outputRowReceiver";
  public static final String TIME_DOMAIN_PARAMETER_METHOD = "timeDomain";
  public static final String OUTPUT_PARAMETER_METHOD = "outputReceiver";
  public static final String TAGGED_OUTPUT_PARAMETER_METHOD = "taggedOutputReceiver";
  public static final String ON_TIMER_CONTEXT_PARAMETER_METHOD = "onTimerContext";
  public static final String ON_WINDOW_EXPIRATION_CONTEXT_PARAMETER_METHOD =
      "onWindowExpirationContext";
  public static final String WINDOW_PARAMETER_METHOD = "window";
  public static final String PANE_INFO_PARAMETER_METHOD = "paneInfo";
  public static final String PIPELINE_OPTIONS_PARAMETER_METHOD = "pipelineOptions";
  public static final String RESTRICTION_PARAMETER_METHOD = "restriction";
  public static final String RESTRICTION_TRACKER_PARAMETER_METHOD = "restrictionTracker";
  public static final String WATERMARK_ESTIMATOR_PARAMETER_METHOD = "watermarkEstimator";
  public static final String WATERMARK_ESTIMATOR_STATE_PARAMETER_METHOD = "watermarkEstimatorState";
  public static final String STATE_PARAMETER_METHOD = "state";
  public static final String TIMER_PARAMETER_METHOD = "timer";
  public static final String SIDE_INPUT_PARAMETER_METHOD = "sideInput";
  public static final String TIMER_FAMILY_PARAMETER_METHOD = "timerFamily";
  public static final String TIMER_ID_PARAMETER_METHOD = "timerId";
  public static final String KEY_PARAMETER_METHOD = "key";

  private static final String THROWABLE_INTERNAL_NAME = Type.getInternalName(Throwable.class);
  private static final String DOFN_INVOKER_BASE_INTERNAL_NAME =
      Type.getInternalName(DoFnInvokerBase.class);

  private static final StackManipulation LOAD_PROCESS_CONTINUATION_STOP =
      ByteBuddyUtils.loadConstantDynamic(
          "processContinuationStop",
          DoFn.ProcessContinuation.class,
          DoFn.ProcessContinuation.stop());

  private static final MethodDescription CREATE_USER_CODE_EXCEPTION;
  private static final MethodDescription VALIDATE_SIZE_METHOD;
  private static final MethodDescription CODER_REGISTRY_GET_CODER_METHOD;
  private static final MethodDescription DEFAULT_SPLIT_RESTRICTION_METHOD;
  private static final MethodDescription DEFAULT_TRUNCATE_RESTRICTION_METHOD;
  private static final MethodDescription DEFAULT_NEW_WATERMARK_ESTIMATOR_METHOD;
  private static final MethodDescription DEFAULT_NEW_TRACKER_METHOD;
  private static final MethodDescription DEFAULT_GET_SIZE_METHOD;
  private static final MethodDescription DOFN_INVOKER_BASE_CONSTRUCTOR;

  static {
    try {
      CREATE_USER_CODE_EXCEPTION =
          new MethodDescription.ForLoadedMethod(
              UserCodeException.class.getDeclaredMethod("wrap", Throwable.class));
      VALIDATE_SIZE_METHOD =
          new MethodDescription.ForLoadedMethod(
              DefaultGetSize.class.getMethod("validateSize", double.class));
      CODER_REGISTRY_GET_CODER_METHOD =
          new MethodDescription.ForLoadedMethod(
              CoderRegistry.class.getMethod("getCoder", TypeDescriptor.class));
      DEFAULT_SPLIT_RESTRICTION_METHOD =
          new MethodDescription.ForLoadedMethod(
              DefaultSplitRestriction.class.getMethod(
                  "invokeSplitRestriction", DoFnInvoker.ArgumentProvider.class));
      DEFAULT_TRUNCATE_RESTRICTION_METHOD =
          new MethodDescription.ForLoadedMethod(
              DefaultTruncateRestriction.class.getMethod(
                  "invokeTruncateRestriction", DoFnInvoker.ArgumentProvider.class));
      DEFAULT_NEW_WATERMARK_ESTIMATOR_METHOD =
          new MethodDescription.ForLoadedMethod(
              DefaultNewWatermarkEstimator.class.getMethod(
                  "invokeNewWatermarkEstimator", DoFnInvoker.ArgumentProvider.class));
      DEFAULT_NEW_TRACKER_METHOD =
          new MethodDescription.ForLoadedMethod(
              DefaultNewTracker.class.getMethod(
                  "invokeNewTracker", DoFnInvoker.ArgumentProvider.class));
      DEFAULT_GET_SIZE_METHOD =
          new MethodDescription.ForLoadedMethod(
              DefaultGetSize.class.getMethod("invokeGetSize", DoFnInvoker.ArgumentProvider.class));
      DOFN_INVOKER_BASE_CONSTRUCTOR =
          new MethodDescription.ForLoadedConstructor(
              DoFnInvokerBase.class.getDeclaredConstructor(DoFn.class));
    } catch (NoSuchMethodException | SecurityException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * Returns a {@link ByteBuddyDoFnInvokerFactory} shared with all other invocations, so that its
   * cache of generated classes is global.
   */
  public static ByteBuddyDoFnInvokerFactory only() {
    return INSTANCE;
  }

  /**
   * Creates a {@link DoFnInvoker} for the given {@link DoFn} by generating bytecode that directly
   * invokes its methods with arguments extracted from the {@link DoFnInvoker.ArgumentProvider}.
   */
  @Override
  public <InputT, OutputT> DoFnInvoker<InputT, OutputT> invokerFor(DoFn<InputT, OutputT> fn) {
    return newByteBuddyInvoker(fn);
  }

  private static final ByteBuddyDoFnInvokerFactory INSTANCE = new ByteBuddyDoFnInvokerFactory();

  private static final String FN_DELEGATE_FIELD_NAME = "delegate";

  /**
   * Cache key for DoFnInvoker constructors that includes both the DoFn class and its generic type
   * parameters to prevent collisions when the same DoFn class is used with different generic types.
   */
  private static final class InvokerCacheKey {
    private final Class<? extends DoFn<?, ?>> fnClass;
    private final TypeDescriptor<?> inputType;
    private final TypeDescriptor<?> outputType;

    InvokerCacheKey(
        Class<? extends DoFn<?, ?>> fnClass,
        TypeDescriptor<?> inputType,
        TypeDescriptor<?> outputType) {
      this.fnClass = fnClass;
      this.inputType = inputType;
      this.outputType = outputType;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof InvokerCacheKey)) {
        return false;
      }
      InvokerCacheKey that = (InvokerCacheKey) o;
      return Objects.equals(fnClass, that.fnClass)
          && Objects.equals(inputType, that.inputType)
          && Objects.equals(outputType, that.outputType);
    }

    @Override
    public int hashCode() {
      return Objects.hash(fnClass, inputType, outputType);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("fnClass", fnClass.getName())
          .add("inputType", inputType)
          .add("outputType", outputType)
          .toString();
    }
  }

  /**
   * A cache of constructors of generated {@link DoFnInvoker} classes, keyed by {@link DoFn} class
   * and its generic type parameters. Needed because generating an invoker class is expensive, and
   * to avoid generating an excessive number of classes consuming PermGen memory.
   *
   * <p>The cache key includes generic type information to prevent collisions when the same DoFn
   * class is used with different generic types (e.g., MyDoFn&lt;String&gt; vs
   * MyDoFn&lt;Integer&gt;).
   *
   * <p>Note that special care must be taken to enumerate this object as concurrent hash maps are <a
   * href="https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/package-summary.html#Weakly>weakly
   * consistent</a>.
   */
  private final Map<InvokerCacheKey, Constructor<?>> byteBuddyInvokerConstructorCache =
      new ConcurrentHashMap<>();

  /**
   * A weak, identity-keyed cache of the constructor selected for each {@link DoFn} instance.
   *
   * <p>Selecting a constructor resolves the {@link DoFn}'s input and output type descriptors. Some
   * runners create a new invoker for the same {@link DoFn} at every bundle boundary, so doing that
   * work on every invocation can be expensive. A deserialized {@link DoFn} is a new instance, so
   * its constructor is selected again; later invoker creation for that instance reuses the cached
   * selection.
   *
   * <p>The constructor values do not reference their {@link DoFn} keys, and weak keys allow unused
   * instances to be collected. {@link MapMaker#weakKeys} also uses identity equality, which is
   * required because separate instances of the same class may have different generic types.
   */
  private final ConcurrentMap<DoFn<?, ?>, Constructor<?>> byteBuddyInvokerConstructorInstanceCache =
      new MapMaker().weakKeys().makeMap();

  private ByteBuddyDoFnInvokerFactory() {}

  /** Returns the {@link DoFnInvoker} for the given {@link DoFn}. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public <InputT, OutputT> DoFnInvoker<InputT, OutputT> newByteBuddyInvoker(
      DoFn<InputT, OutputT> fn) {
    return newByteBuddyInvoker(DoFnSignatures.getSignature((Class) fn.getClass()), fn);
  }

  /**
   * Internal base class for generated {@link DoFnInvoker} instances.
   *
   * <p>This class should <i>not</i> be extended directly, or by Beam users. It must be public for
   * generated instances to have adequate access, as they are generated "inside" the invoked {@link
   * DoFn} class.
   */
  public abstract static class DoFnInvokerBase<InputT, OutputT, DoFnT extends DoFn<InputT, OutputT>>
      implements DoFnInvoker<InputT, OutputT> {
    protected DoFnT delegate;

    private @Nullable Map<String, OnTimerInvoker> onTimerInvokers;
    private @Nullable Map<String, OnTimerInvoker> onTimerFamilyInvokers;

    public DoFnInvokerBase(DoFnT delegate) {
      this.delegate = delegate;
    }

    /**
     * Associates the given timer ID with the given {@link OnTimerInvoker}.
     *
     * <p>This method has package level access as it is intended only for assembly of the {@link
     * DoFnInvokerBase} not by any subclass.
     */
    void addOnTimerInvoker(String timerId, OnTimerInvoker onTimerInvoker) {
      if (this.onTimerInvokers == null) {
        this.onTimerInvokers = Maps.newHashMap();
      }
      this.onTimerInvokers.put(timerId, onTimerInvoker);
    }

    /** Associates the given timerFamily ID with the given {@link OnTimerInvoker}. */
    void addOnTimerFamilyInvoker(String timerFamilyId, OnTimerInvoker onTimerInvoker) {
      if (this.onTimerFamilyInvokers == null) {
        this.onTimerFamilyInvokers = Maps.newHashMap();
      }
      this.onTimerFamilyInvokers.put(timerFamilyId, onTimerInvoker);
    }

    @Override
    public void invokeOnTimer(
        String timerId,
        String timerFamilyId,
        DoFnInvoker.ArgumentProvider<InputT, OutputT> arguments) {
      @Nullable OnTimerInvoker onTimerInvoker =
          timerFamilyId.isEmpty()
              ? (onTimerInvokers != null ? onTimerInvokers.get(timerId) : null)
              : (onTimerFamilyInvokers != null ? onTimerFamilyInvokers.get(timerFamilyId) : null);

      if (onTimerInvoker != null) {
        onTimerInvoker.invokeOnTimer(arguments);
      } else {
        throw new IllegalArgumentException(
            String.format(
                "Attempted to invoke timer %s on %s, but that timer is not registered."
                    + " This is the responsibility of the runner, which must only deliver"
                    + " registered timers.",
                timerId, delegate.getClass().getName()));
      }
    }

    @Override
    public DoFn<InputT, OutputT> getFn() {
      return delegate;
    }
  }

  /** Returns the {@link DoFnInvoker} for the given {@link DoFn}. */
  public <InputT, OutputT> DoFnInvoker<InputT, OutputT> newByteBuddyInvoker(
      DoFnSignature signature, DoFn<InputT, OutputT> fn) {
    checkArgument(
        signature.fnClass().equals(fn.getClass()),
        "Signature is for class %s, but fn is of class %s",
        signature.fnClass(),
        fn.getClass());

    Constructor<?> invokerConstructor =
        byteBuddyInvokerConstructorInstanceCache.computeIfAbsent(
            fn, unused -> getByteBuddyInvokerConstructor(signature, fn));

    try {
      @SuppressWarnings("unchecked")
      DoFnInvoker<InputT, OutputT> invoker =
          (DoFnInvoker<InputT, OutputT>) invokerConstructor.newInstance(fn);
      return invoker;
    } catch (InstantiationException
        | IllegalAccessException
        | IllegalArgumentException
        | InvocationTargetException
        | SecurityException e) {
      throw new RuntimeException("Unable to bind invoker for " + fn.getClass(), e);
    }
  }

  private <InputT, OutputT> Constructor<?> getByteBuddyInvokerConstructor(
      DoFnSignature signature, DoFn<InputT, OutputT> fn) {
    // Extract input and output type descriptors to distinguish generic instantiations.
    // Fall back to Object.class if unavailable. When type info is lost, different generic
    // instantiations share an invoker, which is acceptable since the DoFn class in the cache
    // key prevents collisions between different DoFn classes.
    TypeDescriptor<InputT> inputType;
    try {
      inputType = fn.getInputTypeDescriptor();
    } catch (Exception e) {
      // Some DoFns (like MapElements) throw IllegalStateException if queried after
      // serialization.
      // In this case, we fall back to the raw class behavior (Object).
      inputType = null;
    }
    if (inputType == null) {
      inputType = (TypeDescriptor<InputT>) TypeDescriptor.of(Object.class);
    }

    TypeDescriptor<OutputT> outputType;
    try {
      outputType = fn.getOutputTypeDescriptor();
    } catch (Exception e) {
      // Same as above: fall back to Object if type info is unavailable.
      outputType = null;
    }
    if (outputType == null) {
      outputType = (TypeDescriptor<OutputT>) TypeDescriptor.of(Object.class);
    }

    return getByteBuddyInvokerConstructor(signature, inputType, outputType);
  }

  /**
   * Returns a generated constructor for a {@link DoFnInvoker} for the given {@link DoFnSignature}
   * and specific generic types.
   *
   * <p>These are cached such that at most one {@link DoFnInvoker} class exists for a given {@link
   * DoFn} class with specific generic type parameters. Different generic instantiations of the same
   * DoFn class will have separate cached invoker classes.
   */
  private Constructor<?> getByteBuddyInvokerConstructor(
      DoFnSignature signature, TypeDescriptor<?> inputType, TypeDescriptor<?> outputType) {
    Class<? extends DoFn<?, ?>> fnClass = signature.fnClass();
    InvokerCacheKey cacheKey = new InvokerCacheKey(fnClass, inputType, outputType);
    return byteBuddyInvokerConstructorCache.computeIfAbsent(
        cacheKey,
        key -> {
          Class<? extends DoFnInvoker<?, ?>> invokerClass =
              generateInvokerClass(signature, inputType, outputType);
          try {
            return invokerClass.getConstructor(fnClass);
          } catch (IllegalArgumentException | NoSuchMethodException | SecurityException e) {
            throw new RuntimeException(e);
          }
        });
  }

  /** Default implementation of {@link DoFn.SplitRestriction}, for delegation by bytebuddy. */
  public static class DefaultSplitRestriction {
    /** Doesn't split the restriction. */
    @SuppressWarnings({
      "unused",
      "nullness" // TODO: DoFn parameter is actually never used by outputReceiver
    })
    public static void invokeSplitRestriction(DoFnInvoker.ArgumentProvider argumentProvider) {
      argumentProvider.outputReceiver(null).output(argumentProvider.restriction());
    }
  }

  /** Default implementation of {@link TruncateRestriction}, for delegation by bytebuddy. */
  public static class DefaultTruncateRestriction {

    /** Output the current restriction if it is bounded. Otherwise, return null. */
    @SuppressWarnings("unused")
    public static @Nullable TruncateResult<?> invokeTruncateRestriction(
        DoFnInvoker.ArgumentProvider argumentProvider) {
      if (argumentProvider.restrictionTracker().isBounded() == IsBounded.BOUNDED) {
        return TruncateResult.of(argumentProvider.restriction());
      }
      return null;
    }
  }

  /** Default implementation of {@link DoFn.GetRestrictionCoder}, for delegation by bytebuddy. */
  public static class DefaultRestrictionCoder {
    private final TypeDescriptor<?> restrictionType;

    DefaultRestrictionCoder(TypeDescriptor<?> restrictionType) {
      this.restrictionType = restrictionType;
    }

    @SuppressWarnings({"unused", "unchecked"})
    public <RestrictionT> Coder<RestrictionT> invokeGetRestrictionCoder(CoderRegistry registry)
        throws CannotProvideCoderException {
      return (Coder) registry.getCoder(restrictionType);
    }
  }

  /**
   * Default implementation of {@link DoFn.GetWatermarkEstimatorStateCoder}, for delegation by
   * bytebuddy.
   */
  public static class DefaultWatermarkEstimatorStateCoder {
    private final TypeDescriptor<?> watermarkEstimatorStateType;

    DefaultWatermarkEstimatorStateCoder(TypeDescriptor<?> watermarkEstimatorStateType) {
      this.watermarkEstimatorStateType = watermarkEstimatorStateType;
    }

    @SuppressWarnings({"unused", "unchecked"})
    public <WatermarkEstimatorStateT>
        Coder<WatermarkEstimatorStateT> invokeGetWatermarkEstimatorStateCoder(
            CoderRegistry registry) throws CannotProvideCoderException {
      return (Coder) registry.getCoder(watermarkEstimatorStateType);
    }
  }

  /**
   * Default implementation for {@link DoFn.GetInitialWatermarkEstimatorState}, for delegation by
   * bytebuddy.
   */
  public static class DefaultGetInitialWatermarkEstimatorState {
    /** The default watermark estimator state is {@code null}. */
    @SuppressWarnings("unused")
    public static <InputT, OutputT, WatermarkEstimatorStateT> @Nullable
        WatermarkEstimator<WatermarkEstimatorStateT> invokeNewWatermarkEstimator(
            DoFnInvoker.ArgumentProvider<InputT, OutputT> argumentProvider) {
      return null;
    }
  }

  /** Default implementation of {@link DoFn.NewWatermarkEstimator}, for delegation by bytebuddy. */
  public static class DefaultNewWatermarkEstimator {

    /**
     * Constructs a new watermark estimator from the state type if it is annotated with {@link
     * HasDefaultWatermarkEstimator} otherwise returns a watermark estimator that always reports the
     * minimum watermark.
     */
    @SuppressWarnings("unused")
    public static <InputT, OutputT, WatermarkEstimatorStateT>
        WatermarkEstimator<WatermarkEstimatorStateT> invokeNewWatermarkEstimator(
            DoFnInvoker.ArgumentProvider<InputT, OutputT> argumentProvider) {
      if (argumentProvider.watermarkEstimatorState() instanceof HasDefaultWatermarkEstimator) {
        return ((HasDefaultWatermarkEstimator) argumentProvider.watermarkEstimatorState())
            .newWatermarkEstimator();
      }
      return new WatermarkEstimator<WatermarkEstimatorStateT>() {
        @Override
        public Instant currentWatermark() {
          return GlobalWindow.TIMESTAMP_MIN_VALUE;
        }

        @Override
        @SuppressWarnings("nullness") // WatermarkEstimatorStateT may or may not allow nullness
        // if it does not, then this method must be overridden in the DoFn
        public WatermarkEstimatorStateT getState() {
          return null;
        }
      };
    }
  }

  /** Default implementation of {@link DoFn.NewTracker}, for delegation by bytebuddy. */
  public static class DefaultNewTracker {
    /** Uses {@link HasDefaultTracker} to produce the tracker. */
    @SuppressWarnings("unused")
    public static <InputT, OutputT, RestrictionT, PositionT>
        RestrictionTracker<RestrictionT, PositionT> invokeNewTracker(
            DoFnInvoker.ArgumentProvider<InputT, OutputT> argumentProvider) {
      return ((HasDefaultTracker) argumentProvider.restriction()).newTracker();
    }
  }

  public static class DefaultGetSize {
    /** Uses {@link HasProgress} to produce the size. */
    @SuppressWarnings("unused")
    public static <InputT, OutputT> double invokeGetSize(
        DoFnInvoker.ArgumentProvider<InputT, OutputT> argumentProvider) {
      if (argumentProvider.restrictionTracker() instanceof HasProgress) {
        return ((HasProgress) argumentProvider.restrictionTracker())
            .getProgress()
            .getWorkRemaining();
      } else {
        return 0.0;
      }
    }

    public static double validateSize(double size) {
      if (size < 0) {
        throw new IllegalArgumentException(
            String.format("Expected size >= 0 but received %s.", size));
      }
      return size;
    }
  }

  /**
   * Generates a type suffix string for use in invoker class names.
   *
   * <p>This creates a unique suffix based on the input and output type descriptors to avoid class
   * name collisions when the same DoFn class is used with different generic types.
   *
   * <p>The format is: {@code DoFnInvoker$<8-digit hex hash>}
   *
   * @param inputType the input type descriptor
   * @param outputType the output type descriptor
   * @return a string suffix for the invoker class name
   */
  public static String generateTypeSuffix(
      TypeDescriptor<?> inputType, TypeDescriptor<?> outputType) {
    return String.format(
        "%s$%08x",
        DoFnInvoker.class.getSimpleName(),
        (inputType.toString() + "|" + outputType.toString()).hashCode());
  }

  /** Generates a {@link DoFnInvoker} class for the given {@link DoFnSignature}. */
  private static Class<? extends DoFnInvoker<?, ?>> generateInvokerClass(
      DoFnSignature signature, TypeDescriptor<?> inputType, TypeDescriptor<?> outputType) {
    Class<? extends DoFn<?, ?>> fnClass = signature.fnClass();

    // Create a unique suffix based on the type descriptors to avoid class name collisions
    // when the same DoFn class is used with different generic types.
    String typeSuffix = generateTypeSuffix(inputType, outputType);

    final TypeDescription clazzDescription = new TypeDescription.ForLoadedType(fnClass);

    DynamicType.Builder<?> builder =
        BYTE_BUDDY
            // Create subclasses inside the target class, to have access to
            // private and package-private bits
            .with(StableInvokerNamingStrategy.forDoFnClass(fnClass).withSuffix(typeSuffix))

            // class <invoker class> extends DoFnInvokerBase {
            .subclass(DoFnInvokerBase.class, ConstructorStrategy.Default.NO_CONSTRUCTORS)
            .modifiers(Visibility.PUBLIC, TypeManifestation.FINAL)

            //   private final <fn class> delegate;
            .defineField(
                FN_DELEGATE_FIELD_NAME, fnClass, Visibility.PRIVATE, FieldManifestation.FINAL)

            //   public <invoker class>(<fn class> delegate) { super(delegate); this.delegate =
            // delegate; }
            .defineConstructor(Visibility.PUBLIC)
            .withParameter(fnClass)
            .intercept(InvokerConstructor.INSTANCE)

            //   public invokeProcessElement(ProcessContext, ExtraContextFactory) {
            //     delegate.<@ProcessElement>(... pass just the right args ...);
            //   }
            .method(ElementMatchers.named("invokeProcessElement"))
            .intercept(new ProcessElementDelegation(clazzDescription, signature.processElement()))

            //   public invokeStartBundle(Context c) { delegate.<@StartBundle>(c); }
            //   ... etc ...
            .method(ElementMatchers.named("invokeStartBundle"))
            .intercept(
                delegateMethodWithExtraParametersOrNoop(clazzDescription, signature.startBundle()))
            .method(ElementMatchers.named("invokeFinishBundle"))
            .intercept(
                delegateMethodWithExtraParametersOrNoop(clazzDescription, signature.finishBundle()))
            .method(ElementMatchers.named("invokeSetup"))
            .intercept(delegateMethodWithExtraParametersOrNoop(clazzDescription, signature.setup()))
            .method(ElementMatchers.named("invokeTeardown"))
            .intercept(delegateOrNoop(clazzDescription, signature.teardown()))
            .method(ElementMatchers.named("invokeOnWindowExpiration"))
            .intercept(
                delegateMethodWithExtraParametersOrNoop(
                    clazzDescription, signature.onWindowExpiration()))
            .method(ElementMatchers.named("invokeGetInitialRestriction"))
            .intercept(
                delegateMethodWithExtraParametersOrThrow(
                    clazzDescription, signature.getInitialRestriction()))
            .method(ElementMatchers.named("invokeSplitRestriction"))
            .intercept(splitRestrictionDelegation(clazzDescription, signature.splitRestriction()))
            .method(ElementMatchers.named("invokeTruncateRestriction"))
            .intercept(
                truncateRestrictionDelegation(clazzDescription, signature.truncateRestriction()))
            .method(ElementMatchers.named("invokeGetRestrictionCoder"))
            .intercept(getRestrictionCoderDelegation(clazzDescription, signature))
            .method(ElementMatchers.named("invokeNewTracker"))
            .intercept(newTrackerDelegation(clazzDescription, signature.newTracker()))
            .method(ElementMatchers.named("invokeGetSize"))
            .intercept(getSizeDelegation(clazzDescription, signature.getSize()))
            .method(ElementMatchers.named("invokeGetWatermarkEstimatorStateCoder"))
            .intercept(getWatermarkEstimatorStateCoderDelegation(clazzDescription, signature))
            .method(ElementMatchers.named("invokeGetInitialWatermarkEstimatorState"))
            .intercept(
                getInitialWatermarkEstimatorStateDelegation(
                    clazzDescription, signature.getInitialWatermarkEstimatorState()))
            .method(ElementMatchers.named("invokeNewWatermarkEstimator"))
            .intercept(
                newWatermarkEstimatorDelegation(
                    clazzDescription, signature.newWatermarkEstimator()));

    boolean hasOnTimerMethods =
        signature.onTimerMethods() != null && !signature.onTimerMethods().isEmpty();
    boolean hasOnTimerFamilyMethods =
        signature.onTimerFamilyMethods() != null && !signature.onTimerFamilyMethods().isEmpty();
    if (hasOnTimerMethods || hasOnTimerFamilyMethods) {
      builder =
          builder
              .method(ElementMatchers.named("invokeOnTimer"))
              .intercept(new OnTimerMethodDelegation(signature));
    }

    builder =
        builder.visit(
            new AsmVisitorWrapper.ForDeclaredMethods()
                .writerFlags(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS));

    DynamicType.Unloaded<?> unloaded = builder.make();
    @SuppressWarnings("unchecked")
    Class<? extends DoFnInvoker<?, ?>> res =
        (Class<? extends DoFnInvoker<?, ?>>)
            unloaded
                .load(findClassLoader(fnClass.getClassLoader()), getClassLoadingStrategy(fnClass))
                .getLoaded();
    return res;
  }

  private static Implementation invokeStaticWithSingleArg(MethodDescription staticMethod) {
    return new Implementation.Simple(
        MethodVariableAccess.REFERENCE.loadFrom(1),
        MethodInvocation.invoke(staticMethod),
        MethodReturn.of(staticMethod.getReturnType().asErasure()));
  }

  private static Implementation getCoderFromRegistryDelegation(
      String constantName, TypeDescriptor<?> coderType) {
    return new Implementation.Simple(
        MethodVariableAccess.REFERENCE.loadFrom(1),
        ByteBuddyUtils.loadConstantDynamic(constantName, TypeDescriptor.class, coderType),
        MethodInvocation.invoke(CODER_REGISTRY_GET_CODER_METHOD),
        MethodReturn.REFERENCE);
  }

  private static Implementation getRestrictionCoderDelegation(
      TypeDescription doFnType, DoFnSignature signature) {
    if (signature.processElement().isSplittable()) {
      if (signature.getRestrictionCoder() == null) {
        checkStateNotNull(
            signature.getInitialRestriction(),
            "@GetRestrictionCoder provided but @GetInitialRestriction not provided:"
                + " please add @GetInitialRestriction");
        return getCoderFromRegistryDelegation(
            "restrictionType", signature.getInitialRestriction().restrictionT());
      } else {
        return new DowncastingParametersMethodDelegation(
            doFnType, signature.getRestrictionCoder().targetMethod());
      }
    } else {
      return ExceptionMethod.throwing(UnsupportedOperationException.class);
    }
  }

  private static Implementation getWatermarkEstimatorStateCoderDelegation(
      TypeDescription doFnType, DoFnSignature signature) {
    if (signature.processElement().isSplittable()) {
      if (signature.getWatermarkEstimatorStateCoder() == null) {
        TypeDescriptor<?> stateType =
            signature.getInitialWatermarkEstimatorState() == null
                ? TypeDescriptors.voids()
                : signature.getInitialWatermarkEstimatorState().watermarkEstimatorStateT();
        return getCoderFromRegistryDelegation("watermarkEstimatorStateType", stateType);
      } else {
        return new DowncastingParametersMethodDelegation(
            doFnType, signature.getWatermarkEstimatorStateCoder().targetMethod());
      }
    } else {
      return ExceptionMethod.throwing(UnsupportedOperationException.class);
    }
  }

  private static Implementation splitRestrictionDelegation(
      TypeDescription doFnType, DoFnSignature.@Nullable SplitRestrictionMethod signature) {
    if (signature == null) {
      return invokeStaticWithSingleArg(DEFAULT_SPLIT_RESTRICTION_METHOD);
    } else {
      return new DoFnMethodWithExtraParametersDelegation(doFnType, signature);
    }
  }

  private static Implementation truncateRestrictionDelegation(
      TypeDescription doFnType, DoFnSignature.@Nullable TruncateRestrictionMethod signature) {
    if (signature == null) {
      return invokeStaticWithSingleArg(DEFAULT_TRUNCATE_RESTRICTION_METHOD);
    } else {
      return new DoFnMethodWithExtraParametersDelegation(doFnType, signature);
    }
  }

  private static Implementation getInitialWatermarkEstimatorStateDelegation(
      TypeDescription doFnType,
      DoFnSignature.@Nullable GetInitialWatermarkEstimatorStateMethod signature) {
    if (signature == null) {
      return FixedValue.nullValue();
    } else {
      return new DoFnMethodWithExtraParametersDelegation(doFnType, signature);
    }
  }

  private static Implementation newWatermarkEstimatorDelegation(
      TypeDescription doFnType, DoFnSignature.@Nullable NewWatermarkEstimatorMethod signature) {
    if (signature == null) {
      return invokeStaticWithSingleArg(DEFAULT_NEW_WATERMARK_ESTIMATOR_METHOD);
    } else {
      return new DoFnMethodWithExtraParametersDelegation(doFnType, signature);
    }
  }

  private static Implementation newTrackerDelegation(
      TypeDescription doFnType, DoFnSignature.@Nullable NewTrackerMethod signature) {
    if (signature == null) {
      // We must have already verified that in this case the restriction type
      // is a subtype of HasDefaultTracker.
      return invokeStaticWithSingleArg(DEFAULT_NEW_TRACKER_METHOD);
    } else {
      return new DoFnMethodWithExtraParametersDelegation(doFnType, signature);
    }
  }

  private static Implementation getSizeDelegation(
      TypeDescription doFnType, DoFnSignature.@Nullable GetSizeMethod signature) {
    if (signature == null) {
      return invokeStaticWithSingleArg(DEFAULT_GET_SIZE_METHOD);
    } else {
      return new GetSizeDelegation(doFnType, signature);
    }
  }

  /** Delegates to the given method if available, or does nothing. */
  private static Implementation delegateOrNoop(
      TypeDescription doFnType, DoFnSignature.@Nullable DoFnMethod method) {
    return (method == null)
        ? FixedValue.originType()
        : new DoFnMethodDelegation(doFnType, method.targetMethod());
  }

  /** Delegates method with extra parameters to the given method if available, or does nothing. */
  private static Implementation delegateMethodWithExtraParametersOrNoop(
      TypeDescription doFnType, DoFnSignature.@Nullable MethodWithExtraParameters method) {
    return (method == null)
        ? FixedValue.originType()
        : new DoFnMethodWithExtraParametersDelegation(doFnType, method);
  }

  /** Delegates method with extra parameters to the given method if available, or throws. */
  private static Implementation delegateMethodWithExtraParametersOrThrow(
      TypeDescription doFnType, DoFnSignature.@Nullable MethodWithExtraParameters method) {
    return (method == null)
        ? ExceptionMethod.throwing(UnsupportedOperationException.class)
        : new DoFnMethodWithExtraParametersDelegation(doFnType, method);
  }

  private static boolean requiresDelegateParameter(DoFnSignature.Parameter parameter) {
    return !(parameter instanceof SchemaElementParameter
        || parameter instanceof BundleFinalizerParameter
        || parameter instanceof WindowParameter
        || parameter instanceof RestrictionParameter
        || parameter instanceof RestrictionTrackerParameter
        || parameter instanceof WatermarkEstimatorParameter
        || parameter instanceof WatermarkEstimatorStateParameter
        || parameter instanceof StateParameter
        || parameter instanceof TimerParameter
        || parameter instanceof TimerFamilyParameter
        || parameter instanceof DoFnSignature.Parameter.PipelineOptionsParameter
        || parameter instanceof SideInputParameter
        || parameter instanceof DoFnSignature.Parameter.KeyParameter);
  }

  static StackManipulation pushTargetAndExtraParameters(
      FieldDescription delegateField,
      List<DoFnSignature.Parameter> extraParams,
      int argumentProviderSlot,
      int delegateCacheSlot) {
    boolean cacheDelegateInLocal = false;
    for (DoFnSignature.Parameter param : extraParams) {
      if (requiresDelegateParameter(param)) {
        cacheDelegateInLocal = true;
        break;
      }
    }

    List<StackManipulation> manipulations = new ArrayList<>(extraParams.size() + 4);
    manipulations.add(MethodVariableAccess.REFERENCE.loadFrom(0));
    manipulations.add(FieldAccess.forField(delegateField).read());
    if (cacheDelegateInLocal) {
      manipulations.add(Duplication.SINGLE);
      manipulations.add(MethodVariableAccess.REFERENCE.storeAt(delegateCacheSlot));
    }

    StackManipulation pushDelegate = MethodVariableAccess.REFERENCE.loadFrom(delegateCacheSlot);
    StackManipulation pushExtraContextFactory =
        MethodVariableAccess.REFERENCE.loadFrom(argumentProviderSlot);

    for (DoFnSignature.Parameter param : extraParams) {
      manipulations.add(pushExtraContextFactory);
      manipulations.add(getExtraContextParameter(param, pushDelegate));
    }
    return new StackManipulation.Compound(manipulations);
  }

  /**
   * Implements a method of {@link DoFnInvoker} (the "instrumented method") by delegating to a
   * "target method" of the wrapped {@link DoFn}.
   */
  static class DoFnMethodDelegation implements Implementation {
    /** The {@link MethodDescription} of the wrapped {@link DoFn}'s method. */
    protected final MethodDescription targetMethod;

    /** Starts {@code null}, initialized by {@link #prepare(InstrumentedType)}. */
    protected @MonotonicNonNull FieldDescription delegateField;

    protected final TypeDescription doFnType;

    public DoFnMethodDelegation(TypeDescription doFnType, Method targetMethod) {
      this.doFnType = doFnType;
      this.targetMethod = new MethodDescription.ForLoadedMethod(targetMethod);
    }

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      // Remember the typed delegate field description of the instrumented invoker class.
      delegateField =
          instrumentedType
              .getDeclaredFields()
              .filter(ElementMatchers.named(FN_DELEGATE_FIELD_NAME))
              .getOnly();
      return instrumentedType;
    }

    @Override
    public ByteCodeAppender appender(final Target implementationTarget) {
      return new ByteCodeAppender() {
        /**
         * @param instrumentedMethod The {@link DoFnInvoker} method for which we're generating code.
         */
        @Override
        public ByteCodeAppender.Size apply(
            MethodVisitor methodVisitor,
            Context implementationContext,
            MethodDescription instrumentedMethod) {
          FieldDescription checkedDelegateField =
              checkStateNotNull(
                  delegateField,
                  "delegateField is null - must call prepare(...) before delegation");

          StackManipulation manipulation =
              new StackManipulation.Compound(
                  pushTargetAndArguments(checkedDelegateField, instrumentedMethod),
                  new UserCodeMethodInvocation(targetMethod, afterDelegation(instrumentedMethod)));

          StackManipulation.Size size = manipulation.apply(methodVisitor, implementationContext);
          return new ByteCodeAppender.Size(size.getMaximalSize(), numLocals(instrumentedMethod));
        }
      };
    }

    protected int numLocals(MethodDescription instrumentedMethod) {
      return 1 + instrumentedMethod.getParameters().size();
    }

    protected StackManipulation pushTargetAndArguments(
        FieldDescription checkedDelegateField, MethodDescription instrumentedMethod) {
      return new StackManipulation.Compound(
          MethodVariableAccess.REFERENCE.loadFrom(0),
          FieldAccess.forField(checkedDelegateField).read(),
          beforeDelegation(instrumentedMethod));
    }

    /**
     * Return the code to the prepare the operand stack for the method delegation.
     *
     * <p>Before this method is called, the stack delegate will be the only thing on the stack.
     *
     * <p>After this method is called, the stack contents should contain exactly the arguments
     * necessary to invoke the target method.
     */
    protected StackManipulation beforeDelegation(MethodDescription instrumentedMethod) {
      return MethodVariableAccess.allArgumentsOf(targetMethod);
    }

    /**
     * Return the code to execute after the method delegation.
     *
     * <p>Before this method is called, the stack will either be empty (if the target method returns
     * void) or contain the method return value.
     *
     * <p>After this method is called, the stack should either be empty (if the instrumented method
     * returns void) or contain the value for the instrumented method to return).
     */
    protected StackManipulation afterDelegation(MethodDescription instrumentedMethod) {
      return new StackManipulation.Compound(
          Assigner.DEFAULT.assign(
              targetMethod.getReturnType(), instrumentedMethod.getReturnType(), Typing.STATIC),
          MethodReturn.of(instrumentedMethod.getReturnType()));
    }
  }

  /**
   * Implements {@link DoFnInvoker}'s method with extra parameters by delegating to a "target
   * method" of the wrapped {@link DoFn}.
   */
  static class DoFnMethodWithExtraParametersDelegation extends DoFnMethodDelegation {
    private final DoFnSignature.MethodWithExtraParameters signature;

    public DoFnMethodWithExtraParametersDelegation(
        TypeDescription clazzDescription, DoFnSignature.MethodWithExtraParameters signature) {
      super(clazzDescription, signature.targetMethod());
      this.signature = signature;
    }

    @Override
    protected int numLocals(MethodDescription instrumentedMethod) {
      // Reserve an extra local slot (slot 2) in case `this.delegate` is cached in a local variable.
      return 2 + instrumentedMethod.getParameters().size();
    }

    @Override
    protected StackManipulation pushTargetAndArguments(
        FieldDescription checkedDelegateField, MethodDescription instrumentedMethod) {
      return pushTargetAndExtraParameters(checkedDelegateField, signature.extraParameters(), 1, 2);
    }
  }

  /**
   * Implements {@link DoFnInvoker#invokeOnTimer} directly inside the generated {@link DoFnInvoker}
   * class using conditional branches instead of per-instance {@link Map} lookups and separate
   * {@link OnTimerInvoker} instances.
   */
  private static final class OnTimerMethodDelegation implements Implementation {
    private final Map<String, OnTimerMethod> onTimerMethods;
    private final Map<String, OnTimerFamilyMethod> onTimerFamilyMethods;
    private @MonotonicNonNull FieldDescription delegateField;

    OnTimerMethodDelegation(DoFnSignature signature) {
      this.onTimerMethods =
          signature.onTimerMethods() != null ? signature.onTimerMethods() : Collections.emptyMap();
      this.onTimerFamilyMethods =
          signature.onTimerFamilyMethods() != null
              ? signature.onTimerFamilyMethods()
              : Collections.emptyMap();
    }

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      delegateField =
          instrumentedType
              .getDeclaredFields()
              .filter(ElementMatchers.named(FN_DELEGATE_FIELD_NAME))
              .getOnly();
      return instrumentedType;
    }

    @Override
    public ByteCodeAppender appender(final Target implementationTarget) {
      return (mv, implementationContext, instrumentedMethod) -> {
        FieldDescription checkedDelegateField =
            checkStateNotNull(
                delegateField, "delegateField is null - must call prepare(...) before delegation");

        Label timerFamilyDispatch = new Label();
        Label unhandledTimer = new Label();

        // Check timerFamilyId.isEmpty() (timerFamilyId is in slot 2).
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "isEmpty", "()Z", false);
        mv.visitJumpInsn(Opcodes.IFEQ, timerFamilyDispatch);

        // Dispatch @OnTimer methods by timerId (slot 1).
        for (OnTimerMethod onTimerMethod : onTimerMethods.values()) {
          emitTimerBranch(
              mv,
              implementationContext,
              instrumentedMethod,
              checkedDelegateField,
              onTimerMethod.id(),
              1,
              onTimerMethod);
        }
        mv.visitJumpInsn(Opcodes.GOTO, unhandledTimer);

        // Dispatch @OnTimerFamily methods by timerFamilyId (slot 2).
        mv.visitLabel(timerFamilyDispatch);
        for (OnTimerFamilyMethod onTimerFamilyMethod : onTimerFamilyMethods.values()) {
          emitTimerBranch(
              mv,
              implementationContext,
              instrumentedMethod,
              checkedDelegateField,
              onTimerFamilyMethod.id(),
              2,
              onTimerFamilyMethod);
        }

        // Fallback to DoFnInvokerBase.invokeOnTimer for unregistered timer IDs.
        mv.visitLabel(unhandledTimer);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            DOFN_INVOKER_BASE_INTERNAL_NAME,
            "invokeOnTimer",
            "(Ljava/lang/String;Ljava/lang/String;Lorg/apache/beam/sdk/transforms/reflect/DoFnInvoker$ArgumentProvider;)V",
            false);
        mv.visitInsn(Opcodes.RETURN);

        // ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES computes maxStack and maxLocals.
        return new ByteCodeAppender.Size(4, 5);
      };
    }

    private static void emitTimerBranch(
        MethodVisitor mv,
        Context implementationContext,
        MethodDescription instrumentedMethod,
        FieldDescription delegateField,
        String expectedId,
        int idSlot,
        DoFnSignature.MethodWithExtraParameters methodSignature) {
      Label nextBranch = new Label();
      mv.visitLdcInsn(expectedId);
      mv.visitVarInsn(Opcodes.ALOAD, idSlot);
      mv.visitMethodInsn(
          Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
      mv.visitJumpInsn(Opcodes.IFEQ, nextBranch);

      MethodDescription targetMethod =
          new MethodDescription.ForLoadedMethod(methodSignature.targetMethod());
      StackManipulation afterDelegation =
          new StackManipulation.Compound(
              Assigner.DEFAULT.assign(
                  targetMethod.getReturnType(), instrumentedMethod.getReturnType(), Typing.STATIC),
              MethodReturn.VOID);

      // In invokeOnTimer(String, String, ArgumentProvider), ArgumentProvider is slot 3 and
      // slot 4 is available to cache `this.delegate`.
      StackManipulation branchBody =
          new StackManipulation.Compound(
              pushTargetAndExtraParameters(delegateField, methodSignature.extraParameters(), 3, 4),
              new UserCodeMethodInvocation(targetMethod, afterDelegation));
      branchBody.apply(mv, implementationContext);

      mv.visitLabel(nextBranch);
    }
  }

  /**
   * Passes parameters to the delegated method by downcasting each parameter of non-primitive type
   * to its expected type.
   */
  private static class DowncastingParametersMethodDelegation extends DoFnMethodDelegation {
    DowncastingParametersMethodDelegation(TypeDescription doFnType, Method method) {
      super(doFnType, method);
    }

    @Override
    protected StackManipulation beforeDelegation(MethodDescription instrumentedMethod) {
      List<StackManipulation> pushParameters = new ArrayList<>();
      TypeList.Generic paramTypes = targetMethod.getParameters().asTypeList();
      for (int i = 0; i < paramTypes.size(); i++) {
        TypeDescription.Generic paramT = paramTypes.get(i);
        pushParameters.add(MethodVariableAccess.of(paramT).loadFrom(i + 1));
        if (!paramT.isPrimitive()) {
          pushParameters.add(TypeCasting.to(paramT));
        }
      }
      return new StackManipulation.Compound(pushParameters);
    }
  }

  /**
   * This wrapper exists to convert checked exceptions to unchecked exceptions, since if this fails
   * the library itself is malformed.
   */
  private static MethodDescription getExtraContextFactoryMethodDescription(
      String methodName, Class<?>... parameterTypes) {
    try {
      return new MethodDescription.ForLoadedMethod(
          DoFnInvoker.ArgumentProvider.class.getMethod(methodName, parameterTypes));
    } catch (Exception e) {
      throw new IllegalStateException(
          String.format(
              "Failed to locate required method %s.%s",
              DoFnInvoker.ArgumentProvider.class.getSimpleName(), methodName),
          e);
    }
  }

  /**
   * Calls a zero-parameter getter on the {@link DoFnInvoker.ArgumentProvider}, which must be on top
   * of the stack.
   */
  private static StackManipulation simpleExtraContextParameter(String methodName) {
    return MethodInvocation.invoke(getExtraContextFactoryMethodDescription(methodName));
  }

  static StackManipulation getExtraContextParameter(
      DoFnSignature.Parameter parameter, final StackManipulation pushDelegate) {

    return parameter.match(
        new Cases<StackManipulation>() {
          @Override
          public StackManipulation dispatch(StartBundleContextParameter p) {
            return new StackManipulation.Compound(
                pushDelegate,
                MethodInvocation.invoke(
                    getExtraContextFactoryMethodDescription(
                        START_BUNDLE_CONTEXT_PARAMETER_METHOD, DoFn.class)));
          }

          @Override
          public StackManipulation dispatch(FinishBundleContextParameter p) {
            return new StackManipulation.Compound(
                pushDelegate,
                MethodInvocation.invoke(
                    getExtraContextFactoryMethodDescription(
                        FINISH_BUNDLE_CONTEXT_PARAMETER_METHOD, DoFn.class)));
          }

          @Override
          public StackManipulation dispatch(ProcessContextParameter p) {
            return new StackManipulation.Compound(
                pushDelegate,
                MethodInvocation.invoke(
                    getExtraContextFactoryMethodDescription(
                        PROCESS_CONTEXT_PARAMETER_METHOD, DoFn.class)));
          }

          @Override
          public StackManipulation dispatch(ElementParameter p) {
            return new StackManipulation.Compound(
                pushDelegate,
                MethodInvocation.invoke(
                    getExtraContextFactoryMethodDescription(ELEMENT_PARAMETER_METHOD, DoFn.class)),
                TypeCasting.to(new TypeDescription.ForLoadedType(p.elementT().getRawType())));
          }

          @Override
          public StackManipulation dispatch(SchemaElementParameter p) {
            ForLoadedType elementType = new ForLoadedType(p.elementT().getRawType());
            ForLoadedType castType =
                elementType.isPrimitive()
                    ? new ForLoadedType(Primitives.wrap(p.elementT().getRawType()))
                    : elementType;

            StackManipulation stackManipulation =
                new StackManipulation.Compound(
                    IntegerConstant.forValue(p.index()),
                    MethodInvocation.invoke(
                        getExtraContextFactoryMethodDescription(
                            SCHEMA_ELEMENT_PARAMETER_METHOD, int.class)),
                    TypeCasting.to(castType));
            if (elementType.isPrimitive()) {
              stackManipulation =
                  new Compound(
                      stackManipulation,
                      Assigner.DEFAULT.assign(
                          elementType.asBoxed().asGenericType(),
                          elementType.asUnboxed().asGenericType(),
                          Typing.STATIC));
            }
            return stackManipulation;
          }

          @Override
          public StackManipulation dispatch(TimestampParameter p) {
            return new StackManipulation.Compound(
                pushDelegate,
                MethodInvocation.invoke(
                    getExtraContextFactoryMethodDescription(
                        TIMESTAMP_PARAMETER_METHOD, DoFn.class)));
          }

          @Override
          public StackManipulation dispatch(CausedByDrainParameter p) {
            return new StackManipulation.Compound(
                pushDelegate,
                MethodInvocation.invoke(
                    getExtraContextFactoryMethodDescription(
                        CAUSED_BY_DRAIN_PARAMETER_METHOD, DoFn.class)));
          }

          @Override
          public StackManipulation dispatch(ValueKindParameter p) {
            return new StackManipulation.Compound(
                pushDelegate,
                MethodInvocation.invoke(
                    getExtraContextFactoryMethodDescription(
                        VALUE_KIND_PARAMETER_METHOD, DoFn.class)));
          }

          @Override
          public StackManipulation dispatch(BundleFinalizerParameter p) {
            return simpleExtraContextParameter(BUNDLE_FINALIZER_PARAMETER_METHOD);
          }

          @Override
          public StackManipulation dispatch(TimeDomainParameter p) {
            return new StackManipulation.Compound(
                pushDelegate,
                MethodInvocation.invoke(
                    getExtraContextFactoryMethodDescription(
                        TIME_DOMAIN_PARAMETER_METHOD, DoFn.class)));
          }

          @Override
          public StackManipulation dispatch(OutputReceiverParameter p) {
            String method =
                p.isRowReceiver() ? OUTPUT_ROW_RECEIVER_METHOD : OUTPUT_PARAMETER_METHOD;
            return new StackManipulation.Compound(
                pushDelegate,
                MethodInvocation.invoke(
                    getExtraContextFactoryMethodDescription(method, DoFn.class)));
          }

          @Override
          public StackManipulation dispatch(TaggedOutputReceiverParameter p) {
            return new StackManipulation.Compound(
                pushDelegate,
                MethodInvocation.invoke(
                    getExtraContextFactoryMethodDescription(
                        TAGGED_OUTPUT_PARAMETER_METHOD, DoFn.class)));
          }

          @Override
          public StackManipulation dispatch(OnTimerContextParameter p) {
            return new StackManipulation.Compound(
                pushDelegate,
                MethodInvocation.invoke(
                    getExtraContextFactoryMethodDescription(
                        ON_TIMER_CONTEXT_PARAMETER_METHOD, DoFn.class)));
          }

          @Override
          public StackManipulation dispatch(
              DoFnSignature.Parameter.OnWindowExpirationContextParameter p) {
            return new StackManipulation.Compound(
                pushDelegate,
                MethodInvocation.invoke(
                    getExtraContextFactoryMethodDescription(
                        ON_WINDOW_EXPIRATION_CONTEXT_PARAMETER_METHOD, DoFn.class)));
          }

          @Override
          public StackManipulation dispatch(WindowParameter p) {
            return new StackManipulation.Compound(
                simpleExtraContextParameter(WINDOW_PARAMETER_METHOD),
                TypeCasting.to(new TypeDescription.ForLoadedType(p.windowT().getRawType())));
          }

          @Override
          public StackManipulation dispatch(PaneInfoParameter p) {
            return new StackManipulation.Compound(
                pushDelegate,
                MethodInvocation.invoke(
                    getExtraContextFactoryMethodDescription(
                        PANE_INFO_PARAMETER_METHOD, DoFn.class)));
          }

          @Override
          public StackManipulation dispatch(RestrictionParameter p) {
            // DoFnInvoker.ArgumentProvider.restriction() returns an Object,
            // but the methods expect a concrete subtype of it.
            // Insert a downcast.
            return new StackManipulation.Compound(
                simpleExtraContextParameter(RESTRICTION_PARAMETER_METHOD),
                TypeCasting.to(new TypeDescription.ForLoadedType(p.restrictionT().getRawType())));
          }

          @Override
          public StackManipulation dispatch(RestrictionTrackerParameter p) {
            // DoFnInvoker.ArgumentProvider.restrictionTracker() returns a RestrictionTracker,
            // but the methods expect a concrete subtype of it.
            // Insert a downcast.
            return new StackManipulation.Compound(
                simpleExtraContextParameter(RESTRICTION_TRACKER_PARAMETER_METHOD),
                TypeCasting.to(new TypeDescription.ForLoadedType(p.trackerT().getRawType())));
          }

          @Override
          public StackManipulation dispatch(WatermarkEstimatorParameter p) {
            // DoFnInvoker.ArgumentProvider.watermarkEstimator() returns a WatermarkEstimator,
            // but the methods expect a concrete subtype of it.
            // Insert a downcast.
            return new StackManipulation.Compound(
                simpleExtraContextParameter(WATERMARK_ESTIMATOR_PARAMETER_METHOD),
                TypeCasting.to(new TypeDescription.ForLoadedType(p.estimatorT().getRawType())));
          }

          @Override
          public StackManipulation dispatch(WatermarkEstimatorStateParameter p) {
            // DoFnInvoker.ArgumentProvider.watermarkEstimatorState() returns an Object,
            // but the methods expect a concrete subtype of it.
            // Insert a downcast.
            return new StackManipulation.Compound(
                simpleExtraContextParameter(WATERMARK_ESTIMATOR_STATE_PARAMETER_METHOD),
                TypeCasting.to(
                    new TypeDescription.ForLoadedType(p.estimatorStateT().getRawType())));
          }

          @Override
          public StackManipulation dispatch(StateParameter p) {
            return new StackManipulation.Compound(
                new TextConstant(p.referent().id()),
                IntegerConstant.forValue(p.alwaysFetched()),
                MethodInvocation.invoke(
                    getExtraContextFactoryMethodDescription(
                        STATE_PARAMETER_METHOD, String.class, boolean.class)),
                TypeCasting.to(
                    new TypeDescription.ForLoadedType(p.referent().stateType().getRawType())));
          }

          @Override
          public StackManipulation dispatch(TimerParameter p) {
            return new StackManipulation.Compound(
                new TextConstant(p.referent().id()),
                MethodInvocation.invoke(
                    getExtraContextFactoryMethodDescription(TIMER_PARAMETER_METHOD, String.class)),
                TypeCasting.to(new TypeDescription.ForLoadedType(Timer.class)));
          }

          @Override
          public StackManipulation dispatch(TimerFamilyParameter p) {
            return new StackManipulation.Compound(
                new TextConstant(p.referent().id()),
                MethodInvocation.invoke(
                    getExtraContextFactoryMethodDescription(
                        TIMER_FAMILY_PARAMETER_METHOD, String.class)),
                TypeCasting.to(new TypeDescription.ForLoadedType(TimerMap.class)));
          }

          @Override
          public StackManipulation dispatch(DoFnSignature.Parameter.PipelineOptionsParameter p) {
            return simpleExtraContextParameter(PIPELINE_OPTIONS_PARAMETER_METHOD);
          }

          @Override
          public StackManipulation dispatch(SideInputParameter p) {
            return new StackManipulation.Compound(
                new TextConstant(p.sideInputId()),
                MethodInvocation.invoke(
                    getExtraContextFactoryMethodDescription(
                        SIDE_INPUT_PARAMETER_METHOD, String.class)),
                TypeCasting.to(new TypeDescription.ForLoadedType(p.elementT().getRawType())));
          }

          @Override
          public StackManipulation dispatch(DoFnSignature.Parameter.TimerIdParameter p) {
            return new StackManipulation.Compound(
                pushDelegate,
                MethodInvocation.invoke(
                    getExtraContextFactoryMethodDescription(
                        TIMER_ID_PARAMETER_METHOD, DoFn.class)));
          }

          @Override
          public StackManipulation dispatch(CurrentRecordIdParameter p) {
            return new StackManipulation.Compound(
                pushDelegate,
                MethodInvocation.invoke(
                    getExtraContextFactoryMethodDescription(
                        CURRENT_RECORD_ID_PARAMETER_METHOD, DoFn.class)));
          }

          @Override
          public StackManipulation dispatch(CurrentRecordOffsetParameter p) {
            return new StackManipulation.Compound(
                pushDelegate,
                MethodInvocation.invoke(
                    getExtraContextFactoryMethodDescription(
                        CURRENT_RECORD_OFFSET_PARAMETER_METHOD, DoFn.class)));
          }

          @Override
          public StackManipulation dispatch(FireTimestampParameter p) {
            return new StackManipulation.Compound(
                pushDelegate,
                MethodInvocation.invoke(
                    getExtraContextFactoryMethodDescription(
                        FIRE_TIMESTAMP_PARAMETER_METHOD, DoFn.class)));
          }

          @Override
          public StackManipulation dispatch(DoFnSignature.Parameter.KeyParameter p) {
            return new StackManipulation.Compound(
                simpleExtraContextParameter(KEY_PARAMETER_METHOD),
                TypeCasting.to(new TypeDescription.ForLoadedType(p.keyT().getRawType())));
          }
        });
  }

  /**
   * Implements the invoker's {@link DoFnInvoker#invokeProcessElement} method by delegating to the
   * {@link ProcessElement} method.
   */
  private static final class ProcessElementDelegation
      extends DoFnMethodWithExtraParametersDelegation {

    /** Implementation for the {@link ProcessElement} method. */
    private ProcessElementDelegation(
        TypeDescription doFnType, DoFnSignature.ProcessElementMethod signature) {
      super(doFnType, signature);
    }

    @Override
    protected StackManipulation afterDelegation(MethodDescription instrumentedMethod) {
      if (TypeDescription.VOID.equals(targetMethod.getReturnType().asErasure())) {
        return new StackManipulation.Compound(
            LOAD_PROCESS_CONTINUATION_STOP, MethodReturn.REFERENCE);
      } else {
        return MethodReturn.of(targetMethod.getReturnType().asErasure());
      }
    }
  }

  /**
   * Implements the invoker's {@link DoFnInvoker#invokeGetSize} method by delegating to the {@link
   * GetSize} method.
   */
  private static final class GetSizeDelegation extends DoFnMethodWithExtraParametersDelegation {
    /** Implementation for the {@link GetSize} method. */
    private GetSizeDelegation(TypeDescription doFnType, DoFnSignature.GetSizeMethod signature) {
      super(doFnType, signature);
    }

    @Override
    protected StackManipulation afterDelegation(MethodDescription instrumentedMethod) {
      return new StackManipulation.Compound(
          MethodInvocation.invoke(VALIDATE_SIZE_METHOD), MethodReturn.DOUBLE);
    }
  }

  private static final class UserCodeMethodInvocation implements StackManipulation {

    private final MethodDescription targetMethod;
    private final StackManipulation afterDelegation;

    UserCodeMethodInvocation(MethodDescription targetMethod, StackManipulation afterDelegation) {
      this.targetMethod = targetMethod;
      this.afterDelegation = afterDelegation;
    }

    @Override
    public boolean isValid() {
      return true;
    }

    @Override
    public StackManipulation.Size apply(MethodVisitor mv, Context context) {
      Label tryBlockStart = new Label();
      Label tryBlockEnd = new Label();
      Label catchBlockStart = new Label();

      mv.visitTryCatchBlock(tryBlockStart, tryBlockEnd, catchBlockStart, THROWABLE_INTERNAL_NAME);

      // The try block invokes the target user method.
      mv.visitLabel(tryBlockStart);
      StackManipulation.Size size = MethodInvocation.invoke(targetMethod).apply(mv, context);
      mv.visitLabel(tryBlockEnd);

      // On normal completion, transform/return the result outside the try block.
      size = size.aggregate(afterDelegation.apply(mv, context));

      // The handler wraps the exception, and then throws.
      mv.visitLabel(catchBlockStart);
      size =
          size.aggregate(
              new Compound(MethodInvocation.invoke(CREATE_USER_CODE_EXCEPTION), Throw.INSTANCE)
                  .apply(mv, context));

      return size;
    }
  }

  /**
   * A constructor {@link Implementation} for a {@link DoFnInvoker class}. Produces the byte code
   * for a constructor that takes a single argument and assigns it to the super and subclass
   * delegate fields.
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
                    // super(delegate)
                    MethodVariableAccess.REFERENCE.loadFrom(0),
                    MethodVariableAccess.REFERENCE.loadFrom(1),
                    MethodInvocation.invoke(DOFN_INVOKER_BASE_CONSTRUCTOR),
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
