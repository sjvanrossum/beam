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
package org.apache.beam.sdk.schemas;

import java.io.Serializable;
import org.apache.beam.sdk.annotations.Internal;
import org.apache.beam.sdk.util.Preconditions;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * <b><i>For internal use only; no backwards-compatibility guarantees.</i></b>
 *
 * <p>An interface to access a field of a class.
 *
 * <p>Implementations of this interface are generated at runtime to map object fields to Row fields.
 */
@Internal
public interface FieldValueGetter<ObjectT extends @NonNull Object, ValueT> extends Serializable {
  @Nullable ValueT get(ObjectT object);

  /** Returns the raw value of the getter before any further transformations. */
  default @Nullable Object getRaw(ObjectT object) {
    return get(object);
  }

  default boolean isNull(ObjectT object) {
    return get(object) == null;
  }

  default byte getByte(ObjectT object) {
    return ((Byte) Preconditions.checkStateNotNull(get(object))).byteValue();
  }

  default short getInt16(ObjectT object) {
    return ((Short) Preconditions.checkStateNotNull(get(object))).shortValue();
  }

  default int getInt32(ObjectT object) {
    return ((Integer) Preconditions.checkStateNotNull(get(object))).intValue();
  }

  default long getInt64(ObjectT object) {
    return ((Long) Preconditions.checkStateNotNull(get(object))).longValue();
  }

  default float getFloat(ObjectT object) {
    return ((Float) Preconditions.checkStateNotNull(get(object))).floatValue();
  }

  default double getDouble(ObjectT object) {
    return ((Double) Preconditions.checkStateNotNull(get(object))).doubleValue();
  }

  default boolean getBoolean(ObjectT object) {
    return ((Boolean) Preconditions.checkStateNotNull(get(object))).booleanValue();
  }

  String name();
}
