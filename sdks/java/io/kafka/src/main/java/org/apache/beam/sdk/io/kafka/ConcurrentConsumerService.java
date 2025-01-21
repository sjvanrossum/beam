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
package org.apache.beam.sdk.io.kafka;

import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions.checkState;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.function.Supplier;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Suppliers;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;

class ConcurrentConsumerService<K, V> implements ConcurrentConsumer<K, V> {
  private class BoundTopicPartitionAssignment
      implements ConcurrentConsumer.TopicPartitionAssignment<K, V> {
    private final TopicPartition partition;
    private final Supplier<Metric> recordsLagMetricSupplier;
    private volatile boolean refreshPosition;
    private volatile long position;

    BoundTopicPartitionAssignment(
        final TopicPartition partition, final Supplier<Metric> recordsLagMetricSupplier) {
      this.partition = partition;
      this.recordsLagMetricSupplier = recordsLagMetricSupplier;
      this.refreshPosition = true;
      this.position = 0L;
    }

    @Override
    public void close() {
      ConcurrentConsumerService.this.executorService.execute(
          () -> {
            ConcurrentConsumerService.this.assignment.remove(partition, this);
            ConcurrentConsumerService.this.partitionRecordsLagMetricSuppliers.remove(
                partition, this.recordsLagMetricSupplier);
          });
    }

    @Override
    public OptionalLong currentLag() {
      try {
        return OptionalLong.of(
            ((Number) this.recordsLagMetricSupplier.get().metricValue()).longValue());
      } catch (Exception e) {
        return OptionalLong.empty();
      }
    }

    @Override
    public Optional<List<ConsumerRecord<K, V>>> pollOnce()
        throws ExecutionException, InterruptedException {
      checkState(ConcurrentConsumerService.this.pollPhaser.register() >= 0);
      try {
        ConcurrentConsumerService.this.executorService.execute(
            () ->
                ConcurrentConsumerService.this.consumer.resume(
                    Collections.singleton(this.partition)));
        while (ConcurrentConsumerService.this.pollPhaser.arriveAndAwaitAdvance() >= 0) {
          final ConsumerRecords<K, V> nextRecords = ConcurrentConsumerService.this.pollResult.get();
          if (nextRecords.partitions().contains(this.partition)) {
            this.refreshPosition = true;
            return Optional.of(nextRecords.records(this.partition));
          }
        }
      } finally {
        ConcurrentConsumerService.this.pollPhaser.arriveAndDeregister();
      }

      return Optional.empty();
    }

    @Override
    public long position() throws ExecutionException, InterruptedException {
      if (this.refreshPosition) {
        final long nextPosition =
            ConcurrentConsumerService.this
                .executorService
                .submit(() -> ConcurrentConsumerService.this.consumer.position(this.partition))
                .get();
        this.position = nextPosition;
        this.refreshPosition = false;
        return nextPosition;
      }

      return this.position;
    }

    @Override
    public void seek(final long offset) {
      this.position = offset;
      this.refreshPosition = false;
      ConcurrentConsumerService.this.executorService.execute(
          () -> ConcurrentConsumerService.this.consumer.seek(this.partition, offset));
    }
  }

  private class PollPhaser extends Phaser {
    @Override
    public boolean onAdvance(int phase, int registeredParties) {
      if (registeredParties > 0) {
        ConcurrentConsumerService.this.pollResult =
            ConcurrentConsumerService.this.executorService.submit(
                () -> {
                  final ConsumerRecords<K, V> result =
                      ConcurrentConsumerService.this.consumer.poll(
                          ConcurrentConsumerService.this.pollTimeout);
                  ConcurrentConsumerService.this.consumer.pause(result.partitions());
                  return result;
                });
      }
      return ConcurrentConsumerService.this.executorService.isShutdown();
    }
  }

  private final Consumer<K, V> consumer;
  private final Duration pollTimeout;
  private final Phaser pollPhaser;
  private final ExecutorService executorService;
  private final Map<TopicPartition, Supplier<Metric>> partitionRecordsLagMetricSuppliers;
  private final Map<TopicPartition, BoundTopicPartitionAssignment> assignment;
  private Future<ConsumerRecords<K, V>> pollResult;

  ConcurrentConsumerService(final Consumer<K, V> consumer, final Duration pollTimeout) {
    this.consumer = consumer;
    this.pollTimeout = pollTimeout;
    this.pollPhaser = new PollPhaser();
    this.executorService = Executors.newSingleThreadExecutor();
    this.partitionRecordsLagMetricSuppliers = new ConcurrentHashMap<>();
    this.assignment = new ConcurrentHashMap<>();
    this.pollResult = CompletableFuture.completedFuture(ConsumerRecords.empty());
  }

  @Override
  public TopicPartitionAssignment<K, V> assign(final TopicPartition partition) {
    final BoundTopicPartitionAssignment result =
        new BoundTopicPartitionAssignment(
            partition,
            this.partitionRecordsLagMetricSuppliers.computeIfAbsent(
                partition,
                k ->
                    Suppliers.memoize(
                        () ->
                            this.consumer.metrics().values().stream()
                                .filter(
                                    m ->
                                        "consumer-fetch-manager-metrics"
                                                .equals(m.metricName().group())
                                            && "records-lag".equals(m.metricName().name())
                                            && k.topic()
                                                .replace('.', '_')
                                                .equals(m.metricName().tags().get("topic"))
                                            && Integer.toString(k.partition())
                                                .equals(m.metricName().tags().get("partition")))
                                .findAny()
                                .get())));
    this.executorService.execute(
        () -> {
          checkState(this.assignment.put(partition, result) == null);
          this.consumer.assign(this.assignment.keySet());
          this.consumer.pause(Collections.singleton(partition));
        });
    return result;
  }

  @Override
  public Map<TopicPartition, TopicPartitionAssignment<K, V>> assignment() {
    return Collections.unmodifiableMap(this.assignment);
  }

  @Override
  public void close() {
    this.executorService.shutdown();
  }

  @Override
  public boolean isClosed() {
    return this.executorService.isShutdown();
  }

  @Override
  public Map<MetricName, ? extends Metric> metrics() {
    return this.consumer.metrics();
  }

  @Override
  public Optional<OffsetAndTimestamp> offsetForTime(
      final TopicPartition partition, final long timestampToSearch)
      throws ExecutionException, InterruptedException {
    Future<Optional<OffsetAndTimestamp>> result =
        this.executorService.submit(
            () ->
                Optional.ofNullable(
                    this.consumer
                        .offsetsForTimes(Collections.singletonMap(partition, timestampToSearch))
                        .get(partition)));
    return result.get();
  }
}
