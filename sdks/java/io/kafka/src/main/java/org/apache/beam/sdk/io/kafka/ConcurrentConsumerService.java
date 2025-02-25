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
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;
import org.checkerframework.checker.nullness.qual.Nullable;

class ConcurrentConsumerService<K, V> implements ConcurrentConsumer<K, V> {
  private class BoundTopicPartitionAssignment
      implements ConcurrentConsumer.TopicPartitionAssignment<K, V> {

    private final TopicPartition partition;

    BoundTopicPartitionAssignment(final TopicPartition partition) {
      this.partition = partition;
    }

    @Override
    public void close() throws Exception {
      final Thread currentThread = Thread.currentThread();
      final @Nullable Thread previousThread =
          // ConcurrentConsumerService.this.pendingTails.getAndSet(ASSIGN, currentThread);
          ConcurrentConsumerService.this.assignPendingTail.getAndSet(0, currentThread);
      boolean reinterrupt = false;

      Thread.yield();

      // if (ConcurrentConsumerService.this.pendingTails.compareAndSet(ASSIGN, currentThread, null))
      // {
      if (ConcurrentConsumerService.this.assignPendingTail.compareAndSet(0, currentThread, null)) {
        synchronized (ConcurrentConsumerService.this.consumer) {
          synchronized (ConcurrentConsumerService.this.assignActiveTail) {
            ConcurrentConsumerService.this.assignArgument.remove(this.partition);

            if (previousThread != null) {
              // ConcurrentConsumerService.this.activeTails.set(ASSIGN, previousThread);
              ConcurrentConsumerService.this.assignActiveTail.set(0, previousThread);
              LockSupport.unpark(previousThread);
              // while (ConcurrentConsumerService.this.activeTails.get(ASSIGN) != null) {
              while (ConcurrentConsumerService.this.assignActiveTail.get(0) != null) {
                try {
                  ConcurrentConsumerService.this.assignActiveTail.wait();
                } catch (InterruptedException e) {
                  reinterrupt = true;
                }
              }
            }

            ConcurrentConsumerService.this.consumer.assign(
                ConcurrentConsumerService.this.assignArgument);
            ConcurrentConsumerService.this.consumer.pause(
                ConcurrentConsumerService.this.assignArgument);
          }
        }
      } else { // Shared read
        // while (ConcurrentConsumerService.this.activeTails.get(ASSIGN) != currentThread) {
        while (ConcurrentConsumerService.this.assignActiveTail.get(0) != currentThread) {
          LockSupport.park(this);
        }

        ConcurrentConsumerService.this.assignArgument.remove(this.partition);

        // ConcurrentConsumerService.this.activeTails.set(ASSIGN, previousThread);
        ConcurrentConsumerService.this.assignActiveTail.set(0, previousThread);
        if (previousThread == null) {
          synchronized (ConcurrentConsumerService.this.assignActiveTail) {
            ConcurrentConsumerService.this.assignActiveTail.notify();
          }
        } else {
          LockSupport.unpark(previousThread);
        }
      }

      if (reinterrupt) {
        currentThread.interrupt();
      }
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Optional<List<ConsumerRecord<K, V>>> poll(Duration timeout)
        throws ExecutionException, InterruptedException {
      final Thread currentThread = Thread.currentThread();
      final @Nullable Thread previousThread =
          // ConcurrentConsumerService.this.pendingTails.getAndSet(POLL, currentThread);
          ConcurrentConsumerService.this.pollPendingTail.getAndSet(0, currentThread);
      final ConsumerRecords<K, V> result;
      boolean reinterrupt = false;

      Thread.yield();

      // if (ConcurrentConsumerService.this.pendingTails.compareAndSet(POLL, currentThread, null)) {
      if (ConcurrentConsumerService.this.pollPendingTail.compareAndSet(0, currentThread, null)) {
        synchronized (ConcurrentConsumerService.this.consumer) {
          synchronized (ConcurrentConsumerService.this.pollActiveTail) {
            ConcurrentConsumerService.this.pollArgument.add(this.partition);

            if (previousThread != null) {
              // ConcurrentConsumerService.this.activeTails.set(POLL, previousThread);
              ConcurrentConsumerService.this.pollActiveTail.set(0, previousThread);
              LockSupport.unpark(previousThread);
              // while (ConcurrentConsumerService.this.activeTails.get(POLL) != null) {
              while (ConcurrentConsumerService.this.pollActiveTail.get(0) != null) {
                try {
                  ConcurrentConsumerService.this.pollActiveTail.wait();
                } catch (InterruptedException e) {
                  reinterrupt = true;
                }
              }
            }

            ConcurrentConsumerService.this.consumer.resume(
                ConcurrentConsumerService.this.pollArgument);

            try {
              ConcurrentConsumerService.this.pollReturn =
                  result = ConcurrentConsumerService.this.consumer.poll(timeout);
            } finally {
              if (previousThread != null) {
                // ConcurrentConsumerService.this.activeTails.set(POLL, previousThread);
                ConcurrentConsumerService.this.pollActiveTail.set(0, previousThread);
                LockSupport.unpark(previousThread);
                // while (ConcurrentConsumerService.this.activeTails.get(POLL) != null) {
                while (ConcurrentConsumerService.this.pollActiveTail.get(0) != null) {
                  try {
                    ConcurrentConsumerService.this.pollActiveTail.wait();
                  } catch (InterruptedException e) {
                    reinterrupt = true;
                  }
                }
              }

              ConcurrentConsumerService.this.consumer.pause(
                  ConcurrentConsumerService.this.pollArgument);
              ConcurrentConsumerService.this.pollArgument.clear();
            }
          }
        }
      } else { // Shared read
        // while (ConcurrentConsumerService.this.activeTails.get(POLL) != currentThread) {
        while (ConcurrentConsumerService.this.pollActiveTail.get(0) != currentThread) {
          LockSupport.park(this);
        }

        ConcurrentConsumerService.this.pollArgument.add(this.partition);

        // ConcurrentConsumerService.this.activeTails.set(POLL, previousThread);
        ConcurrentConsumerService.this.pollActiveTail.set(0, previousThread);
        if (previousThread == null) {
          synchronized (ConcurrentConsumerService.this.pollActiveTail) {
            ConcurrentConsumerService.this.pollActiveTail.notify();
          }
        } else {
          LockSupport.unpark(previousThread);
        }

        // while (ConcurrentConsumerService.this.activeTails.get(POLL) != currentThread) {
        while (ConcurrentConsumerService.this.pollActiveTail.get(0) != currentThread) {
          LockSupport.park(this);
        }

        result = ConcurrentConsumerService.this.pollReturn;

        // ConcurrentConsumerService.this.activeTails.set(POLL, previousThread);
        ConcurrentConsumerService.this.pollActiveTail.set(0, previousThread);
        if (previousThread == null) {
          synchronized (ConcurrentConsumerService.this.pollActiveTail) {
            ConcurrentConsumerService.this.pollActiveTail.notify();
          }
        } else {
          LockSupport.unpark(previousThread);
        }
      }

      if (reinterrupt) {
        currentThread.interrupt();
      }

      if (result == ConsumerRecords.empty()) {
        return Optional.empty();
      } else {
        return Optional.of(result.records(this.partition));
      }
    }

    @Override
    public long position() throws ExecutionException, InterruptedException {
      synchronized (ConcurrentConsumerService.this.consumer) {
        return ConcurrentConsumerService.this.consumer.position(this.partition);
      }
    }

    @Override
    public void seek(final long offset) {
      synchronized (ConcurrentConsumerService.this.consumer) {
        ConcurrentConsumerService.this.consumer.seek(this.partition, offset);
      }
    }
  }

  // private static final int ASSIGN = 0;
  // private static final int END_OFFSETS = 1;
  // private static final int POLL = 2;

  // private final AtomicReferenceArray<@Nullable Thread> pendingTails;
  // private final AtomicReferenceArray<@Nullable Thread> activeTails;

  private final Consumer<K, V> consumer;

  private final AtomicReferenceArray<@Nullable Thread> assignPendingTail;
  private final AtomicReferenceArray<@Nullable Thread> assignActiveTail;
  private final Set<TopicPartition> assignArgument;

  private final AtomicReferenceArray<@Nullable Thread> endOffsetsPendingTail;
  private final AtomicReferenceArray<@Nullable Thread> endOffsetsActiveTail;
  private final Set<TopicPartition> endOffsetsArgument;
  private Map<TopicPartition, Long> endOffsetsReturn;

  private final AtomicReferenceArray<@Nullable Thread> pollPendingTail;
  private final AtomicReferenceArray<@Nullable Thread> pollActiveTail;
  private final Set<TopicPartition> pollArgument;
  private ConsumerRecords<K, V> pollReturn;

  ConcurrentConsumerService(final Consumer<K, V> consumer) {
    // this.pendingTails = new AtomicReferenceArray<>(16);
    // this.activeTails = new AtomicReferenceArray<>(16);

    this.consumer = consumer;

    this.assignPendingTail = new AtomicReferenceArray<>(32);
    this.assignActiveTail = new AtomicReferenceArray<>(32);
    this.assignArgument = new HashSet<>();

    this.endOffsetsPendingTail = new AtomicReferenceArray<>(32);
    this.endOffsetsActiveTail = new AtomicReferenceArray<>(32);
    this.endOffsetsArgument = new HashSet<>();
    this.endOffsetsReturn = Collections.emptyMap();

    this.pollPendingTail = new AtomicReferenceArray<>(32);
    this.pollActiveTail = new AtomicReferenceArray<>(32);
    this.pollArgument = new HashSet<>();
    this.pollReturn = ConsumerRecords.empty();
  }

  @Override
  public void close() throws Exception {
    synchronized (this.consumer) {
      this.consumer.close();
    }
  }

  @Override
  public OptionalLong endOffset(final TopicPartition partition) throws Exception {
    final Thread currentThread = Thread.currentThread();
    // final @Nullable Thread previousThread = this.pendingTails.getAndSet(END_OFFSETS,
    // currentThread);
    final @Nullable Thread previousThread = this.endOffsetsPendingTail.getAndSet(0, currentThread);
    ;
    final Map<TopicPartition, Long> result;
    boolean reinterrupt = false;

    Thread.yield();

    // if (this.pendingTails.compareAndSet(END_OFFSETS, currentThread, null)) {
    if (this.endOffsetsPendingTail.compareAndSet(0, currentThread, null)) {
      synchronized (this.consumer) {
        synchronized (this.endOffsetsActiveTail) {
          this.endOffsetsArgument.add(partition);

          if (previousThread != null) {
            // this.activeTails.set(END_OFFSETS, previousThread);
            this.endOffsetsActiveTail.set(0, previousThread);
            LockSupport.unpark(previousThread);
            // while (this.activeTails.get(END_OFFSETS) != null) {
            while (this.endOffsetsActiveTail.get(0) != null) {
              try {
                this.endOffsetsActiveTail.wait();
              } catch (InterruptedException e) {
                reinterrupt = true;
              }
            }
          }

          try {
            this.endOffsetsReturn = result = this.consumer.endOffsets(endOffsetsArgument);
          } finally {
            if (previousThread != null) {
              // this.activeTails.set(END_OFFSETS, previousThread);
              this.endOffsetsActiveTail.set(0, previousThread);
              LockSupport.unpark(previousThread);
              // while (this.activeTails.get(END_OFFSETS) != null) {
              while (this.endOffsetsActiveTail.get(0) != null) {
                try {
                  this.endOffsetsActiveTail.wait();
                } catch (InterruptedException e) {
                  reinterrupt = true;
                }
              }
            }

            this.endOffsetsArgument.clear();
          }
        }
      }
    } else { // Shared read
      // while (this.activeTails.get(END_OFFSETS) != currentThread) {
      while (this.endOffsetsActiveTail.get(0) != currentThread) {
        LockSupport.park(this);
      }

      this.endOffsetsArgument.add(partition);

      // this.activeTails.set(END_OFFSETS, previousThread);
      this.endOffsetsActiveTail.set(0, previousThread);
      if (previousThread == null) {
        synchronized (this.endOffsetsActiveTail) {
          this.endOffsetsActiveTail.notify();
        }
      } else {
        LockSupport.unpark(previousThread);
      }

      // while (this.activeTails.get(END_OFFSETS) != currentThread) {
      while (this.endOffsetsActiveTail.get(0) != currentThread) {
        LockSupport.park(this);
      }

      result = this.endOffsetsReturn;

      // this.activeTails.set(END_OFFSETS, previousThread);
      this.endOffsetsActiveTail.set(0, previousThread);
      if (previousThread == null) {
        synchronized (this.endOffsetsActiveTail) {
          this.endOffsetsActiveTail.notify();
        }
      } else {
        LockSupport.unpark(previousThread);
      }
    }

    if (reinterrupt) {
      currentThread.interrupt();
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
    final Thread currentThread = Thread.currentThread();
    // final @Nullable Thread previousThread = this.pendingTails.getAndSet(ASSIGN, currentThread);
    final @Nullable Thread previousThread = this.assignPendingTail.getAndSet(0, currentThread);
    boolean reinterrupt = false;

    Thread.yield();

    // if (this.pendingTails.compareAndSet(ASSIGN, currentThread, null)) {
    if (this.assignPendingTail.compareAndSet(0, currentThread, null)) {
      synchronized (this.consumer) {
        synchronized (this.assignActiveTail) {
          this.assignArgument.add(partition);

          if (previousThread != null) {
            // this.activeTails.set(ASSIGN, previousThread);
            this.assignActiveTail.set(0, previousThread);
            LockSupport.unpark(previousThread);
            // while (this.activeTails.get(ASSIGN) != null) {
            while (this.assignActiveTail.get(0) != null) {
              try {
                this.assignActiveTail.wait();
              } catch (InterruptedException e) {
                reinterrupt = true;
              }
            }
          }

          this.consumer.assign(this.assignArgument);
          this.consumer.pause(this.assignArgument);
        }
      }
    } else { // Shared read
      // while (this.activeTails.get(ASSIGN) != currentThread) {
      while (this.assignActiveTail.get(0) != currentThread) {
        LockSupport.park(this);
      }

      this.assignArgument.add(partition);

      // this.activeTails.set(ASSIGN, previousThread);
      this.assignActiveTail.set(0, previousThread);
      if (previousThread == null) {
        synchronized (this.assignActiveTail) {
          this.assignActiveTail.notify();
        }
      } else {
        LockSupport.unpark(previousThread);
      }
    }

    if (reinterrupt) {
      currentThread.interrupt();
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

  static {
    // Reduce the risk of rare disastrous classloading in first call to
    // LockSupport.park: https://bugs.openjdk.org/browse/JDK-8074773
    @SuppressWarnings("unused")
    Class<?> ensureLoaded = LockSupport.class;
  }
}
