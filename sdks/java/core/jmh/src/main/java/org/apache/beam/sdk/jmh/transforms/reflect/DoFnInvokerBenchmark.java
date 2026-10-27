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
package org.apache.beam.sdk.jmh.transforms.reflect;

import java.util.Collection;
import java.util.concurrent.TimeUnit;
import org.apache.beam.sdk.coders.VarLongCoder;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.state.StateSpec;
import org.apache.beam.sdk.state.StateSpecs;
import org.apache.beam.sdk.state.TimeDomain;
import org.apache.beam.sdk.state.Timer;
import org.apache.beam.sdk.state.TimerSpec;
import org.apache.beam.sdk.state.TimerSpecs;
import org.apache.beam.sdk.state.ValueState;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.DoFn.OutputReceiver;
import org.apache.beam.sdk.transforms.reflect.DoFnInvoker;
import org.apache.beam.sdk.transforms.reflect.DoFnInvokers;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.TimerDeclaration;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature.TimerFamilyDeclaration;
import org.apache.beam.sdk.transforms.splittabledofn.HasDefaultTracker;
import org.apache.beam.sdk.transforms.splittabledofn.HasDefaultWatermarkEstimator;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker.HasProgress;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker.Progress;
import org.apache.beam.sdk.transforms.splittabledofn.SplitResult;
import org.apache.beam.sdk.transforms.splittabledofn.WatermarkEstimator;
import org.apache.beam.sdk.transforms.splittabledofn.WatermarkEstimators;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.GlobalWindow;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.values.CausedByDrain;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.OutputBuilder;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TupleTag;
import org.joda.time.Duration;
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
import org.openjdk.jmh.annotations.Warmup;

/** JMH microbenchmarks for {@link DoFnInvoker} bytecode generation and invocation. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@SuppressWarnings({"rawtypes", "unchecked", "nullness", "unused"})
public class DoFnInvokerBenchmark {

  public static class ProcessContextDoFn extends DoFn<KV<String, Long>, String> {
    @ProcessElement
    public void processElement(ProcessContext c) {
      c.output(c.element().getKey());
    }
  }

  public static class MultiParamDoFn extends DoFn<KV<String, Long>, String> {
    @ProcessElement
    public void processElement(
        @Element KV<String, Long> element,
        @Timestamp Instant timestamp,
        BoundedWindow window,
        PaneInfo pane,
        CausedByDrain causedByDrain,
        OutputReceiver<String> out) {
      if (timestamp.getMillis() >= 0 && window != null && pane != null && causedByDrain != null) {
        out.output(element.getKey());
      }
    }
  }

  public static class StatefulAndTimerDoFn extends DoFn<KV<String, Long>, String> {
    @StateId("s1")
    private final StateSpec<ValueState<Long>> s1 = StateSpecs.value(VarLongCoder.of());

    @StateId("s2")
    private final StateSpec<ValueState<Long>> s2 = StateSpecs.value(VarLongCoder.of());

    @TimerId("t1")
    private final TimerSpec t1 = TimerSpecs.timer(TimeDomain.EVENT_TIME);

    @TimerId("t2")
    private final TimerSpec t2 = TimerSpecs.timer(TimeDomain.PROCESSING_TIME);

    @TimerFamily("fam1")
    private final TimerSpec fam1 = TimerSpecs.timerMap(TimeDomain.EVENT_TIME);

    @ProcessElement
    public void processElement(
        @Element KV<String, Long> element,
        @Timestamp Instant timestamp,
        @StateId("s1") ValueState<Long> state1,
        @StateId("s2") ValueState<Long> state2,
        @TimerId("t1") Timer timer1,
        OutputReceiver<String> out) {
      Long v1 = state1.read();
      state2.write(v1);
      timer1.set(timestamp);
      out.output(element.getKey());
    }

    @OnTimer("t1")
    public void onTimer1(@Timestamp Instant ts, BoundedWindow window, OutputReceiver<String> out) {
      if (ts != null && window != null) {
        out.output("t1");
      }
    }

    @OnTimer("t2")
    public void onTimer2(
        @Timestamp Instant ts,
        BoundedWindow window,
        @StateId("s1") ValueState<Long> state1,
        OutputReceiver<String> out) {
      if (ts != null && window != null && state1.read() != null) {
        out.output("t2");
      }
    }

    @OnTimerFamily("fam1")
    public void onTimerFamily1(
        @TimerId String dynamicTimerId,
        @Timestamp Instant ts,
        BoundedWindow window,
        OutputReceiver<String> out) {
      if (ts != null && window != null) {
        out.output(dynamicTimerId);
      }
    }
  }

  public static class DefaultRestriction
      implements HasDefaultTracker<DefaultRestriction, DefaultTracker>,
          HasDefaultWatermarkEstimator<Instant, WatermarkEstimators.Manual> {
    @Override
    public DefaultTracker newTracker() {
      return new DefaultTracker(this);
    }

    @Override
    public WatermarkEstimators.Manual newWatermarkEstimator() {
      return new WatermarkEstimators.Manual(Instant.EPOCH);
    }
  }

  public static class DefaultTracker extends RestrictionTracker<DefaultRestriction, Void>
      implements HasProgress {
    private static final Progress PROGRESS = Progress.from(2.0, 3.0);
    private final DefaultRestriction restriction;

    DefaultTracker(DefaultRestriction restriction) {
      this.restriction = restriction;
    }

    @Override
    public boolean tryClaim(Void position) {
      return true;
    }

    @Override
    public DefaultRestriction currentRestriction() {
      return restriction;
    }

    @Override
    public SplitResult<DefaultRestriction> trySplit(double fractionOfRemainder) {
      return SplitResult.of(restriction, restriction);
    }

    @Override
    public void checkDone() {}

    @Override
    public IsBounded isBounded() {
      return IsBounded.BOUNDED;
    }

    @Override
    public Progress getProgress() {
      return PROGRESS;
    }
  }

  public static class DefaultSdfDoFn extends DoFn<KV<String, Long>, String> {
    @GetInitialRestriction
    public DefaultRestriction getInitialRestriction(@Element KV<String, Long> element) {
      return new DefaultRestriction();
    }

    @ProcessElement
    public void processElement(
        @Element KV<String, Long> element,
        RestrictionTracker<DefaultRestriction, Void> tracker,
        OutputReceiver<String> out) {
      if (tracker.tryClaim(null)) {
        out.output(element.getKey());
      }
    }
  }

  private static final class FastArgumentProvider
      extends DoFnInvoker.BaseArgumentProvider<KV<String, Long>, String> {
    private final KV<String, Long> element = KV.of("user-key-123", 42L);
    private final Instant timestamp = Instant.ofEpochMilli(1000L);
    private final BoundedWindow window = GlobalWindow.INSTANCE;
    private final PaneInfo pane = PaneInfo.NO_FIRING;
    private final DefaultRestriction restriction = new DefaultRestriction();
    private final DefaultTracker tracker = new DefaultTracker(restriction);
    private String lastOutput;

    private final DoFn<KV<String, Long>, String>.ProcessContext processContext;
    private final OutputReceiver<String> outputReceiver =
        new OutputReceiver<String>() {
          @Override
          public OutputBuilder<String> builder(String value) {
            return null;
          }

          @Override
          public void output(String output) {
            lastOutput = output;
          }
        };

    private final ValueState<Long> valueState =
        new ValueState<Long>() {
          private Long val = 10L;

          @Override
          public void write(Long input) {
            this.val = input;
          }

          @Override
          public Long read() {
            return val;
          }

          @Override
          public ValueState<Long> readLater() {
            return this;
          }

          @Override
          public void clear() {
            this.val = null;
          }
        };

    private final Timer timer =
        new Timer() {
          @Override
          public void set(Instant target) {}

          @Override
          public void setRelative() {}

          @Override
          public Timer offset(Duration offset) {
            return this;
          }

          @Override
          public Timer align(Duration period) {
            return this;
          }

          @Override
          public Timer withOutputTimestamp(Instant outputTimestamp) {
            return this;
          }

          @Override
          public Timer withNoOutputTimestamp() {
            return this;
          }

          @Override
          public void clear() {}

          @Override
          public Instant getCurrentRelativeTime() {
            return timestamp;
          }
        };

    FastArgumentProvider(DoFn<KV<String, Long>, String> fn) {
      this.processContext =
          fn.new ProcessContext() {
            @Override
            public KV<String, Long> element() {
              return element;
            }

            @Override
            public <T> T sideInput(PCollectionView<T> view) {
              return null;
            }

            @Override
            public Instant timestamp() {
              return timestamp;
            }

            @Override
            public PaneInfo pane() {
              return pane;
            }

            @Override
            public String currentRecordId() {
              return null;
            }

            @Override
            public Long currentRecordOffset() {
              return null;
            }

            @Override
            public CausedByDrain causedByDrain() {
              return CausedByDrain.NORMAL;
            }

            @Override
            public org.apache.beam.sdk.values.ValueKind valueKind() {
              return org.apache.beam.sdk.values.ValueKind.INSERT;
            }

            @Override
            public PipelineOptions getPipelineOptions() {
              return null;
            }

            @Override
            public void output(String output) {
              lastOutput = output;
            }

            @Override
            public void outputWithTimestamp(String output, Instant timestamp) {
              lastOutput = output;
            }

            @Override
            public void outputWindowedValue(
                String output,
                Instant timestamp,
                Collection<? extends BoundedWindow> windows,
                PaneInfo pane) {
              lastOutput = output;
            }

            @Override
            public <T> void output(TupleTag<T> tag, T output) {}

            @Override
            public <T> void outputWithTimestamp(TupleTag<T> tag, T output, Instant timestamp) {}

            @Override
            public <T> void outputWindowedValue(
                TupleTag<T> tag,
                T output,
                Instant timestamp,
                Collection<? extends BoundedWindow> windows,
                PaneInfo pane) {}

            @Override
            public <T> void outputWindowedValue(
                TupleTag<T> tag, org.apache.beam.sdk.values.WindowedValue<T> windowedValue) {}

            @Override
            public void outputWindowedValue(
                org.apache.beam.sdk.values.WindowedValue<String> windowedValue) {
              lastOutput = windowedValue.getValue();
            }
          };
    }

    @Override
    public String getErrorContext() {
      return "DoFnInvokerBenchmark";
    }

    @Override
    public BoundedWindow window() {
      return window;
    }

    @Override
    public PaneInfo paneInfo(DoFn<KV<String, Long>, String> doFn) {
      return pane;
    }

    @Override
    public DoFn<KV<String, Long>, String>.ProcessContext processContext(
        DoFn<KV<String, Long>, String> doFn) {
      return processContext;
    }

    @Override
    public KV<String, Long> element(DoFn<KV<String, Long>, String> doFn) {
      return element;
    }

    @Override
    public Object key() {
      return element.getKey();
    }

    @Override
    public Instant timestamp(DoFn<KV<String, Long>, String> doFn) {
      return timestamp;
    }

    @Override
    public CausedByDrain causedByDrain(DoFn<KV<String, Long>, String> doFn) {
      return CausedByDrain.NORMAL;
    }

    @Override
    public String timerId(DoFn<KV<String, Long>, String> doFn) {
      return "dynamic-timer-1";
    }

    @Override
    public TimeDomain timeDomain(DoFn<KV<String, Long>, String> doFn) {
      return TimeDomain.EVENT_TIME;
    }

    @Override
    public OutputReceiver<String> outputReceiver(DoFn<KV<String, Long>, String> doFn) {
      return outputReceiver;
    }

    @Override
    public Object restriction() {
      return restriction;
    }

    @Override
    public RestrictionTracker<?, ?> restrictionTracker() {
      return tracker;
    }

    @Override
    public org.apache.beam.sdk.state.State state(String stateId, boolean alwaysFetched) {
      return valueState;
    }

    @Override
    public Timer timer(String timerId) {
      return timer;
    }

    @Override
    public Object watermarkEstimatorState() {
      return restriction;
    }
  }

  @State(Scope.Benchmark)
  public static class BenchmarkState {
    DoFnInvoker<KV<String, Long>, String> processContextInvoker;
    DoFnInvoker<KV<String, Long>, String> multiParamInvoker;
    DoFnInvoker<KV<String, Long>, String> statefulAndTimerInvoker;
    DoFnInvoker<KV<String, Long>, String> defaultSdfInvoker;
    FastArgumentProvider argumentProvider;

    @Setup
    public void setUp() {
      ProcessContextDoFn processContextDoFn = new ProcessContextDoFn();
      this.processContextInvoker = DoFnInvokers.invokerFor(processContextDoFn);
      this.multiParamInvoker = DoFnInvokers.invokerFor(new MultiParamDoFn());
      this.statefulAndTimerInvoker = DoFnInvokers.invokerFor(new StatefulAndTimerDoFn());
      this.defaultSdfInvoker = DoFnInvokers.invokerFor(new DefaultSdfDoFn());
      this.argumentProvider = new FastArgumentProvider(processContextDoFn);
    }
  }

  @Benchmark
  public String invokeProcessContext(BenchmarkState state) {
    state.processContextInvoker.invokeProcessElement(state.argumentProvider);
    return state.argumentProvider.lastOutput;
  }

  @Benchmark
  public String invokeMultiParamElement(BenchmarkState state) {
    state.multiParamInvoker.invokeProcessElement(state.argumentProvider);
    return state.argumentProvider.lastOutput;
  }

  @Benchmark
  public String invokeStatefulAndTimerElement(BenchmarkState state) {
    state.statefulAndTimerInvoker.invokeProcessElement(state.argumentProvider);
    return state.argumentProvider.lastOutput;
  }

  @Benchmark
  public String invokeOnTimer(BenchmarkState state) {
    state.statefulAndTimerInvoker.invokeOnTimer(
        TimerDeclaration.PREFIX + "t2", "", state.argumentProvider);
    return state.argumentProvider.lastOutput;
  }

  @Benchmark
  public String invokeOnTimerFamily(BenchmarkState state) {
    state.statefulAndTimerInvoker.invokeOnTimer(
        "dynamic-timer-1", TimerFamilyDeclaration.PREFIX + "fam1", state.argumentProvider);
    return state.argumentProvider.lastOutput;
  }

  @Benchmark
  public double invokeDefaultSdfCallbacks(BenchmarkState state) {
    RestrictionTracker<?, ?> tracker =
        state.defaultSdfInvoker.invokeNewTracker(state.argumentProvider);
    WatermarkEstimator<?> estimator =
        state.defaultSdfInvoker.invokeNewWatermarkEstimator(state.argumentProvider);
    return state.defaultSdfInvoker.invokeGetSize(state.argumentProvider)
        + (tracker != null && estimator != null ? 1.0 : 0.0);
  }
}
