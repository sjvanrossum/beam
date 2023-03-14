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

mod data;
mod external_worker_service;
mod operators;

pub use external_worker_service::ExternalWorkerPool;
pub mod sdk_worker;

#[macro_use]
extern crate strum_macros;

mod test_utils {
    use std::sync::Mutex;

    pub static mut RECORDING_OPERATOR_LOGS: Mutex<Vec<String>> = Mutex::new(Vec::new());

    pub unsafe fn reset_log() {
        let mut log = RECORDING_OPERATOR_LOGS.lock().unwrap();
        *log.as_mut() = Vec::new();
    }
}

#[cfg(test)]
mod tests {
    use std::{collections::HashMap, sync::Arc};

    use serde_json;

    use internals::urns;
    use proto::beam_api::{
        fn_execution::ProcessBundleDescriptor,
        pipeline::{FunctionSpec, PTransform},
    };

    use crate::{
        sdk_worker::BundleProcessor,
        test_utils::{reset_log, RECORDING_OPERATOR_LOGS},
    };

    fn make_ptransform(
        urn: &'static str,
        inputs: HashMap<String, String>,
        outputs: HashMap<String, String>,
        payload: Vec<u8>,
    ) -> PTransform {
        PTransform {
            unique_name: "".to_string(),
            spec: Some(FunctionSpec {
                urn: urn.to_string(),
                payload,
            }),
            subtransforms: Vec::with_capacity(0),
            inputs,
            outputs,
            display_data: Vec::with_capacity(0),
            environment_id: "".to_string(),
            annotations: HashMap::with_capacity(0),
        }
    }

    #[tokio::test]
    async fn test_operator_construction() {
        let descriptor = ProcessBundleDescriptor {
            id: "".to_string(),
            // Note the inverted order should still be resolved correctly
            transforms: HashMap::from([
                (
                    "y".to_string(),
                    make_ptransform(
                        urns::RECORDING_URN,
                        HashMap::from([("input".to_string(), "pc1".to_string())]),
                        HashMap::from([("out".to_string(), "pc2".to_string())]),
                        Vec::with_capacity(0),
                    ),
                ),
                (
                    "z".to_string(),
                    make_ptransform(
                        urns::RECORDING_URN,
                        HashMap::from([("input".to_string(), "pc2".to_string())]),
                        HashMap::with_capacity(0),
                        Vec::with_capacity(0),
                    ),
                ),
                (
                    "x".to_string(),
                    make_ptransform(
                        urns::CREATE_URN,
                        HashMap::with_capacity(0),
                        HashMap::from([("out".to_string(), "pc1".to_string())]),
                        serde_json::to_vec(&["a", "b", "c"]).unwrap(),
                    ),
                ),
            ]),
            pcollections: HashMap::with_capacity(0),
            windowing_strategies: HashMap::with_capacity(0),
            coders: HashMap::with_capacity(0),
            environments: HashMap::with_capacity(0),
            state_api_service_descriptor: None,
            timer_api_service_descriptor: None,
        };

        unsafe {
            reset_log();
        }

        let processor = BundleProcessor::new(Arc::new(descriptor), &[urns::CREATE_URN]);

        processor.process("bundle_id".to_string()).await;

        unsafe {
            let log = RECORDING_OPERATOR_LOGS.lock().unwrap();
            let _log: &Vec<String> = log.as_ref();

            assert_eq!(
                *_log,
                Vec::from([
                    "z.start_bundle()",
                    "y.start_bundle()",
                    "y.process(String(\"a\"))",
                    "z.process(String(\"a\"))",
                    "y.process(String(\"b\"))",
                    "z.process(String(\"b\"))",
                    "y.process(String(\"c\"))",
                    "z.process(String(\"c\"))",
                    "y.finish_bundle()",
                    "z.finish_bundle()",
                ])
            );
        }
    }
}
