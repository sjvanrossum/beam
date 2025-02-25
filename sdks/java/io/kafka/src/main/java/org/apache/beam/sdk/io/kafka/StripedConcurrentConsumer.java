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

import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Supplier;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.hash.Hashing;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;
import org.checkerframework.checker.nullness.qual.Nullable;

class StripedConcurrentConsumer<K, V> implements ConcurrentConsumer<K, V> {
  private final int length;
  private final AtomicReferenceArray<@Nullable ConcurrentConsumer<K, V>> stripes;
  private final Supplier<ConcurrentConsumer<K, V>> consumerSupplier;

  StripedConcurrentConsumer(Supplier<ConcurrentConsumer<K, V>> consumerSupplier) {
    this.length = Runtime.getRuntime().availableProcessors();
    this.stripes = new AtomicReferenceArray<>(this.length * 16);
    this.consumerSupplier = consumerSupplier;
  }

  @Override
  public void close() throws Exception {
    for (int i = 0; i < this.stripes.length(); ++i) {
      final @Nullable ConcurrentConsumer<K, V> stripe = this.stripes.get(i * 16);
      if (stripe != null) {
        stripe.close();
      }
    }
  }

  @Override
  public OptionalLong endOffset(final TopicPartition partition) throws Exception {
    return computeIfAbsent(partition).endOffset(partition);
  }

  @Override
  public TopicPartitionAssignment<K, V> assign(final TopicPartition partition) throws Exception {
    return computeIfAbsent(partition).assign(partition);
  }

  @Override
  public Map<MetricName, ? extends Metric> metrics() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<OffsetAndTimestamp> offsetForTime(
      TopicPartition partition, long timestampToSearch) throws Exception {
    return computeIfAbsent(partition).offsetForTime(partition, timestampToSearch);
  }

  private ConcurrentConsumer<K, V> computeIfAbsent(final TopicPartition partition) {
    final int stripe = Hashing.consistentHash(partition.hashCode(), this.length) * 16;

    @Nullable ConcurrentConsumer<K, V> consumer = this.stripes.get(stripe);
    if (consumer == null) {
      synchronized (this.stripes) {
        consumer = this.stripes.get(stripe);
        if (consumer == null) {
          consumer = this.consumerSupplier.get();
          this.stripes.set(stripe, consumer);
        }
      }
    }

    return consumer;
  }
}
