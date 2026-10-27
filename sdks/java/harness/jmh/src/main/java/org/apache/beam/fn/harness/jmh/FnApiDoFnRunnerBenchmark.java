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
package org.apache.beam.fn.harness.jmh;

import static org.apache.beam.sdk.values.WindowedValues.valueInGlobalWindow;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.beam.fn.harness.Caches;
import org.apache.beam.fn.harness.FnApiDoFnRunner;
import org.apache.beam.fn.harness.PTransformRunnerFactoryTestContext;
import org.apache.beam.fn.harness.state.BeamFnStateClient;
import org.apache.beam.model.fnexecution.v1.BeamFnApi.ProcessBundleRequest.CacheToken;
import org.apache.beam.model.fnexecution.v1.BeamFnApi.StateGetResponse;
import org.apache.beam.model.fnexecution.v1.BeamFnApi.StateRequest;
import org.apache.beam.model.fnexecution.v1.BeamFnApi.StateResponse;
import org.apache.beam.model.pipeline.v1.Endpoints.ApiServiceDescriptor;
import org.apache.beam.model.pipeline.v1.RunnerApi;
import org.apache.beam.model.pipeline.v1.RunnerApi.Environment;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.fn.data.BeamFnDataOutboundAggregator;
import org.apache.beam.sdk.fn.data.FnDataReceiver;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.state.StateSpec;
import org.apache.beam.sdk.state.StateSpecs;
import org.apache.beam.sdk.state.TimeDomain;
import org.apache.beam.sdk.state.Timer;
import org.apache.beam.sdk.state.TimerMap;
import org.apache.beam.sdk.state.TimerSpec;
import org.apache.beam.sdk.state.TimerSpecs;
import org.apache.beam.sdk.state.ValueState;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.GlobalWindow;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.util.ByteStringOutputStream;
import org.apache.beam.sdk.util.construction.PipelineTranslation;
import org.apache.beam.sdk.util.construction.SdkComponents;
import org.apache.beam.sdk.values.CausedByDrain;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.sdk.values.WindowedValue;
import org.apache.beam.sdk.values.WindowedValues;
import org.apache.beam.vendor.grpc.v1p69p0.com.google.protobuf.ByteString;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableList;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableMap;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.Iterables;
import org.joda.time.Instant;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * In-memory JMH microbenchmarks for {@link FnApiDoFnRunner} element and timer execution without
 * gRPC transport overhead.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@SuppressWarnings({"rawtypes", "unchecked", "nullness", "unused"})
public class FnApiDoFnRunnerBenchmark {

  private static final String TRANSFORM_ID = "benchTransform";
  private static final Schema INPUT_ROW_SCHEMA =
      Schema.builder().addStringField("id").addInt32Field("count").addDoubleField("score").build();

  public static class ProcessContextDoFn extends DoFn<KV<String, String>, String> {
    @ProcessElement
    public void processElement(ProcessContext c) {
      c.output(c.element().getKey());
    }
  }

  public static class MultiParamDoFn extends DoFn<KV<String, String>, String> {
    @ProcessElement
    public void processElement(
        @Element KV<String, String> element,
        @Timestamp Instant timestamp,
        BoundedWindow window,
        PaneInfo pane,
        CausedByDrain causedByDrain,
        OutputReceiver<String> out) {
      if (timestamp.getMillis() >= 0 && window != null && pane != null && causedByDrain != null) {
        out.output(element.getValue());
      }
    }
  }

  public static class StatefulAndTimerDoFn extends DoFn<KV<String, String>, String> {
    @StateId("value")
    private final StateSpec<ValueState<String>> valueStateSpec =
        StateSpecs.value(StringUtf8Coder.of());

    @TimerId("event")
    private final TimerSpec eventTimerSpec = TimerSpecs.timer(TimeDomain.EVENT_TIME);

    @TimerFamily("event-family")
    private final TimerSpec eventFamilySpec = TimerSpecs.timerMap(TimeDomain.EVENT_TIME);

    @ProcessElement
    public void processElement(
        @Element KV<String, String> element,
        @Timestamp Instant timestamp,
        @StateId("value") ValueState<String> state,
        @TimerId("event") Timer eventTimer,
        @TimerFamily("event-family") TimerMap timerFamily,
        OutputReceiver<String> out) {
      String current = state.read();
      if (eventTimer.getCurrentRelativeTime() != null && timerFamily != null) {
        out.output(current != null ? current : element.getKey());
      }
    }

    @OnTimer("event")
    public void onEventTimer(
        @Key String key,
        @Timestamp Instant ts,
        BoundedWindow window,
        @StateId("value") ValueState<String> state,
        @TimerId("event") Timer eventTimer,
        OutputReceiver<String> out) {
      String current = state.read();
      if (ts != null && window != null && eventTimer != null) {
        out.output(current != null ? current : key);
      }
    }

    @OnTimerFamily("event-family")
    public void onEventFamilyTimer(
        @TimerId String dynamicTimerId,
        @Key String key,
        @Timestamp Instant ts,
        BoundedWindow window,
        OutputReceiver<String> out) {
      if (ts != null && window != null) {
        out.output(dynamicTimerId.length() > 0 ? key : "");
      }
    }
  }

  public static class SchemaFieldAccessDoFn extends DoFn<Row, String> {
    @ProcessElement
    public void processElement(
        @FieldAccess("id") String id, @FieldAccess("count") int count, OutputReceiver<String> out) {
      if (count >= 0) {
        out.output(id);
      }
    }
  }

  private static final class NoOpOutboundAggregator extends BeamFnDataOutboundAggregator {
    NoOpOutboundAggregator() {
      super(PipelineOptionsFactory.create(), false);
    }

    @Override
    public <T> FnDataReceiver<T> registerOutputDataLocation(String pTransformId, Coder<T> coder) {
      return data -> {};
    }

    @Override
    public <T> FnDataReceiver<T> registerOutputTimersLocation(
        String pTransformId, String timerFamilyId, Coder<T> coder) {
      return data -> {};
    }
  }

  private static class RunnerHarness<InputT> {
    final PTransformRunnerFactoryTestContext context;
    FnDataReceiver<WindowedValue<?>> mainInput;
    WindowedValue<String> lastOutput;

    RunnerHarness(PTransformRunnerFactoryTestContext context) {
      this.context = context;
    }
  }

  @State(Scope.Benchmark)
  public static class BenchmarkState {
    private static final WindowedValue<KV<String, String>> KV_ELEMENT_GLOBAL =
        valueInGlobalWindow(KV.of("key-1", "val-1"));
    private static final WindowedValue<KV<String, String>> KV_ELEMENT_WINDOWED =
        WindowedValues.of(
            KV.of("key-1", "val-1"),
            new Instant(1000L),
            new IntervalWindow(new Instant(0L), new Instant(10000L)),
            PaneInfo.NO_FIRING);
    private static final WindowedValue<Row> ROW_ELEMENT_GLOBAL =
        valueInGlobalWindow(
            Row.withSchema(INPUT_ROW_SCHEMA)
                .withFieldValue("id", "row-1")
                .withFieldValue("count", 42)
                .withFieldValue("score", 3.14)
                .build());
    private static final org.apache.beam.sdk.util.construction.Timer<String> TIMER_GLOBAL =
        org.apache.beam.sdk.util.construction.Timer.of(
            "key-1",
            "",
            Collections.singletonList(GlobalWindow.INSTANCE),
            new Instant(2000L),
            new Instant(1000L),
            PaneInfo.NO_FIRING,
            CausedByDrain.NORMAL);
    private static final org.apache.beam.sdk.util.construction.Timer<String> DYNAMIC_TIMER_GLOBAL =
        org.apache.beam.sdk.util.construction.Timer.of(
            "key-1",
            "dyn-tag-1",
            Collections.singletonList(GlobalWindow.INSTANCE),
            new Instant(2000L),
            new Instant(1000L),
            PaneInfo.NO_FIRING,
            CausedByDrain.NORMAL);

    RunnerHarness<KV<String, String>> processContextHarness;
    RunnerHarness<KV<String, String>> multiParamHarness;
    RunnerHarness<KV<String, String>> statefulHarness;
    RunnerHarness<Row> schemaFieldAccessHarness;
    FnDataReceiver<org.apache.beam.sdk.util.construction.Timer<String>> timerReceiver;
    FnDataReceiver<org.apache.beam.sdk.util.construction.Timer<String>> timerFamilyReceiver;

    @Setup
    public void setUp() throws Exception {
      this.processContextHarness = buildKvRunner(new ProcessContextDoFn());
      this.multiParamHarness = buildKvRunner(new MultiParamDoFn());
      this.statefulHarness = buildKvRunner(new StatefulAndTimerDoFn());
      this.schemaFieldAccessHarness = buildRowRunner(new SchemaFieldAccessDoFn());
      this.timerReceiver =
          (FnDataReceiver)
              statefulHarness.context.getIncomingTimerEndpoint("ts-event").getReceiver();
      this.timerFamilyReceiver =
          (FnDataReceiver)
              statefulHarness.context.getIncomingTimerEndpoint("tfs-event-family").getReceiver();
    }

    @TearDown
    public void tearDown() throws Exception {
      for (RunnerHarness<?> harness :
          ImmutableList.of(
              processContextHarness,
              multiParamHarness,
              statefulHarness,
              schemaFieldAccessHarness)) {
        Iterables.getOnlyElement(harness.context.getFinishBundleFunctions()).run();
        Iterables.getOnlyElement(harness.context.getTearDownFunctions()).run();
      }
    }

    private static RunnerHarness<KV<String, String>> buildKvRunner(
        DoFn<KV<String, String>, String> doFn) throws Exception {
      Pipeline p = Pipeline.create();
      PCollection<KV<String, String>> input =
          p.apply(
              Create.of(KV.of("key-1", "val-1"))
                  .withCoder(KvCoder.of(StringUtf8Coder.of(), StringUtf8Coder.of())));
      PCollection<String> output = input.apply(TRANSFORM_ID, ParDo.of(doFn));
      return createHarness(p, input, output);
    }

    private static RunnerHarness<Row> buildRowRunner(DoFn<Row, String> doFn) throws Exception {
      Pipeline p = Pipeline.create();
      PCollection<Row> input =
          p.apply(Create.of(ROW_ELEMENT_GLOBAL.getValue()).withRowSchema(INPUT_ROW_SCHEMA));
      PCollection<String> output = input.apply(TRANSFORM_ID, ParDo.of(doFn));
      return createHarness(p, input, output);
    }

    private static <InputT> RunnerHarness<InputT> createHarness(
        Pipeline p, PCollection<InputT> input, PCollection<String> output) throws Exception {
      SdkComponents sdkComponents = SdkComponents.create(p.getOptions());
      sdkComponents.registerEnvironment(Environment.getDefaultInstance());
      RunnerApi.Pipeline pProto = PipelineTranslation.toProto(p, sdkComponents);
      String inputId = sdkComponents.registerPCollection(input);
      String outputId = sdkComponents.registerPCollection(output);

      RunnerApi.PTransform topTransform = pProto.getComponents().getTransformsOrThrow(TRANSFORM_ID);
      RunnerApi.PTransform parDoTransform =
          topTransform.getSubtransformsCount() > 0
              ? pProto.getComponents().getTransformsOrThrow(topTransform.getSubtransforms(0))
              : topTransform;

      ByteStringOutputStream encodedStateStream = new ByteStringOutputStream();
      StringUtf8Coder.of().encode("cached-state-val", encodedStateStream);
      ByteString encodedStateBytes = encodedStateStream.toByteString();
      StateResponse cachedStateResponse =
          StateResponse.newBuilder()
              .setGet(StateGetResponse.newBuilder().setData(encodedStateBytes))
              .build();
      BeamFnStateClient fastStateClient =
          (StateRequest.Builder requestBuilder) ->
              CompletableFuture.completedFuture(
                  cachedStateResponse.toBuilder().setId(requestBuilder.getId()).build());

      CacheToken userStateToken =
          CacheToken.newBuilder()
              .setUserState(CacheToken.UserState.getDefaultInstance())
              .setToken(ByteString.copyFromUtf8("bench-token"))
              .build();

      PTransformRunnerFactoryTestContext context =
          PTransformRunnerFactoryTestContext.builder(TRANSFORM_ID, parDoTransform)
              .beamFnStateClient(fastStateClient)
              .processBundleInstructionId("bench-bundle")
              .cacheTokensSupplier(() -> Collections.singletonList(userStateToken))
              .bundleCacheSupplier(Caches::eternal)
              .processWideCache(Caches.eternal())
              .components(
                  RunnerApi.Components.newBuilder()
                      .putAllCoders(pProto.getComponents().getCodersMap())
                      .putAllEnvironments(Collections.emptyMap())
                      .putAllWindowingStrategies(pProto.getComponents().getWindowingStrategiesMap())
                      .putAllPcollections(pProto.getComponents().getPcollectionsMap())
                      .build())
              .outboundAggregators(
                  ImmutableMap.of(
                      ApiServiceDescriptor.getDefaultInstance(), new NoOpOutboundAggregator()))
              .timerApiServiceDescriptor(ApiServiceDescriptor.getDefaultInstance())
              .build();

      RunnerHarness<InputT> harness = new RunnerHarness<>(context);
      context.addPCollectionConsumer(
          outputId,
          (FnDataReceiver) (FnDataReceiver<WindowedValue<String>>) v -> harness.lastOutput = v);

      new FnApiDoFnRunner.Registrar()
          .getPTransformRunnerFactories()
          .get(org.apache.beam.sdk.util.construction.PTransformTranslation.PAR_DO_TRANSFORM_URN)
          .addRunnerForPTransform(context);
      Iterables.getOnlyElement(context.getStartBundleFunctions()).run();

      harness.mainInput = context.getPCollectionConsumer(inputId);
      return harness;
    }
  }

  @Benchmark
  public WindowedValue<String> processElementWithProcessContext(BenchmarkState state)
      throws Exception {
    state.processContextHarness.mainInput.accept(BenchmarkState.KV_ELEMENT_GLOBAL);
    return state.processContextHarness.lastOutput;
  }

  @Benchmark
  public WindowedValue<String> processElementWithMultiParams(BenchmarkState state)
      throws Exception {
    state.multiParamHarness.mainInput.accept(BenchmarkState.KV_ELEMENT_WINDOWED);
    return state.multiParamHarness.lastOutput;
  }

  @Benchmark
  public WindowedValue<String> processStatefulAndTimerElement(BenchmarkState state)
      throws Exception {
    state.statefulHarness.mainInput.accept(BenchmarkState.KV_ELEMENT_GLOBAL);
    return state.statefulHarness.lastOutput;
  }

  @Benchmark
  public WindowedValue<String> processTimer(BenchmarkState state) throws Exception {
    state.timerReceiver.accept(BenchmarkState.TIMER_GLOBAL);
    return state.statefulHarness.lastOutput;
  }

  @Benchmark
  public WindowedValue<String> processTimerFamily(BenchmarkState state) throws Exception {
    state.timerFamilyReceiver.accept(BenchmarkState.DYNAMIC_TIMER_GLOBAL);
    return state.statefulHarness.lastOutput;
  }

  @Benchmark
  public WindowedValue<String> processSchemaFieldAccessElement(BenchmarkState state)
      throws Exception {
    state.schemaFieldAccessHarness.mainInput.accept(BenchmarkState.ROW_ELEMENT_GLOBAL);
    return state.schemaFieldAccessHarness.lastOutput;
  }
}
