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
package org.apache.beam.fn.harness;

import static org.apache.beam.sdk.util.ByteBuddyUtils.getClassLoadingStrategy;
import static org.apache.beam.sdk.util.Preconditions.checkStateNotNull;
import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions.checkArgument;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.VisibilityBridgeStrategy;
import net.bytebuddy.dynamic.scaffold.InstrumentedType;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.Implementation.Context;
import net.bytebuddy.implementation.bytecode.ByteCodeAppender;
import net.bytebuddy.implementation.bytecode.Removal;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.StackManipulation.Compound;
import net.bytebuddy.implementation.bytecode.Throw;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.implementation.bytecode.assign.Assigner.Typing;
import net.bytebuddy.implementation.bytecode.assign.TypeCasting;
import net.bytebuddy.implementation.bytecode.collection.ArrayAccess;
import net.bytebuddy.implementation.bytecode.constant.IntegerConstant;
import net.bytebuddy.implementation.bytecode.member.FieldAccess;
import net.bytebuddy.implementation.bytecode.member.MethodInvocation;
import net.bytebuddy.implementation.bytecode.member.MethodReturn;
import net.bytebuddy.implementation.bytecode.member.MethodVariableAccess;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatchers;
import org.apache.beam.sdk.annotations.Internal;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature;
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
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.KeyParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.OnTimerContextParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.OnWindowExpirationContextParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.OutputReceiverParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.PaneInfoParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.PipelineOptionsParameter;
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
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.TimerIdParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.TimerParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.TimestampParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.ValueKindParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.WatermarkEstimatorParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.WatermarkEstimatorStateParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.Parameter.WindowParameter;
import org.apache.beam.sdk.transforms.reflect.DoFnSignatures;
import org.apache.beam.sdk.transforms.reflect.StableInvokerNamingStrategy;
import org.apache.beam.sdk.util.UserCodeException;
import org.apache.beam.sdk.util.common.ReflectHelpers;
import org.apache.beam.sdk.util.construction.Timer;
import org.apache.beam.sdk.values.WindowedValue;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.primitives.Primitives;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Dynamically generates a specialized {@link FnApiDoFnInvoker} subclass for executing a {@link
 * DoFn} inside {@link FnApiDoFnRunner} without {@link
 * org.apache.beam.sdk.transforms.reflect.DoFnInvoker.ArgumentProvider} interface dispatch or string
 * map lookups on the element and timer hot paths.
 */
@Internal
@SuppressWarnings("rawtypes")
public final class ByteBuddyFnApiDoFnInvokerFactory {

  /**
   * Harness-specialized invoker for hot-path {@link DoFn} methods ({@link DoFn.ProcessElement},
   * {@link DoFn.OnTimer}, {@link DoFn.OnTimerFamily}, and {@link DoFn.OnWindowExpiration}).
   */
  public abstract static class FnApiDoFnInvoker {
    public abstract void invokeProcessElement();

    public abstract void invokeOnTimer(String timerId, String timerFamilyId);

    public abstract void invokeOnWindowExpiration();

    @SuppressWarnings("DoNotCallSuggester")
    public static void unhandledTimer(DoFn<?, ?> delegate, String timerId, String timerFamilyId) {
      throw new IllegalArgumentException(
          String.format(
              "Attempted to invoke timer %s in timer family %s on %s, but that timer is not"
                  + " registered",
              timerId, timerFamilyId, delegate.getClass().getName()));
    }
  }

  private static final String FN_DELEGATE_FIELD_NAME = "delegate";
  private static final String RUNNER_FIELD_NAME = "runner";

  private static final ByteBuddy BYTE_BUDDY =
      new ByteBuddy(ClassFileVersion.JAVA_V11)
          .with(TypeValidation.DISABLED)
          .with(Implementation.Context.Disabled.Factory.INSTANCE)
          .with(VisibilityBridgeStrategy.Default.NEVER);

  private static final String THROWABLE_INTERNAL_NAME =
      TypeDescription.ForLoadedType.of(Throwable.class).getInternalName();
  private static final String FN_API_DOFN_INVOKER_INTERNAL_NAME =
      TypeDescription.ForLoadedType.of(FnApiDoFnInvoker.class).getInternalName();

  private static final Constructor<?> INVOKER_BASE_CONSTRUCTOR;
  private static final MethodDescription CREATE_USER_CODE_EXCEPTION;

  private static final FieldDescription RUNNER_CURRENT_ELEMENT_FIELD;
  private static final FieldDescription RUNNER_CURRENT_WINDOW_FIELD;
  private static final FieldDescription RUNNER_CURRENT_TIMER_FIELD;
  private static final FieldDescription RUNNER_CURRENT_TIME_DOMAIN_FIELD;
  private static final FieldDescription RUNNER_CURRENT_RESTRICTION_FIELD;
  private static final FieldDescription RUNNER_CURRENT_TRACKER_FIELD;
  private static final FieldDescription RUNNER_CURRENT_WATERMARK_ESTIMATOR_FIELD;
  private static final FieldDescription RUNNER_CURRENT_WATERMARK_ESTIMATOR_STATE_FIELD;
  private static final FieldDescription RUNNER_PIPELINE_OPTIONS_FIELD;
  private static final FieldDescription RUNNER_BUNDLE_FINALIZER_FIELD;
  private static final FieldDescription RUNNER_PROCESS_CONTEXT_FIELD;
  private static final FieldDescription RUNNER_PROCESS_ELEMENT_TAGGED_OUTPUT_FIELD;
  private static final FieldDescription RUNNER_ON_TIMER_CONTEXT_FIELD;
  private static final FieldDescription RUNNER_ON_TIMER_OUTPUT_RECEIVER_FIELD;
  private static final FieldDescription RUNNER_ON_TIMER_TAGGED_OUTPUT_FIELD;
  private static final FieldDescription RUNNER_ON_WINDOW_EXPIRATION_CONTEXT_FIELD;
  private static final FieldDescription RUNNER_ON_WINDOW_EXPIRATION_OUTPUT_RECEIVER_FIELD;
  private static final FieldDescription RUNNER_ON_WINDOW_EXPIRATION_TAGGED_OUTPUT_FIELD;
  private static final FieldDescription RUNNER_TIMER_FAMILIES_FIELD;

  private static final MethodDescription RUNNER_BIND_STATE_METHOD;
  private static final MethodDescription RUNNER_TIMER_FOR_ELEMENT_METHOD;
  private static final MethodDescription RUNNER_TIMER_FOR_ON_TIMER_METHOD;
  private static final MethodDescription RUNNER_SCHEMA_ELEMENT_METHOD;
  private static final MethodDescription RUNNER_SIDE_INPUT_METHOD;
  private static final MethodDescription RUNNER_PROCESS_ELEMENT_ROW_RECEIVER_METHOD;
  private static final MethodDescription RUNNER_ON_TIMER_ROW_RECEIVER_METHOD;
  private static final MethodDescription RUNNER_ON_WINDOW_EXPIRATION_ROW_RECEIVER_METHOD;

  private static final MethodDescription WINDOWED_VALUE_GET_VALUE_METHOD;
  private static final MethodDescription WINDOWED_VALUE_GET_TIMESTAMP_METHOD;
  private static final MethodDescription WINDOWED_VALUE_GET_PANE_INFO_METHOD;
  private static final MethodDescription WINDOWED_VALUE_CAUSED_BY_DRAIN_METHOD;
  private static final MethodDescription WINDOWED_VALUE_GET_VALUE_KIND_METHOD;
  private static final MethodDescription WINDOWED_VALUE_GET_RECORD_ID_METHOD;
  private static final MethodDescription WINDOWED_VALUE_GET_RECORD_OFFSET_METHOD;

  private static final MethodDescription TIMER_GET_USER_KEY_METHOD;
  private static final MethodDescription TIMER_GET_HOLD_TIMESTAMP_METHOD;
  private static final MethodDescription TIMER_GET_FIRE_TIMESTAMP_METHOD;
  private static final MethodDescription TIMER_GET_DYNAMIC_TIMER_TAG_METHOD;
  private static final MethodDescription TIMER_CAUSED_BY_DRAIN_METHOD;

  static {
    try {
      INVOKER_BASE_CONSTRUCTOR = FnApiDoFnInvoker.class.getDeclaredConstructor();
      CREATE_USER_CODE_EXCEPTION =
          new MethodDescription.ForLoadedMethod(
              UserCodeException.class.getDeclaredMethod("wrap", Throwable.class));

      RUNNER_CURRENT_ELEMENT_FIELD =
          new FieldDescription.ForLoadedField(FnApiDoFnRunner.class.getField("currentElement"));
      RUNNER_CURRENT_WINDOW_FIELD =
          new FieldDescription.ForLoadedField(FnApiDoFnRunner.class.getField("currentWindow"));
      RUNNER_CURRENT_TIMER_FIELD =
          new FieldDescription.ForLoadedField(FnApiDoFnRunner.class.getField("currentTimer"));
      RUNNER_CURRENT_TIME_DOMAIN_FIELD =
          new FieldDescription.ForLoadedField(FnApiDoFnRunner.class.getField("currentTimeDomain"));
      RUNNER_CURRENT_RESTRICTION_FIELD =
          new FieldDescription.ForLoadedField(FnApiDoFnRunner.class.getField("currentRestriction"));
      RUNNER_CURRENT_TRACKER_FIELD =
          new FieldDescription.ForLoadedField(FnApiDoFnRunner.class.getField("currentTracker"));
      RUNNER_CURRENT_WATERMARK_ESTIMATOR_FIELD =
          new FieldDescription.ForLoadedField(
              FnApiDoFnRunner.class.getField("currentWatermarkEstimator"));
      RUNNER_CURRENT_WATERMARK_ESTIMATOR_STATE_FIELD =
          new FieldDescription.ForLoadedField(
              FnApiDoFnRunner.class.getField("currentWatermarkEstimatorState"));
      RUNNER_PIPELINE_OPTIONS_FIELD =
          new FieldDescription.ForLoadedField(FnApiDoFnRunner.class.getField("pipelineOptions"));
      RUNNER_BUNDLE_FINALIZER_FIELD =
          new FieldDescription.ForLoadedField(FnApiDoFnRunner.class.getField("bundleFinalizer"));
      RUNNER_PROCESS_CONTEXT_FIELD =
          new FieldDescription.ForLoadedField(FnApiDoFnRunner.class.getField("processContext"));
      RUNNER_PROCESS_ELEMENT_TAGGED_OUTPUT_FIELD =
          new FieldDescription.ForLoadedField(
              FnApiDoFnRunner.class.getField("processElementTaggedOutputReceiver"));
      RUNNER_ON_TIMER_CONTEXT_FIELD =
          new FieldDescription.ForLoadedField(
              FnApiDoFnRunner.class.getField("onTimerContextInstance"));
      RUNNER_ON_TIMER_OUTPUT_RECEIVER_FIELD =
          new FieldDescription.ForLoadedField(
              FnApiDoFnRunner.class.getField("onTimerOutputReceiver"));
      RUNNER_ON_TIMER_TAGGED_OUTPUT_FIELD =
          new FieldDescription.ForLoadedField(
              FnApiDoFnRunner.class.getField("onTimerTaggedOutputReceiver"));
      RUNNER_ON_WINDOW_EXPIRATION_CONTEXT_FIELD =
          new FieldDescription.ForLoadedField(
              FnApiDoFnRunner.class.getField("onWindowExpirationContextInstance"));
      RUNNER_ON_WINDOW_EXPIRATION_OUTPUT_RECEIVER_FIELD =
          new FieldDescription.ForLoadedField(
              FnApiDoFnRunner.class.getField("onWindowExpirationOutputReceiver"));
      RUNNER_ON_WINDOW_EXPIRATION_TAGGED_OUTPUT_FIELD =
          new FieldDescription.ForLoadedField(
              FnApiDoFnRunner.class.getField("onWindowExpirationTaggedOutputReceiver"));
      RUNNER_TIMER_FAMILIES_FIELD =
          new FieldDescription.ForLoadedField(FnApiDoFnRunner.class.getField("timerFamilies"));

      RUNNER_BIND_STATE_METHOD =
          new MethodDescription.ForLoadedMethod(
              FnApiDoFnRunner.class.getMethod("bindState", int.class, boolean.class));
      RUNNER_TIMER_FOR_ELEMENT_METHOD =
          new MethodDescription.ForLoadedMethod(
              FnApiDoFnRunner.class.getMethod("timerForElement", int.class));
      RUNNER_TIMER_FOR_ON_TIMER_METHOD =
          new MethodDescription.ForLoadedMethod(
              FnApiDoFnRunner.class.getMethod("timerForOnTimer", int.class));
      RUNNER_SCHEMA_ELEMENT_METHOD =
          new MethodDescription.ForLoadedMethod(
              FnApiDoFnRunner.class.getMethod("schemaElement", int.class));
      RUNNER_SIDE_INPUT_METHOD =
          new MethodDescription.ForLoadedMethod(
              FnApiDoFnRunner.class.getMethod("sideInput", int.class));
      RUNNER_PROCESS_ELEMENT_ROW_RECEIVER_METHOD =
          new MethodDescription.ForLoadedMethod(
              FnApiDoFnRunner.class.getMethod("processElementRowReceiver"));
      RUNNER_ON_TIMER_ROW_RECEIVER_METHOD =
          new MethodDescription.ForLoadedMethod(
              FnApiDoFnRunner.class.getMethod("onTimerRowReceiver"));
      RUNNER_ON_WINDOW_EXPIRATION_ROW_RECEIVER_METHOD =
          new MethodDescription.ForLoadedMethod(
              FnApiDoFnRunner.class.getMethod("onWindowExpirationRowReceiver"));

      WINDOWED_VALUE_GET_VALUE_METHOD =
          new MethodDescription.ForLoadedMethod(WindowedValue.class.getMethod("getValue"));
      WINDOWED_VALUE_GET_TIMESTAMP_METHOD =
          new MethodDescription.ForLoadedMethod(WindowedValue.class.getMethod("getTimestamp"));
      WINDOWED_VALUE_GET_PANE_INFO_METHOD =
          new MethodDescription.ForLoadedMethod(WindowedValue.class.getMethod("getPaneInfo"));
      WINDOWED_VALUE_CAUSED_BY_DRAIN_METHOD =
          new MethodDescription.ForLoadedMethod(WindowedValue.class.getMethod("causedByDrain"));
      WINDOWED_VALUE_GET_VALUE_KIND_METHOD =
          new MethodDescription.ForLoadedMethod(WindowedValue.class.getMethod("getValueKind"));
      WINDOWED_VALUE_GET_RECORD_ID_METHOD =
          new MethodDescription.ForLoadedMethod(WindowedValue.class.getMethod("getRecordId"));
      WINDOWED_VALUE_GET_RECORD_OFFSET_METHOD =
          new MethodDescription.ForLoadedMethod(WindowedValue.class.getMethod("getRecordOffset"));

      TIMER_GET_USER_KEY_METHOD =
          new MethodDescription.ForLoadedMethod(Timer.class.getMethod("getUserKey"));
      TIMER_GET_HOLD_TIMESTAMP_METHOD =
          new MethodDescription.ForLoadedMethod(Timer.class.getMethod("getHoldTimestamp"));
      TIMER_GET_FIRE_TIMESTAMP_METHOD =
          new MethodDescription.ForLoadedMethod(Timer.class.getMethod("getFireTimestamp"));
      TIMER_GET_DYNAMIC_TIMER_TAG_METHOD =
          new MethodDescription.ForLoadedMethod(Timer.class.getMethod("getDynamicTimerTag"));
      TIMER_CAUSED_BY_DRAIN_METHOD =
          new MethodDescription.ForLoadedMethod(Timer.class.getMethod("causedByDrain"));
    } catch (NoSuchMethodException | NoSuchFieldException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static final ConcurrentHashMap<
          Class<? extends DoFn<?, ?>>, Constructor<? extends FnApiDoFnInvoker>>
      CONSTRUCTOR_CACHE = new ConcurrentHashMap<>();

  private ByteBuddyFnApiDoFnInvokerFactory() {}

  public static FnApiDoFnInvoker forDoFn(DoFn<?, ?> fn, FnApiDoFnRunner<?, ?, ?, ?, ?> runner) {
    @SuppressWarnings("unchecked")
    Class<? extends DoFn<?, ?>> fnClass = (Class<? extends DoFn<?, ?>>) fn.getClass();
    Constructor<? extends FnApiDoFnInvoker> constructor =
        CONSTRUCTOR_CACHE.computeIfAbsent(
            fnClass,
            cls -> {
              DoFnSignature signature = DoFnSignatures.getSignature(cls);
              Class<? extends FnApiDoFnInvoker> invokerClass = generateInvokerClass(signature);
              try {
                return invokerClass.getConstructor(cls, FnApiDoFnRunner.class);
              } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
              }
            });
    try {
      return constructor.newInstance(fn, runner);
    } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Returns the unique {@code sideInputId}s referenced across all methods of the given {@link
   * DoFnSignature} in deterministic declaration order.
   */
  public static List<String> getOrderedSideInputIds(DoFnSignature signature) {
    Set<String> sideInputIds = new LinkedHashSet<>();
    collectSideInputIds(signature.processElement(), sideInputIds);
    collectSideInputIds(signature.onWindowExpiration(), sideInputIds);
    if (signature.onTimerMethods() != null) {
      for (OnTimerMethod method : signature.onTimerMethods().values()) {
        collectSideInputIds(method, sideInputIds);
      }
    }
    if (signature.onTimerFamilyMethods() != null) {
      for (OnTimerFamilyMethod method : signature.onTimerFamilyMethods().values()) {
        collectSideInputIds(method, sideInputIds);
      }
    }
    return new ArrayList<>(sideInputIds);
  }

  private static void collectSideInputIds(
      DoFnSignature.@Nullable MethodWithExtraParameters method, Set<String> sideInputIds) {
    if (method == null) {
      return;
    }
    for (DoFnSignature.Parameter param : method.extraParameters()) {
      if (param instanceof SideInputParameter) {
        sideInputIds.add(((SideInputParameter) param).sideInputId());
      }
    }
  }

  private static Map<String, Integer> buildIndexMap(Iterable<String> keys) {
    Map<String, Integer> map = new HashMap<>();
    int index = 0;
    for (String key : keys) {
      map.put(key, index++);
    }
    return map;
  }

  private enum MethodKind {
    PROCESS_ELEMENT,
    ON_TIMER,
    ON_WINDOW_EXPIRATION
  }

  private static final class SignatureIndices {
    final Map<String, Integer> stateIndices;
    final Map<String, Integer> timerIndices;
    final Map<String, Integer> timerFamilyIndices;
    final Map<String, Integer> sideInputIndices;

    SignatureIndices(DoFnSignature signature) {
      this.stateIndices = buildIndexMap(signature.stateDeclarations().keySet());
      this.timerIndices = buildIndexMap(signature.timerDeclarations().keySet());
      this.timerFamilyIndices = buildIndexMap(signature.timerFamilyDeclarations().keySet());
      this.sideInputIndices = buildIndexMap(getOrderedSideInputIds(signature));
    }
  }

  private static Class<? extends FnApiDoFnInvoker> generateInvokerClass(DoFnSignature signature) {
    Class<? extends DoFn<?, ?>> fnClass = signature.fnClass();
    SignatureIndices indices = new SignatureIndices(signature);

    DynamicType.Builder<?> builder =
        BYTE_BUDDY
            .with(
                StableInvokerNamingStrategy.forDoFnClass(fnClass)
                    .withSuffix(FnApiDoFnInvoker.class.getSimpleName()))
            .subclass(FnApiDoFnInvoker.class, ConstructorStrategy.Default.NO_CONSTRUCTORS)
            .modifiers(Visibility.PUBLIC, TypeManifestation.FINAL)
            .defineField(
                FN_DELEGATE_FIELD_NAME, fnClass, Visibility.PRIVATE, FieldManifestation.FINAL)
            .defineField(
                RUNNER_FIELD_NAME,
                FnApiDoFnRunner.class,
                Visibility.PRIVATE,
                FieldManifestation.FINAL)
            .defineConstructor(Visibility.PUBLIC)
            .withParameters(fnClass, FnApiDoFnRunner.class)
            .intercept(InvokerConstructor.INSTANCE)
            .method(ElementMatchers.named("invokeProcessElement"))
            .intercept(
                new DirectMethodDelegation(
                    signature.processElement(), MethodKind.PROCESS_ELEMENT, indices))
            .method(ElementMatchers.named("invokeOnWindowExpiration"))
            .intercept(
                signature.onWindowExpiration() == null
                    ? FixedValue.originType()
                    : new DirectMethodDelegation(
                        signature.onWindowExpiration(), MethodKind.ON_WINDOW_EXPIRATION, indices))
            .method(ElementMatchers.named("invokeOnTimer"))
            .intercept(new DirectOnTimerDelegation(signature, indices))
            .visit(
                new AsmVisitorWrapper.ForDeclaredMethods()
                    .writerFlags(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS));

    ClassLoader classLoader = ReflectHelpers.findClassLoader(fnClass.getClassLoader());
    @SuppressWarnings("unchecked")
    Class<? extends FnApiDoFnInvoker> loaded =
        (Class<? extends FnApiDoFnInvoker>)
            builder.make().load(classLoader, getClassLoadingStrategy(fnClass)).getLoaded();
    return loaded;
  }

  private static boolean readsCurrentElement(DoFnSignature.Parameter param, MethodKind methodKind) {
    if (methodKind != MethodKind.PROCESS_ELEMENT) {
      return false;
    }
    return param instanceof ElementParameter
        || param instanceof TimestampParameter
        || param instanceof PaneInfoParameter
        || param instanceof CausedByDrainParameter
        || param instanceof ValueKindParameter
        || param instanceof CurrentRecordIdParameter
        || param instanceof CurrentRecordOffsetParameter;
  }

  private static boolean readsCurrentTimer(DoFnSignature.Parameter param, MethodKind methodKind) {
    if (methodKind == MethodKind.PROCESS_ELEMENT) {
      return false;
    }
    return param instanceof KeyParameter
        || param instanceof TimestampParameter
        || param instanceof FireTimestampParameter
        || param instanceof TimerIdParameter
        || param instanceof CausedByDrainParameter;
  }

  private static StackManipulation buildMethodBody(
      FieldDescription delegateField,
      FieldDescription runnerField,
      DoFnSignature.MethodWithExtraParameters methodSignature,
      MethodKind methodKind,
      SignatureIndices indices,
      int runnerLocalSlot,
      int elemOrTimerLocalSlot) {
    List<DoFnSignature.Parameter> extraParams = methodSignature.extraParameters();
    int elementReads = 0;
    int timerReads = 0;
    for (DoFnSignature.Parameter param : extraParams) {
      if (readsCurrentElement(param, methodKind)) {
        elementReads++;
      } else if (readsCurrentTimer(param, methodKind)) {
        timerReads++;
      }
    }

    boolean cacheRunnerInLocal = extraParams.size() >= 2;
    boolean cacheElementInLocal = elementReads >= 2;
    boolean cacheTimerInLocal = timerReads >= 2;

    List<StackManipulation> setupAndArgs = new ArrayList<>(extraParams.size() + 8);

    StackManipulation readRunnerFromField =
        new Compound(
            MethodVariableAccess.REFERENCE.loadFrom(0), FieldAccess.forField(runnerField).read());
    final StackManipulation loadRunner;
    if (cacheRunnerInLocal) {
      setupAndArgs.add(readRunnerFromField);
      setupAndArgs.add(MethodVariableAccess.REFERENCE.storeAt(runnerLocalSlot));
      loadRunner = MethodVariableAccess.REFERENCE.loadFrom(runnerLocalSlot);
    } else {
      loadRunner = readRunnerFromField;
    }

    final StackManipulation loadCurrentElement;
    if (cacheElementInLocal) {
      setupAndArgs.add(loadRunner);
      setupAndArgs.add(FieldAccess.forField(RUNNER_CURRENT_ELEMENT_FIELD).read());
      setupAndArgs.add(MethodVariableAccess.REFERENCE.storeAt(elemOrTimerLocalSlot));
      loadCurrentElement = MethodVariableAccess.REFERENCE.loadFrom(elemOrTimerLocalSlot);
    } else {
      loadCurrentElement =
          new Compound(loadRunner, FieldAccess.forField(RUNNER_CURRENT_ELEMENT_FIELD).read());
    }

    final StackManipulation loadCurrentTimer;
    if (cacheTimerInLocal) {
      setupAndArgs.add(loadRunner);
      setupAndArgs.add(FieldAccess.forField(RUNNER_CURRENT_TIMER_FIELD).read());
      setupAndArgs.add(MethodVariableAccess.REFERENCE.storeAt(elemOrTimerLocalSlot));
      loadCurrentTimer = MethodVariableAccess.REFERENCE.loadFrom(elemOrTimerLocalSlot);
    } else {
      loadCurrentTimer =
          new Compound(loadRunner, FieldAccess.forField(RUNNER_CURRENT_TIMER_FIELD).read());
    }

    // Push `this.delegate`.
    setupAndArgs.add(MethodVariableAccess.REFERENCE.loadFrom(0));
    setupAndArgs.add(FieldAccess.forField(delegateField).read());

    // Push all method arguments.
    for (DoFnSignature.Parameter param : extraParams) {
      setupAndArgs.add(
          pushParameter(
              param, methodKind, indices, loadRunner, loadCurrentElement, loadCurrentTimer));
    }

    MethodDescription targetMethod =
        new MethodDescription.ForLoadedMethod(methodSignature.targetMethod());
    StackManipulation afterDelegation =
        TypeDescription.VOID.equals(targetMethod.getReturnType().asErasure())
            ? MethodReturn.VOID
            : new Compound(Removal.of(targetMethod.getReturnType()), MethodReturn.VOID);

    return new Compound(
        new Compound(setupAndArgs), new UserCodeMethodInvocation(targetMethod, afterDelegation));
  }

  private static StackManipulation pushParameter(
      DoFnSignature.Parameter parameter,
      MethodKind methodKind,
      SignatureIndices indices,
      StackManipulation loadRunner,
      StackManipulation loadCurrentElement,
      StackManipulation loadCurrentTimer) {
    return parameter.match(
        new Cases<StackManipulation>() {
          @Override
          public StackManipulation dispatch(StartBundleContextParameter p) {
            throw new UnsupportedOperationException(
                "StartBundleContextParameter is not supported in FnApiDoFnInvoker");
          }

          @Override
          public StackManipulation dispatch(FinishBundleContextParameter p) {
            throw new UnsupportedOperationException(
                "FinishBundleContextParameter is not supported in FnApiDoFnInvoker");
          }

          @Override
          public StackManipulation dispatch(ProcessContextParameter p) {
            return new Compound(
                loadRunner, FieldAccess.forField(RUNNER_PROCESS_CONTEXT_FIELD).read());
          }

          @Override
          public StackManipulation dispatch(ElementParameter p) {
            return new Compound(
                loadCurrentElement,
                MethodInvocation.invoke(WINDOWED_VALUE_GET_VALUE_METHOD),
                TypeCasting.to(new ForLoadedType(p.elementT().getRawType())));
          }

          @Override
          public StackManipulation dispatch(SchemaElementParameter p) {
            ForLoadedType elementType = new ForLoadedType(p.elementT().getRawType());
            ForLoadedType castType =
                elementType.isPrimitive()
                    ? new ForLoadedType(Primitives.wrap(p.elementT().getRawType()))
                    : elementType;

            StackManipulation manipulation =
                new Compound(
                    loadRunner,
                    IntegerConstant.forValue(p.index()),
                    MethodInvocation.invoke(RUNNER_SCHEMA_ELEMENT_METHOD),
                    TypeCasting.to(castType));
            if (elementType.isPrimitive()) {
              manipulation =
                  new Compound(
                      manipulation,
                      Assigner.DEFAULT.assign(
                          elementType.asBoxed().asGenericType(),
                          elementType.asUnboxed().asGenericType(),
                          Typing.STATIC));
            }
            return manipulation;
          }

          @Override
          public StackManipulation dispatch(TimestampParameter p) {
            if (methodKind == MethodKind.PROCESS_ELEMENT) {
              return new Compound(
                  loadCurrentElement, MethodInvocation.invoke(WINDOWED_VALUE_GET_TIMESTAMP_METHOD));
            } else {
              return new Compound(
                  loadCurrentTimer, MethodInvocation.invoke(TIMER_GET_HOLD_TIMESTAMP_METHOD));
            }
          }

          @Override
          public StackManipulation dispatch(CausedByDrainParameter p) {
            if (methodKind == MethodKind.PROCESS_ELEMENT) {
              return new Compound(
                  loadCurrentElement,
                  MethodInvocation.invoke(WINDOWED_VALUE_CAUSED_BY_DRAIN_METHOD));
            } else {
              return new Compound(
                  loadCurrentTimer, MethodInvocation.invoke(TIMER_CAUSED_BY_DRAIN_METHOD));
            }
          }

          @Override
          public StackManipulation dispatch(ValueKindParameter p) {
            return new Compound(
                loadCurrentElement, MethodInvocation.invoke(WINDOWED_VALUE_GET_VALUE_KIND_METHOD));
          }

          @Override
          public StackManipulation dispatch(BundleFinalizerParameter p) {
            return new Compound(
                loadRunner, FieldAccess.forField(RUNNER_BUNDLE_FINALIZER_FIELD).read());
          }

          @Override
          public StackManipulation dispatch(TimeDomainParameter p) {
            return new Compound(
                loadRunner, FieldAccess.forField(RUNNER_CURRENT_TIME_DOMAIN_FIELD).read());
          }

          @Override
          public StackManipulation dispatch(OutputReceiverParameter p) {
            if (p.isRowReceiver()) {
              MethodDescription rowReceiverMethod;
              switch (methodKind) {
                case PROCESS_ELEMENT:
                  rowReceiverMethod = RUNNER_PROCESS_ELEMENT_ROW_RECEIVER_METHOD;
                  break;
                case ON_TIMER:
                  rowReceiverMethod = RUNNER_ON_TIMER_ROW_RECEIVER_METHOD;
                  break;
                case ON_WINDOW_EXPIRATION:
                  rowReceiverMethod = RUNNER_ON_WINDOW_EXPIRATION_ROW_RECEIVER_METHOD;
                  break;
                default:
                  throw new IllegalStateException("Unexpected method kind: " + methodKind);
              }
              return new Compound(loadRunner, MethodInvocation.invoke(rowReceiverMethod));
            }
            FieldDescription receiverField;
            switch (methodKind) {
              case PROCESS_ELEMENT:
                receiverField = RUNNER_PROCESS_CONTEXT_FIELD;
                break;
              case ON_TIMER:
                receiverField = RUNNER_ON_TIMER_OUTPUT_RECEIVER_FIELD;
                break;
              case ON_WINDOW_EXPIRATION:
                receiverField = RUNNER_ON_WINDOW_EXPIRATION_OUTPUT_RECEIVER_FIELD;
                break;
              default:
                throw new IllegalStateException("Unexpected method kind: " + methodKind);
            }
            return new Compound(loadRunner, FieldAccess.forField(receiverField).read());
          }

          @Override
          public StackManipulation dispatch(TaggedOutputReceiverParameter p) {
            FieldDescription taggedField;
            switch (methodKind) {
              case PROCESS_ELEMENT:
                taggedField = RUNNER_PROCESS_ELEMENT_TAGGED_OUTPUT_FIELD;
                break;
              case ON_TIMER:
                taggedField = RUNNER_ON_TIMER_TAGGED_OUTPUT_FIELD;
                break;
              case ON_WINDOW_EXPIRATION:
                taggedField = RUNNER_ON_WINDOW_EXPIRATION_TAGGED_OUTPUT_FIELD;
                break;
              default:
                throw new IllegalStateException("Unexpected method kind: " + methodKind);
            }
            return new Compound(loadRunner, FieldAccess.forField(taggedField).read());
          }

          @Override
          public StackManipulation dispatch(OnTimerContextParameter p) {
            return new Compound(
                loadRunner, FieldAccess.forField(RUNNER_ON_TIMER_CONTEXT_FIELD).read());
          }

          @Override
          public StackManipulation dispatch(OnWindowExpirationContextParameter p) {
            return new Compound(
                loadRunner, FieldAccess.forField(RUNNER_ON_WINDOW_EXPIRATION_CONTEXT_FIELD).read());
          }

          @Override
          public StackManipulation dispatch(WindowParameter p) {
            return new Compound(
                loadRunner,
                FieldAccess.forField(RUNNER_CURRENT_WINDOW_FIELD).read(),
                TypeCasting.to(new ForLoadedType(p.windowT().getRawType())));
          }

          @Override
          public StackManipulation dispatch(PaneInfoParameter p) {
            return new Compound(
                loadCurrentElement, MethodInvocation.invoke(WINDOWED_VALUE_GET_PANE_INFO_METHOD));
          }

          @Override
          public StackManipulation dispatch(RestrictionParameter p) {
            return new Compound(
                loadRunner,
                FieldAccess.forField(RUNNER_CURRENT_RESTRICTION_FIELD).read(),
                TypeCasting.to(new ForLoadedType(p.restrictionT().getRawType())));
          }

          @Override
          public StackManipulation dispatch(RestrictionTrackerParameter p) {
            return new Compound(
                loadRunner,
                FieldAccess.forField(RUNNER_CURRENT_TRACKER_FIELD).read(),
                TypeCasting.to(new ForLoadedType(p.trackerT().getRawType())));
          }

          @Override
          public StackManipulation dispatch(WatermarkEstimatorParameter p) {
            return new Compound(
                loadRunner,
                FieldAccess.forField(RUNNER_CURRENT_WATERMARK_ESTIMATOR_FIELD).read(),
                TypeCasting.to(new ForLoadedType(p.estimatorT().getRawType())));
          }

          @Override
          public StackManipulation dispatch(WatermarkEstimatorStateParameter p) {
            return new Compound(
                loadRunner,
                FieldAccess.forField(RUNNER_CURRENT_WATERMARK_ESTIMATOR_STATE_FIELD).read(),
                TypeCasting.to(new ForLoadedType(p.estimatorStateT().getRawType())));
          }

          @Override
          public StackManipulation dispatch(StateParameter p) {
            Integer stateIndex = indices.stateIndices.get(p.referent().id());
            checkArgument(stateIndex != null, "Unknown state id: %s", p.referent().id());
            return new Compound(
                loadRunner,
                IntegerConstant.forValue(stateIndex),
                IntegerConstant.forValue(p.alwaysFetched()),
                MethodInvocation.invoke(RUNNER_BIND_STATE_METHOD),
                TypeCasting.to(new ForLoadedType(p.referent().stateType().getRawType())));
          }

          @Override
          public StackManipulation dispatch(TimerParameter p) {
            Integer timerIndex = indices.timerIndices.get(p.referent().id());
            checkArgument(timerIndex != null, "Unknown timer id: %s", p.referent().id());
            MethodDescription timerMethod =
                methodKind == MethodKind.PROCESS_ELEMENT
                    ? RUNNER_TIMER_FOR_ELEMENT_METHOD
                    : RUNNER_TIMER_FOR_ON_TIMER_METHOD;
            return new Compound(
                loadRunner,
                IntegerConstant.forValue(timerIndex),
                MethodInvocation.invoke(timerMethod));
          }

          @Override
          public StackManipulation dispatch(TimerFamilyParameter p) {
            Integer familyIndex = indices.timerFamilyIndices.get(p.referent().id());
            checkArgument(familyIndex != null, "Unknown timer family id: %s", p.referent().id());
            return new Compound(
                loadRunner,
                FieldAccess.forField(RUNNER_TIMER_FAMILIES_FIELD).read(),
                IntegerConstant.forValue(familyIndex),
                ArrayAccess.REFERENCE.load());
          }

          @Override
          public StackManipulation dispatch(PipelineOptionsParameter p) {
            return new Compound(
                loadRunner, FieldAccess.forField(RUNNER_PIPELINE_OPTIONS_FIELD).read());
          }

          @Override
          public StackManipulation dispatch(SideInputParameter p) {
            Integer sideInputIndex = indices.sideInputIndices.get(p.sideInputId());
            checkArgument(sideInputIndex != null, "Unknown side input id: %s", p.sideInputId());
            return new Compound(
                loadRunner,
                IntegerConstant.forValue(sideInputIndex),
                MethodInvocation.invoke(RUNNER_SIDE_INPUT_METHOD),
                TypeCasting.to(new ForLoadedType(p.elementT().getRawType())));
          }

          @Override
          public StackManipulation dispatch(TimerIdParameter p) {
            return new Compound(
                loadCurrentTimer, MethodInvocation.invoke(TIMER_GET_DYNAMIC_TIMER_TAG_METHOD));
          }

          @Override
          public StackManipulation dispatch(CurrentRecordIdParameter p) {
            return new Compound(
                loadCurrentElement, MethodInvocation.invoke(WINDOWED_VALUE_GET_RECORD_ID_METHOD));
          }

          @Override
          public StackManipulation dispatch(CurrentRecordOffsetParameter p) {
            return new Compound(
                loadCurrentElement,
                MethodInvocation.invoke(WINDOWED_VALUE_GET_RECORD_OFFSET_METHOD));
          }

          @Override
          public StackManipulation dispatch(FireTimestampParameter p) {
            return new Compound(
                loadCurrentTimer, MethodInvocation.invoke(TIMER_GET_FIRE_TIMESTAMP_METHOD));
          }

          @Override
          public StackManipulation dispatch(KeyParameter p) {
            return new Compound(
                loadCurrentTimer,
                MethodInvocation.invoke(TIMER_GET_USER_KEY_METHOD),
                TypeCasting.to(new ForLoadedType(p.keyT().getRawType())));
          }
        });
  }

  private static final class DirectMethodDelegation implements Implementation {
    private final DoFnSignature.MethodWithExtraParameters methodSignature;
    private final MethodKind methodKind;
    private final SignatureIndices indices;
    private @MonotonicNonNull FieldDescription delegateField;
    private @MonotonicNonNull FieldDescription runnerField;

    DirectMethodDelegation(
        DoFnSignature.MethodWithExtraParameters methodSignature,
        MethodKind methodKind,
        SignatureIndices indices) {
      this.methodSignature = methodSignature;
      this.methodKind = methodKind;
      this.indices = indices;
    }

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      delegateField =
          instrumentedType
              .getDeclaredFields()
              .filter(ElementMatchers.named(FN_DELEGATE_FIELD_NAME))
              .getOnly();
      runnerField =
          instrumentedType
              .getDeclaredFields()
              .filter(ElementMatchers.named(RUNNER_FIELD_NAME))
              .getOnly();
      return instrumentedType;
    }

    @Override
    public ByteCodeAppender appender(final Target implementationTarget) {
      return (mv, implementationContext, instrumentedMethod) -> {
        FieldDescription checkedDelegateField = checkStateNotNull(delegateField);
        FieldDescription checkedRunnerField = checkStateNotNull(runnerField);

        StackManipulation body =
            buildMethodBody(
                checkedDelegateField,
                checkedRunnerField,
                methodSignature,
                methodKind,
                indices,
                1,
                2);
        StackManipulation.Size size = body.apply(mv, implementationContext);
        return new ByteCodeAppender.Size(size.getMaximalSize(), 3);
      };
    }
  }

  private static final class DirectOnTimerDelegation implements Implementation {
    private final Map<String, OnTimerMethod> onTimerMethods;
    private final Map<String, OnTimerFamilyMethod> onTimerFamilyMethods;
    private final SignatureIndices indices;
    private @MonotonicNonNull FieldDescription delegateField;
    private @MonotonicNonNull FieldDescription runnerField;

    DirectOnTimerDelegation(DoFnSignature signature, SignatureIndices indices) {
      this.onTimerMethods =
          signature.onTimerMethods() != null ? signature.onTimerMethods() : Collections.emptyMap();
      this.onTimerFamilyMethods =
          signature.onTimerFamilyMethods() != null
              ? signature.onTimerFamilyMethods()
              : Collections.emptyMap();
      this.indices = indices;
    }

    @Override
    public InstrumentedType prepare(InstrumentedType instrumentedType) {
      delegateField =
          instrumentedType
              .getDeclaredFields()
              .filter(ElementMatchers.named(FN_DELEGATE_FIELD_NAME))
              .getOnly();
      runnerField =
          instrumentedType
              .getDeclaredFields()
              .filter(ElementMatchers.named(RUNNER_FIELD_NAME))
              .getOnly();
      return instrumentedType;
    }

    @Override
    public ByteCodeAppender appender(final Target implementationTarget) {
      return (mv, implementationContext, instrumentedMethod) -> {
        FieldDescription checkedDelegateField = checkStateNotNull(delegateField);
        FieldDescription checkedRunnerField = checkStateNotNull(runnerField);

        Label timerFamilyDispatch = new Label();
        Label unhandledTimer = new Label();

        if (!onTimerMethods.isEmpty() || !onTimerFamilyMethods.isEmpty()) {
          // Check timerFamilyId.isEmpty() (timerFamilyId is in slot 2).
          mv.visitVarInsn(Opcodes.ALOAD, 2);
          mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "isEmpty", "()Z", false);
          mv.visitJumpInsn(Opcodes.IFEQ, timerFamilyDispatch);

          for (OnTimerMethod onTimerMethod : onTimerMethods.values()) {
            emitTimerBranch(
                mv,
                implementationContext,
                checkedDelegateField,
                checkedRunnerField,
                onTimerMethod.id(),
                1,
                onTimerMethod,
                indices);
          }
          mv.visitJumpInsn(Opcodes.GOTO, unhandledTimer);

          mv.visitLabel(timerFamilyDispatch);
          for (OnTimerFamilyMethod onTimerFamilyMethod : onTimerFamilyMethods.values()) {
            emitTimerBranch(
                mv,
                implementationContext,
                checkedDelegateField,
                checkedRunnerField,
                onTimerFamilyMethod.id(),
                2,
                onTimerFamilyMethod,
                indices);
          }
        }

        mv.visitLabel(unhandledTimer);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        FieldAccess.forField(checkedDelegateField).read().apply(mv, implementationContext);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            FN_API_DOFN_INVOKER_INTERNAL_NAME,
            "unhandledTimer",
            "(Lorg/apache/beam/sdk/transforms/DoFn;Ljava/lang/String;Ljava/lang/String;)V",
            false);
        mv.visitInsn(Opcodes.RETURN);

        return new ByteCodeAppender.Size(4, 5);
      };
    }

    private static void emitTimerBranch(
        MethodVisitor mv,
        Context implementationContext,
        FieldDescription delegateField,
        FieldDescription runnerField,
        String expectedId,
        int idSlot,
        DoFnSignature.MethodWithExtraParameters methodSignature,
        SignatureIndices indices) {
      Label nextBranch = new Label();
      mv.visitLdcInsn(expectedId);
      mv.visitVarInsn(Opcodes.ALOAD, idSlot);
      mv.visitMethodInsn(
          Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
      mv.visitJumpInsn(Opcodes.IFEQ, nextBranch);

      // Slots 0, 1, 2 are (this, timerId, timerFamilyId); use slots 3 and 4 for runner & timer.
      StackManipulation branchBody =
          buildMethodBody(
              delegateField, runnerField, methodSignature, MethodKind.ON_TIMER, indices, 3, 4);
      branchBody.apply(mv, implementationContext);

      mv.visitLabel(nextBranch);
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

      mv.visitLabel(tryBlockStart);
      StackManipulation.Size size = MethodInvocation.invoke(targetMethod).apply(mv, context);
      mv.visitLabel(tryBlockEnd);

      size = size.aggregate(afterDelegation.apply(mv, context));

      mv.visitLabel(catchBlockStart);
      size =
          size.aggregate(
              new Compound(MethodInvocation.invoke(CREATE_USER_CODE_EXCEPTION), Throw.INSTANCE)
                  .apply(mv, context));

      return size;
    }
  }

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
      FieldDescription runnerField =
          implementationTarget
              .getInstrumentedType()
              .getDeclaredFields()
              .filter(ElementMatchers.named(RUNNER_FIELD_NAME))
              .getOnly();
      return (methodVisitor, implementationContext, instrumentedMethod) -> {
        StackManipulation.Size size =
            new StackManipulation.Compound(
                    MethodVariableAccess.REFERENCE.loadFrom(0),
                    MethodInvocation.invoke(
                        new MethodDescription.ForLoadedConstructor(INVOKER_BASE_CONSTRUCTOR)),
                    MethodVariableAccess.REFERENCE.loadFrom(0),
                    MethodVariableAccess.REFERENCE.loadFrom(1),
                    FieldAccess.forField(delegateField).write(),
                    MethodVariableAccess.REFERENCE.loadFrom(0),
                    MethodVariableAccess.REFERENCE.loadFrom(2),
                    FieldAccess.forField(runnerField).write(),
                    MethodReturn.VOID)
                .apply(methodVisitor, implementationContext);
        return new ByteCodeAppender.Size(size.getMaximalSize(), instrumentedMethod.getStackSize());
      };
    }
  }
}
