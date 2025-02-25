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

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;
import org.checkerframework.checker.nullness.qual.Nullable;

class SynchronizedConcurrentConsumer<K, V> implements ConcurrentConsumer<K, V> {
  private class BoundTopicPartitionAssignment
      implements ConcurrentConsumer.TopicPartitionAssignment<K, V> {

    private final TopicPartition partition;

    BoundTopicPartitionAssignment(final TopicPartition partition) {
      this.partition = partition;
    }

    @Override
    public void close() throws Exception {
      synchronized (SynchronizedConcurrentConsumer.this.consumer) {
        Set<TopicPartition> assignment =
            new HashSet<>(SynchronizedConcurrentConsumer.this.consumer.assignment());
        assignment.remove(partition);
        try {
          SynchronizedConcurrentConsumer.this.consumer.assign(assignment);
        } finally {
          SynchronizedConcurrentConsumer.this.consumer.pause(
              SynchronizedConcurrentConsumer.this.consumer.assignment());
        }
      }
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Optional<List<ConsumerRecord<K, V>>> poll(Duration timeout)
        throws ExecutionException, InterruptedException {
      final ConsumerRecords<K, V> result;

      synchronized (SynchronizedConcurrentConsumer.this.consumer) {
        SynchronizedConcurrentConsumer.this.consumer.resume(Collections.singleton(this.partition));
        result = SynchronizedConcurrentConsumer.this.consumer.poll(timeout);
        SynchronizedConcurrentConsumer.this.consumer.pause(Collections.singleton(this.partition));
      }

      if (result == ConsumerRecords.empty()) {
        return Optional.empty();
      } else {
        return Optional.of(result.records(this.partition));
      }
    }

    @Override
    public long position() throws ExecutionException, InterruptedException {
      synchronized (SynchronizedConcurrentConsumer.this.consumer) {
        return SynchronizedConcurrentConsumer.this.consumer.position(this.partition);
      }
    }

    @Override
    public void seek(final long offset) {
      synchronized (SynchronizedConcurrentConsumer.this.consumer) {
        SynchronizedConcurrentConsumer.this.consumer.seek(this.partition, offset);
      }
    }
  }

  private final Consumer<K, V> consumer;

  SynchronizedConcurrentConsumer(final Consumer<K, V> consumer) {
    this.consumer = consumer;
  }

  @Override
  public void close() throws Exception {
    synchronized (this.consumer) {
      this.consumer.close();
    }
  }

  @Override
  public OptionalLong endOffset(final TopicPartition partition) throws Exception {
    final Map<TopicPartition, Long> result;

    synchronized (this.consumer) {
      result = this.consumer.endOffsets(Collections.singleton(partition));
    }

    final @Nullable Long endOffset = result.get(partition);
    if (endOffset == null) {
      return OptionalLong.empty();
    } else {
      return OptionalLong.of(endOffset);
    }
  }

  @Override
  public TopicPartitionAssignment<K, V> assign(final TopicPartition partition) {
    synchronized (this.consumer) {
      Set<TopicPartition> assignment = new HashSet<>(this.consumer.assignment());
      assignment.add(partition);
      try {
        this.consumer.assign(assignment);
      } finally {
        this.consumer.pause(this.consumer.assignment());
      }
    }

    return new BoundTopicPartitionAssignment(partition);
  }

  @Override
  public Map<MetricName, ? extends Metric> metrics() {
    return this.consumer.metrics();
  }

  @Override
  public Optional<OffsetAndTimestamp> offsetForTime(
      final TopicPartition partition, final long timestampToSearch)
      throws ExecutionException, InterruptedException {
    final Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes;
    synchronized (this.consumer) {
      offsetsForTimes =
          this.consumer.offsetsForTimes(Collections.singletonMap(partition, timestampToSearch));
    }
    return Optional.of(partition).map(offsetsForTimes::get);
  }
}
