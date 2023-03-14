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

pub mod coders;
pub mod required_coders;
pub mod rust_coders;
pub mod standard_coders;
pub mod urns;

#[macro_use]
extern crate strum_macros;

#[cfg(test)]
mod tests {
    use super::coders::*;
    use super::required_coders::*;
    use super::rust_coders::*;
    use super::standard_coders::*;

    use std::any::Any;
    use std::fmt;

    use bytes::{Buf, BufMut};
    use serde::Deserialize;
    use serde_yaml::{Deserializer, Value};

    // TODO: empty this list
    const UNSUPPORTED_CODERS: [&'static str; 13] = [
        "beam:coder:bool:v1",
        "beam:coder:kv:v1",
        "beam:coder:interval_window:v1",
        "beam:coder:iterable:v1",
        "beam:coder:state_backed_iterable:v1",
        "beam:coder:timer:v1",
        "beam:coder:global_window:v1",
        "beam:coder:windowed_value:v1",
        "beam:coder:param_windowed_value:v1",
        "beam:coder:double:v1",
        "beam:coder:row:v1",
        "beam:coder:sharded_key:v1",
        "beam:coder:custom_window:v1",
    ];

    const STANDARD_CODERS_FILE: &str =
        "model/fn-execution/src/main/resources/org/apache/beam/model/fnexecution/v1/standard_coders.yaml";

    trait CoderTestUtils
    where
        <Self as CoderTestUtils>::InternalCoderType: Clone + fmt::Debug,
    {
        type InternalCoderType;

        fn parse_yaml_value(
            &self,
            value: &serde_yaml::Value,
        ) -> <Self as CoderTestUtils>::InternalCoderType;
    }

    impl CoderTestUtils for BytesCoder {
        type InternalCoderType = Vec<u8>;

        fn parse_yaml_value(
            &self,
            value: &serde_yaml::Value,
        ) -> <BytesCoder as CoderTestUtils>::InternalCoderType {
            value.as_str().unwrap().as_bytes().to_vec()
        }
    }

    impl<K, V> CoderTestUtils for KVCoder<KV<K, V>>
    where
        K: Clone + fmt::Debug,
        V: Clone + fmt::Debug,
    {
        type InternalCoderType = KV<K, V>;

        fn parse_yaml_value(
            &self,
            value: &serde_yaml::Value,
        ) -> <KVCoder<KV<K, V>> as CoderTestUtils>::InternalCoderType {
            unimplemented!()
        }
    }

    impl<T> CoderTestUtils for IterableCoder<T>
    where
        T: Clone + fmt::Debug,
    {
        type InternalCoderType = T;

        fn parse_yaml_value(
            &self,
            value: &serde_yaml::Value,
        ) -> <IterableCoder<T> as CoderTestUtils>::InternalCoderType {
            unimplemented!()
        }
    }

    impl CoderTestUtils for StrUtf8Coder {
        type InternalCoderType = String;

        fn parse_yaml_value(
            &self,
            value: &serde_yaml::Value,
        ) -> <StrUtf8Coder as CoderTestUtils>::InternalCoderType {
            value.as_str().unwrap().to_string()
        }
    }

    impl CoderTestUtils for VarIntCoder {
        type InternalCoderType = u64;

        fn parse_yaml_value(
            &self,
            value: &serde_yaml::Value,
        ) -> <VarIntCoder as CoderTestUtils>::InternalCoderType {
            if !value.is_u64() {
                return value.as_i64().unwrap() as u64;
            }

            value.as_u64().unwrap()
        }
    }

    #[test]
    fn test_standard_coders() {
        let coder_registry = CoderRegistry::new();

        // TODO: Move this to utils module
        let current_dir = std::env::current_dir().unwrap();
        let beam_root_dir = current_dir
            .as_path()
            .parent()
            .unwrap()
            .parent()
            .unwrap()
            .parent()
            .unwrap();
        let standard_coders_dir = beam_root_dir.join(STANDARD_CODERS_FILE);

        let f = std::fs::read(standard_coders_dir).expect("Unable to read file");

        for doc in Deserializer::from_slice(&f) {
            let spec = Value::deserialize(doc).expect("Unable to parse document");

            let urn_spec = spec
                .get("coder")
                .cloned()
                .unwrap()
                .get("urn")
                .cloned()
                .unwrap();
            let urn = urn_spec.as_str().unwrap();

            if UNSUPPORTED_CODERS.contains(&urn) {
                continue;
            }

            let nested_field = spec.get("nested");
            if nested_field.is_some() && nested_field.unwrap().as_bool().unwrap() {
                // TODO: support nesting of coders
                continue;
            }

            let coder_type = coder_registry.get_coder_type(urn);
            let nested = false;

            // TODO: generalize
            match coder_type {
                CoderTypeDiscriminants::Bytes => {
                    let c = BytesCoder::new();
                    run_unnested::<BytesCoder, Vec<u8>>(&c, nested, &spec);
                }
                CoderTypeDiscriminants::StrUtf8 => {
                    let c = StrUtf8Coder::new();
                    run_unnested::<StrUtf8Coder, String>(&c, nested, &spec);
                }
                CoderTypeDiscriminants::VarIntCoder => {
                    let c = VarIntCoder::new();
                    run_unnested::<VarIntCoder, u64>(&c, nested, &spec);
                }
                _ => unimplemented!(),
            }
        }
    }

    fn run_unnested<'a, C, E>(coder: &(dyn Any + 'a), _nested: bool, spec: &Value)
    where
        C: CoderI<E> + CoderTestUtils + CoderTestUtils<InternalCoderType = E> + 'a,
        E: Clone + std::fmt::Debug + PartialEq,
    {
        let examples = spec.get("examples").unwrap().as_mapping().unwrap();

        for (expected, original) in examples.iter() {
            // TODO: test coders for both Context types
            run_case::<C, E>(coder, expected, original);
        }
    }

    fn run_case<'a, C, E>(coder: &(dyn Any + 'a), expected_encoded: &Value, original: &Value)
    where
        C: CoderI<E> + CoderTestUtils + CoderTestUtils<InternalCoderType = E> + 'a,
        E: Clone + std::fmt::Debug + PartialEq,
    {
        let c: &C = coder.downcast_ref::<C>().unwrap();
        // The expected encodings in standard_coders.yaml need to be read as UTF-16
        let expected_enc_utf16: Vec<u16> =
            expected_encoded.as_str().unwrap().encode_utf16().collect();
        let mut expected_enc: Vec<u8> = Vec::new();
        for w in expected_enc_utf16 {
            expected_enc.push(w as u8);
        }

        let mut writer = vec![].writer();
        let mut reader = expected_enc.reader();

        // TODO: revisit when context gets fully implemented
        let context = Context::WholeStream;

        let expected_dec = c.parse_yaml_value(original);

        c.encode(expected_dec.clone(), &mut writer, &context)
            .unwrap();
        let encoded = writer.into_inner();

        let decoded = c.decode(&mut reader, &context).unwrap();

        println!("\n---------\nCoder type: {:?}", c.get_coder_type());
        println!(
            "\nExpected encoded: {:?}\nGenerated encoded: {:?}\n\nExpected decoded: {:?}\nGenerated decoded: {:?}",
            expected_enc.as_slice(), encoded, expected_dec, decoded
        );

        assert_eq!(encoded.as_slice(), expected_enc.as_slice());
        assert_eq!(decoded, expected_dec);
    }

    #[test]
    fn test_general_object_coder() {
        fn string_test() {
            let coder: GeneralObjectCoder<String> = GeneralObjectCoder::new();
            let input = "abcde".to_string();

            let mut writer = vec![].writer();
            coder
                .encode(input.clone(), &mut writer, &Context::NeedsDelimiters)
                .unwrap();
            let buf = writer.into_inner();

            let mut reader = buf.reader();
            let decoded = coder
                .decode(&mut reader, &Context::NeedsDelimiters)
                .unwrap();

            assert_eq!(input, decoded);
        }

        string_test();
        
    }
}
