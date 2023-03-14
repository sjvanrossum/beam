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

/// The context for encoding a PCollection element.
/// For example, for strings of utf8 characters or bytes, `WholeStream` encoding means
/// that the string will be encoded as-is; while `NeedsDelimiter` encoding means that the
/// string will be encoded prefixed with its length.
pub enum Context {
    /// Whole stream encoding/decoding means that the encoding/decoding function does not need to worry about
    /// delimiting the start and end of the current element in the stream of bytes.
    WholeStream,

    /// Needs-delimiters encoding means that the encoding of data must be such that when decoding,
    /// the coder is able to stop decoding data at the end of the current element.
    NeedsDelimiters,
}
