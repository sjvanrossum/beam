/// A descriptor for connecting to a remote port using the Beam Fn Data API.
/// Allows for communication between two environments (for example between the
/// runner and the SDK).
/// Stable
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct RemoteGrpcPort {
    /// (Required) An API descriptor which describes where to
    /// connect to including any authentication that is required.
    #[prost(message, optional, tag = "1")]
    pub api_service_descriptor: ::core::option::Option<
        super::super::pipeline::v1::ApiServiceDescriptor,
    >,
    /// (Required) The ID of the Coder that will be used to encode and decode data
    /// sent over this port.
    #[prost(string, tag = "2")]
    pub coder_id: ::prost::alloc::string::String,
}
/// Requests the ProcessBundleDescriptor with the given id.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct GetProcessBundleDescriptorRequest {
    #[prost(string, tag = "1")]
    pub process_bundle_descriptor_id: ::prost::alloc::string::String,
}
/// A request sent by a runner which the SDK is asked to fulfill.
/// For any unsupported request type, an error should be returned with a
/// matching instruction id.
/// Stable
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct InstructionRequest {
    /// (Required) A unique identifier provided by the runner which represents
    /// this requests execution. The InstructionResponse MUST have the matching id.
    #[prost(string, tag = "1")]
    pub instruction_id: ::prost::alloc::string::String,
    /// (Required) A request that the SDK Harness needs to interpret.
    #[prost(
        oneof = "instruction_request::Request",
        tags = "1001, 1002, 1003, 1004, 1005, 1006, 1000"
    )]
    pub request: ::core::option::Option<instruction_request::Request>,
}
/// Nested message and enum types in `InstructionRequest`.
pub mod instruction_request {
    /// (Required) A request that the SDK Harness needs to interpret.
    #[derive(Clone, PartialEq, ::prost::Oneof)]
    pub enum Request {
        #[prost(message, tag = "1001")]
        ProcessBundle(super::ProcessBundleRequest),
        #[prost(message, tag = "1002")]
        ProcessBundleProgress(super::ProcessBundleProgressRequest),
        #[prost(message, tag = "1003")]
        ProcessBundleSplit(super::ProcessBundleSplitRequest),
        #[prost(message, tag = "1004")]
        FinalizeBundle(super::FinalizeBundleRequest),
        #[prost(message, tag = "1005")]
        MonitoringInfos(super::MonitoringInfosMetadataRequest),
        #[prost(message, tag = "1006")]
        HarnessMonitoringInfos(super::HarnessMonitoringInfosRequest),
        /// DEPRECATED
        #[prost(message, tag = "1000")]
        Register(super::RegisterRequest),
    }
}
/// The response for an associated request the SDK had been asked to fulfill.
/// Stable
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct InstructionResponse {
    /// (Required) A reference provided by the runner which represents a requests
    /// execution. The InstructionResponse MUST have the matching id when
    /// responding to the runner.
    #[prost(string, tag = "1")]
    pub instruction_id: ::prost::alloc::string::String,
    /// If this is specified, then this instruction has failed.
    /// A human readable string representing the reason as to why processing has
    /// failed.
    #[prost(string, tag = "2")]
    pub error: ::prost::alloc::string::String,
    /// If the instruction did not fail, it is required to return an equivalent
    /// response type depending on the request this matches.
    #[prost(
        oneof = "instruction_response::Response",
        tags = "1001, 1002, 1003, 1004, 1005, 1006, 1000"
    )]
    pub response: ::core::option::Option<instruction_response::Response>,
}
/// Nested message and enum types in `InstructionResponse`.
pub mod instruction_response {
    /// If the instruction did not fail, it is required to return an equivalent
    /// response type depending on the request this matches.
    #[derive(Clone, PartialEq, ::prost::Oneof)]
    pub enum Response {
        #[prost(message, tag = "1001")]
        ProcessBundle(super::ProcessBundleResponse),
        #[prost(message, tag = "1002")]
        ProcessBundleProgress(super::ProcessBundleProgressResponse),
        #[prost(message, tag = "1003")]
        ProcessBundleSplit(super::ProcessBundleSplitResponse),
        #[prost(message, tag = "1004")]
        FinalizeBundle(super::FinalizeBundleResponse),
        #[prost(message, tag = "1005")]
        MonitoringInfos(super::MonitoringInfosMetadataResponse),
        #[prost(message, tag = "1006")]
        HarnessMonitoringInfos(super::HarnessMonitoringInfosResponse),
        /// DEPRECATED
        #[prost(message, tag = "1000")]
        Register(super::RegisterResponse),
    }
}
/// A request to provide full MonitoringInfo associated with the entire SDK
/// harness process, not specific to a bundle.
///
/// An SDK can report metrics using an identifier that only contains the
/// associated payload. A runner who wants to receive the full metrics
/// information can request all the monitoring metadata via a
/// MonitoringInfosMetadataRequest providing a list of ids as necessary.
///
/// The SDK is allowed to reuse the identifiers
/// for the lifetime of the associated control connection as long
/// as the MonitoringInfo could be reconstructed fully by overwriting its
/// payload field with the bytes specified here.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct HarnessMonitoringInfosRequest {}
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct HarnessMonitoringInfosResponse {
    /// An identifier to MonitoringInfo.payload mapping containing
    /// Metrics associated with the SDK harness, not a specific bundle.
    ///
    /// An SDK can report metrics using an identifier that only contains the
    /// associated payload. A runner who wants to receive the full metrics
    /// information can request all the monitoring metadata via a
    /// MonitoringInfosMetadataRequest providing a list of ids as necessary.
    ///
    /// The SDK is allowed to reuse the identifiers
    /// for the lifetime of the associated control connection as long
    /// as the MonitoringInfo could be reconstructed fully by overwriting its
    /// payload field with the bytes specified here.
    #[prost(map = "string, bytes", tag = "1")]
    pub monitoring_data: ::std::collections::HashMap<
        ::prost::alloc::string::String,
        ::prost::alloc::vec::Vec<u8>,
    >,
}
/// A list of objects which can be referred to by the runner in
/// future requests.
/// Stable
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct RegisterRequest {
    /// (Optional) The set of descriptors used to process bundles.
    #[prost(message, repeated, tag = "1")]
    pub process_bundle_descriptor: ::prost::alloc::vec::Vec<ProcessBundleDescriptor>,
}
/// Stable
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct RegisterResponse {}
/// Definitions that should be used to construct the bundle processing graph.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ProcessBundleDescriptor {
    /// (Required) A pipeline level unique id which can be used as a reference to
    /// refer to this.
    #[prost(string, tag = "1")]
    pub id: ::prost::alloc::string::String,
    /// (Required) A map from pipeline-scoped id to PTransform.
    #[prost(map = "string, message", tag = "2")]
    pub transforms: ::std::collections::HashMap<
        ::prost::alloc::string::String,
        super::super::pipeline::v1::PTransform,
    >,
    /// (Required) A map from pipeline-scoped id to PCollection.
    #[prost(map = "string, message", tag = "3")]
    pub pcollections: ::std::collections::HashMap<
        ::prost::alloc::string::String,
        super::super::pipeline::v1::PCollection,
    >,
    /// (Required) A map from pipeline-scoped id to WindowingStrategy.
    #[prost(map = "string, message", tag = "4")]
    pub windowing_strategies: ::std::collections::HashMap<
        ::prost::alloc::string::String,
        super::super::pipeline::v1::WindowingStrategy,
    >,
    /// (Required) A map from pipeline-scoped id to Coder.
    #[prost(map = "string, message", tag = "5")]
    pub coders: ::std::collections::HashMap<
        ::prost::alloc::string::String,
        super::super::pipeline::v1::Coder,
    >,
    /// (Required) A map from pipeline-scoped id to Environment.
    #[prost(map = "string, message", tag = "6")]
    pub environments: ::std::collections::HashMap<
        ::prost::alloc::string::String,
        super::super::pipeline::v1::Environment,
    >,
    /// A descriptor describing the end point to use for State API
    /// calls. Required if the Runner intends to send remote references over the
    /// data plane or if any of the transforms rely on user state or side inputs.
    #[prost(message, optional, tag = "7")]
    pub state_api_service_descriptor: ::core::option::Option<
        super::super::pipeline::v1::ApiServiceDescriptor,
    >,
    /// A descriptor describing the end point to use for Data API for user timers.
    /// Required if the ProcessBundleDescriptor contains any transforms that have user timers.
    #[prost(message, optional, tag = "8")]
    pub timer_api_service_descriptor: ::core::option::Option<
        super::super::pipeline::v1::ApiServiceDescriptor,
    >,
}
/// One of the applications specifying the scope of work for a bundle.
/// See
/// <https://docs.google.com/document/d/1tUDb45sStdR8u7-jBkGdw3OGFK7aa2-V7eo86zYSE_4/edit#heading=h.9g3g5weg2u9>
/// for further details.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct BundleApplication {
    /// (Required) The transform to which to pass the element
    #[prost(string, tag = "1")]
    pub transform_id: ::prost::alloc::string::String,
    /// (Required) Name of the transform's input to which to pass the element.
    #[prost(string, tag = "2")]
    pub input_id: ::prost::alloc::string::String,
    /// (Required) The encoded element to pass to the transform.
    #[prost(bytes = "vec", tag = "3")]
    pub element: ::prost::alloc::vec::Vec<u8>,
    /// The map is keyed by the local output name of the PTransform. Each
    /// value represents a lower bound on the timestamps of elements that
    /// are produced by this PTransform into each of its output PCollections
    /// when invoked with this application.
    ///
    /// If there is no watermark reported from RestrictionTracker, the runner will
    /// use MIN_TIMESTAMP by default.
    #[prost(map = "string, message", tag = "4")]
    pub output_watermarks: ::std::collections::HashMap<
        ::prost::alloc::string::String,
        ::prost_types::Timestamp,
    >,
    /// Whether this application potentially produces an unbounded
    /// amount of data. Note that this should only be set to BOUNDED if and
    /// only if the application is known to produce a finite amount of output.
    #[prost(enumeration = "super::super::pipeline::v1::is_bounded::Enum", tag = "5")]
    pub is_bounded: i32,
}
/// An Application should be scheduled for execution after a delay.
/// Either an absolute timestamp or a relative timestamp can represent a
/// scheduled execution time.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct DelayedBundleApplication {
    /// (Required) The application that should be scheduled.
    #[prost(message, optional, tag = "1")]
    pub application: ::core::option::Option<BundleApplication>,
    /// Recommended time delay at which the application should be scheduled to
    /// execute by the runner. Time delay that equals 0 may be scheduled to execute
    /// immediately. The unit of time delay should be microsecond.
    #[prost(message, optional, tag = "2")]
    pub requested_time_delay: ::core::option::Option<::prost_types::Duration>,
}
/// A request to process a given bundle.
/// Stable
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ProcessBundleRequest {
    /// (Required) A reference to the process bundle descriptor that must be
    /// instantiated and executed by the SDK harness.
    #[prost(string, tag = "1")]
    pub process_bundle_descriptor_id: ::prost::alloc::string::String,
    /// (Optional) A list of cache tokens that can be used by an SDK to reuse
    /// cached data returned by the State API across multiple bundles.
    ///
    /// Note that SDKs that can efficiently consume this field should declare
    /// the beam:protocol:state_caching:v1 capability enabling runners to reduce
    /// the amount of memory used.
    ///
    /// See <https://s.apache.org/beam-fn-state-api-and-bundle-processing#heading=h.7ghoih5aig5m>
    /// for additional details on how to use the cache token with the State API
    /// to cache data across bundle boundaries.
    #[prost(message, repeated, tag = "2")]
    pub cache_tokens: ::prost::alloc::vec::Vec<process_bundle_request::CacheToken>,
    /// (Optional) Elements to be processed with the bundle. Either all or
    /// none of the bundle elements should be included in the ProcessBundleRequest.
    /// This embedding is to achieve better efficiency for bundles that contain
    /// only small amounts of data and are cheap to be processed on the SDK harness
    /// side. This field can be set only if the SDK declares that it supports the
    /// beam:protocol:control_request_elements_embedding:v1 capability. See more
    /// at <https://s.apache.org/beam-fn-api-control-data-embedding.>
    #[prost(message, optional, tag = "3")]
    pub elements: ::core::option::Option<Elements>,
}
/// Nested message and enum types in `ProcessBundleRequest`.
pub mod process_bundle_request {
    /// Contains the cache token and also defines the scope of what the token applies to.
    ///
    /// See <https://s.apache.org/beam-fn-state-api-and-bundle-processing#heading=h.7ghoih5aig5m>
    /// for additional details on how to use the cache token with the State API
    /// to cache data across bundle boundaries.
    #[derive(Clone, PartialEq, ::prost::Message)]
    pub struct CacheToken {
        /// An opaque token used with the StateKey to create a globally unique
        /// identifier.
        #[prost(bytes = "vec", tag = "10")]
        pub token: ::prost::alloc::vec::Vec<u8>,
        /// The scope of a cache token.
        #[prost(oneof = "cache_token::Type", tags = "1, 2")]
        pub r#type: ::core::option::Option<cache_token::Type>,
    }
    /// Nested message and enum types in `CacheToken`.
    pub mod cache_token {
        /// A flag to indicate a cache token is valid for all user state.
        #[derive(Clone, PartialEq, ::prost::Message)]
        pub struct UserState {}
        /// A flag to indicate a cache token is valid for a side input.
        #[derive(Clone, PartialEq, ::prost::Message)]
        pub struct SideInput {
            /// (Required) The id of the PTransform containing a side input.
            #[prost(string, tag = "1")]
            pub transform_id: ::prost::alloc::string::String,
            /// (Required) The id of the side input.
            #[prost(string, tag = "2")]
            pub side_input_id: ::prost::alloc::string::String,
        }
        /// The scope of a cache token.
        #[derive(Clone, PartialEq, ::prost::Oneof)]
        pub enum Type {
            #[prost(message, tag = "1")]
            UserState(UserState),
            #[prost(message, tag = "2")]
            SideInput(SideInput),
        }
    }
}
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ProcessBundleResponse {
    /// (Optional) Specifies that the bundle has not been completed and the
    /// following applications need to be scheduled and executed in the future.
    /// A runner that does not yet support residual roots MUST still check that
    /// this is empty for correctness.
    ///
    /// Note that these residual roots must not have been returned as part of a
    /// prior split for this bundle.
    #[prost(message, repeated, tag = "2")]
    pub residual_roots: ::prost::alloc::vec::Vec<DelayedBundleApplication>,
    /// DEPRECATED (Required) The list of metrics or other MonitoredState
    /// collected while processing this bundle.
    #[prost(message, repeated, tag = "3")]
    pub monitoring_infos: ::prost::alloc::vec::Vec<
        super::super::pipeline::v1::MonitoringInfo,
    >,
    /// (Optional) Specifies that the runner must callback to this worker
    /// once the output of the bundle is committed. The Runner must send a
    /// FinalizeBundleRequest with the instruction id of the ProcessBundleRequest
    /// that is related to this ProcessBundleResponse.
    #[prost(bool, tag = "4")]
    pub requires_finalization: bool,
    /// An identifier to MonitoringInfo.payload mapping.
    ///
    /// An SDK can report metrics using an identifier that only contains the
    /// associated payload. A runner who wants to receive the full metrics
    /// information can request all the monitoring metadata via a
    /// MonitoringInfosMetadataRequest providing a list of ids as necessary.
    ///
    /// The SDK is allowed to reuse the identifiers across multiple bundles as long
    /// as the MonitoringInfo could be reconstructed fully by overwriting its
    /// payload field with the bytes specified here.
    #[prost(map = "string, bytes", tag = "5")]
    pub monitoring_data: ::std::collections::HashMap<
        ::prost::alloc::string::String,
        ::prost::alloc::vec::Vec<u8>,
    >,
    /// (Optional) Output elements of the processed bundle. Either all or
    /// none of the bundle elements should be included in the ProcessBundleResponse.
    /// This embedding is to achieve better efficiency for bundles that only
    /// contain small amounts of data. his field can be set only if the runner
    /// declares that it supports the
    /// beam:protocol:control_request_elements_embedding:v1 capability. See more at
    /// <https://s.apache.org/beam-fn-api-control-data-embedding.>
    #[prost(message, optional, tag = "6")]
    pub elements: ::core::option::Option<Elements>,
}
/// A request to report progress information for a given bundle.
/// This is an optional request to be handled and is used to support advanced
/// SDK features such as SplittableDoFn, user level metrics etc.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ProcessBundleProgressRequest {
    /// (Required) A reference to an active process bundle request with the given
    /// instruction id.
    #[prost(string, tag = "1")]
    pub instruction_id: ::prost::alloc::string::String,
}
/// A request to provide full MonitoringInfo for a set of provided ids.
///
/// An SDK can report metrics using an identifier that only contains the
/// associated payload. A runner who wants to receive the full metrics
/// information can request all the monitoring metadata via a
/// MonitoringInfosMetadataRequest providing a list of ids as necessary.
///
/// The SDK is allowed to reuse the identifiers for the lifetime of the
/// associated control connection as long as the MonitoringInfo could be
/// reconstructed fully by overwriting its payload field with the bytes specified
/// here.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct MonitoringInfosMetadataRequest {
    /// A list of ids for which the full MonitoringInfo is requested for.
    #[prost(string, repeated, tag = "1")]
    pub monitoring_info_id: ::prost::alloc::vec::Vec<::prost::alloc::string::String>,
}
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ProcessBundleProgressResponse {
    /// DEPRECATED (Required) The list of metrics or other MonitoredState
    /// collected while processing this bundle.
    #[prost(message, repeated, tag = "3")]
    pub monitoring_infos: ::prost::alloc::vec::Vec<
        super::super::pipeline::v1::MonitoringInfo,
    >,
    /// An identifier to MonitoringInfo.payload mapping.
    ///
    /// An SDK can report metrics using an identifier that only contains the
    /// associated payload. A runner who wants to receive the full metrics
    /// information can request all the monitoring metadata via a
    /// MonitoringInfosMetadataRequest providing a list of ids as necessary.
    ///
    /// The SDK is allowed to reuse the identifiers
    /// for the lifetime of the associated control connection as long
    /// as the MonitoringInfo could be reconstructed fully by overwriting its
    /// payload field with the bytes specified here.
    #[prost(map = "string, bytes", tag = "5")]
    pub monitoring_data: ::std::collections::HashMap<
        ::prost::alloc::string::String,
        ::prost::alloc::vec::Vec<u8>,
    >,
}
/// A response that contains the full mapping information associated with
/// a specified set of identifiers.
///
/// An SDK can report metrics using an identifier that only contains the
/// associated payload. A runner who wants to receive the full metrics
/// information can request all the monitoring metadata via a
/// MonitoringInfosMetadataRequest providing a list of ids as necessary.
///
/// The SDK is allowed to reuse the identifiers
/// for the lifetime of the associated control connection as long
/// as the MonitoringInfo could be reconstructed fully by overwriting its
/// payload field with the bytes specified here.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct MonitoringInfosMetadataResponse {
    /// A mapping from an identifier to the full metrics information.
    #[prost(map = "string, message", tag = "1")]
    pub monitoring_info: ::std::collections::HashMap<
        ::prost::alloc::string::String,
        super::super::pipeline::v1::MonitoringInfo,
    >,
}
/// Represents a request to the SDK to split a currently active bundle.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ProcessBundleSplitRequest {
    /// (Required) A reference to an active process bundle request with the given
    /// instruction id.
    #[prost(string, tag = "1")]
    pub instruction_id: ::prost::alloc::string::String,
    /// (Required) Specifies the desired split for each transform.
    ///
    /// Currently only splits at gRPC read operations are supported.
    /// This may, of course, limit the amount of work downstream operations
    /// receive.
    #[prost(map = "string, message", tag = "3")]
    pub desired_splits: ::std::collections::HashMap<
        ::prost::alloc::string::String,
        process_bundle_split_request::DesiredSplit,
    >,
}
/// Nested message and enum types in `ProcessBundleSplitRequest`.
pub mod process_bundle_split_request {
    /// A message specifying the desired split for a single transform.
    #[derive(Clone, PartialEq, ::prost::Message)]
    pub struct DesiredSplit {
        /// (Required) The fraction of known work remaining in this bundle
        /// for this transform that should be kept by the SDK after this split.
        ///
        /// Set to 0 to "checkpoint" as soon as possible (keeping as little work as
        /// possible and returning the remainder).
        #[prost(double, tag = "1")]
        pub fraction_of_remainder: f64,
        /// (Optional) A set of allowed element indices where the SDK may split. When
        /// this is empty, there are no constraints on where to split.
        #[prost(int64, repeated, tag = "3")]
        pub allowed_split_points: ::prost::alloc::vec::Vec<i64>,
        /// (Required for gRPC Read operation transforms) Number of total elements
        /// expected to be sent to this GrpcRead operation, required to correctly
        /// account for unreceived data when determining where to split.
        #[prost(int64, tag = "2")]
        pub estimated_input_elements: i64,
    }
}
/// Represents a partition of the bundle: a "primary" and a "residual", with the
/// following properties:
/// - The work in primary and residual doesn't overlap, and combined, adds up
///    to the work in the current bundle if the split hadn't happened.
/// - The current bundle, if it keeps executing, will have done exactly none of
///    the work under residual_roots and none of the elements at and beyond the
///    first_residual_element.
/// - The current bundle, if no further splits happen, will have done exactly
///    the work under primary_roots and all elements up to and including the
///    channel splits last_primary_element.
///
/// This allows the SDK to relinquish ownership of and commit to not process some
/// of the elements that it may have been sent (the residual) while retaining
/// ownership and commitment to finish the other portion (the primary).
///
/// For example, lets say the SDK is processing elements A B C D E and a split
/// request comes in. The SDK could return a response with a channel split
/// representing a last_primary_element of 3 (D) and first_residual_element of 4
/// (E). The SDK is now responsible for processing A B C D and the runner must
/// process E in the future. A future split request could have the SDK split the
/// elements B into B1 and B2 and C into C1 and C2 representing their primary and
/// residual roots. The SDK would return a response with a channel split
/// representing a last_primary_element of 0 (A) and first_residual_element of 3
/// (D) with primary_roots (B1, C1) and residual_roots (B2, C2). The SDK is now
/// responsible for processing A B1 C1 and the runner must process C2 D2 (and E
/// from the prior split) in the future. Yet another future split request could
/// have the SDK could split B1 further into B1a and B1b primary and residuals
/// and return C2 as a residual (assuming C2 was left unprocessed). The SDK would
/// return a response with a channel split representing a last_primary_element of
/// 0 (A) and first_residual_element of 4 (E) with primary_roots (B1a) and
/// residual_roots (B1b, C1). The SDK is now responsible for processing A B1a the
/// runner must process B1b C1 (in addition to C2, D, E from prior splits) in the
/// future.
///
/// For more rigorous definitions see <https://s.apache.org/beam-breaking-fusion>
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ProcessBundleSplitResponse {
    /// (Optional) Root applications that should replace the current bundle.
    ///
    /// Note that primary roots can only be specified if a channel split's
    /// last_primary_element + 1 < first_residual_element
    ///
    /// Note that there must be a corresponding residual root contained within
    /// residual_roots representing the remainder of processing for the original
    /// element this this primary root represents a fraction of.
    #[prost(message, repeated, tag = "1")]
    pub primary_roots: ::prost::alloc::vec::Vec<BundleApplication>,
    /// (Optional) Root applications that have been removed from the current bundle and
    /// have to be executed in a separate bundle (e.g. in parallel on a different
    /// worker, or after the current bundle completes, etc.)
    ///
    /// Note that residual roots can only be specified if a channel split's
    /// last_primary_element + 1 < first_residual_element
    ///
    /// Note that there must be a corresponding primary root contained within
    /// primary_roots representing the remainder of processing for the original
    /// element this this residual root represents a fraction of.
    ///
    /// Note that subsequent splits must not return prior residual roots.
    #[prost(message, repeated, tag = "2")]
    pub residual_roots: ::prost::alloc::vec::Vec<DelayedBundleApplication>,
    /// (Required) Partitions of input data channels into primary and residual
    /// elements, if any. Must not include any elements represented in the bundle
    /// applications roots above of the current split or any prior split of the
    /// same bundle.
    #[prost(message, repeated, tag = "3")]
    pub channel_splits: ::prost::alloc::vec::Vec<
        process_bundle_split_response::ChannelSplit,
    >,
}
/// Nested message and enum types in `ProcessBundleSplitResponse`.
pub mod process_bundle_split_response {
    /// Represents contiguous portions of the data channel that are either
    /// entirely processed or entirely unprocessed and belong to the primary
    /// or residual respectively.
    ///
    /// This affords both a more efficient representation over the FnAPI
    /// (if the bundle is large) and often a more efficient representation
    /// on the runner side (e.g. if the set of elements can be represented
    /// as some range in an underlying dataset).
    ///
    /// Note that for a split the following properties must hold:
    /// - last_primary_element < first_residual_element
    /// - primary roots and residual roots can only be specified if the
    ///    last_primary_element + 1 < first_residual_element
    ///    (typically there is one primary and residual root per element in the
    ///    range (last_primary_element, first_residual_element))
    /// - primary roots and residual roots must represent a disjoint but full
    ///    coverage of work represented by the elements between last_primary_element
    ///    and first_residual_element
    ///
    /// Note that subsequent splits of the same bundle must ensure that:
    /// - the first_residual_element does not increase
    /// - the first_residual_element does not decrease if there were residual
    ///    or primary roots returned in a prior split.
    #[derive(Clone, PartialEq, ::prost::Message)]
    pub struct ChannelSplit {
        /// (Required) The grpc read transform reading this channel.
        #[prost(string, tag = "1")]
        pub transform_id: ::prost::alloc::string::String,
        /// (Required) The last element of the input channel that should be entirely
        /// considered part of the primary, identified by its absolute zero-based
        /// index in the (ordered) channel.
        #[prost(int64, tag = "2")]
        pub last_primary_element: i64,
        /// (Required) The first element of the input channel that should be entirely
        /// considered part of the residual, identified by its absolute zero-based
        /// index in the (ordered) channel.
        #[prost(int64, tag = "3")]
        pub first_residual_element: i64,
    }
}
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct FinalizeBundleRequest {
    /// (Required) A reference to a completed process bundle request with the given
    /// instruction id.
    #[prost(string, tag = "1")]
    pub instruction_id: ::prost::alloc::string::String,
}
/// Empty
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct FinalizeBundleResponse {}
/// Messages used to represent logical byte streams.
/// Stable
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct Elements {
    /// (Optional) A list containing parts of logical byte streams.
    #[prost(message, repeated, tag = "1")]
    pub data: ::prost::alloc::vec::Vec<elements::Data>,
    /// (Optional)  A list of timer byte streams.
    #[prost(message, repeated, tag = "2")]
    pub timers: ::prost::alloc::vec::Vec<elements::Timers>,
}
/// Nested message and enum types in `Elements`.
pub mod elements {
    /// Represents multiple encoded elements in nested context for a given named
    /// instruction and transform.
    #[derive(Clone, PartialEq, ::prost::Message)]
    pub struct Data {
        /// (Required) A reference to an active instruction request with the given
        /// instruction id.
        #[prost(string, tag = "1")]
        pub instruction_id: ::prost::alloc::string::String,
        /// (Required) A definition representing a consumer or producer of this data.
        /// If received by a harness, this represents the consumer within that
        /// harness that should consume these bytes. If sent by a harness, this
        /// represents the producer of these bytes.
        ///
        /// Note that a single element may span multiple Data messages.
        ///
        /// Note that a sending/receiving pair should share the same identifier.
        #[prost(string, tag = "2")]
        pub transform_id: ::prost::alloc::string::String,
        /// (Optional) Represents a part of a logical byte stream. Elements within
        /// the logical byte stream are encoded in the nested context and
        /// concatenated together.
        #[prost(bytes = "vec", tag = "3")]
        pub data: ::prost::alloc::vec::Vec<u8>,
        /// (Optional) Set this bit to indicate the this is the last data block
        /// for the given instruction and transform, ending the stream.
        #[prost(bool, tag = "4")]
        pub is_last: bool,
    }
    /// Represent the encoded user timer for a given instruction, transform and
    /// timer id.
    #[derive(Clone, PartialEq, ::prost::Message)]
    pub struct Timers {
        /// (Required) A reference to an active instruction request with the given
        /// instruction id.
        #[prost(string, tag = "1")]
        pub instruction_id: ::prost::alloc::string::String,
        /// (Required) A definition representing a consumer or producer of this data.
        /// If received by a harness, this represents the consumer within that
        /// harness that should consume these timers. If sent by a harness, this
        /// represents the producer of these timers.
        #[prost(string, tag = "2")]
        pub transform_id: ::prost::alloc::string::String,
        /// (Required) The local timer family name used to identify the associated
        /// timer family specification
        #[prost(string, tag = "3")]
        pub timer_family_id: ::prost::alloc::string::String,
        /// (Optional) Represents a logical byte stream of timers. Encoded according
        /// to the coder in the timer spec.
        #[prost(bytes = "vec", tag = "4")]
        pub timers: ::prost::alloc::vec::Vec<u8>,
        /// (Optional) Set this bit to indicate the this is the last data block
        /// for the given instruction and transform, ending the stream.
        #[prost(bool, tag = "5")]
        pub is_last: bool,
    }
}
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct StateRequest {
    /// (Required) A unique identifier provided by the SDK which represents this
    /// requests execution. The StateResponse corresponding with this request
    /// will have the matching id.
    #[prost(string, tag = "1")]
    pub id: ::prost::alloc::string::String,
    /// (Required) The associated instruction id of the work that is currently
    /// being processed. This allows for the runner to associate any modifications
    /// to state to be committed with the appropriate work execution.
    #[prost(string, tag = "2")]
    pub instruction_id: ::prost::alloc::string::String,
    /// (Required) The state key this request is for.
    #[prost(message, optional, tag = "3")]
    pub state_key: ::core::option::Option<StateKey>,
    /// (Required) The action to take on this request.
    #[prost(oneof = "state_request::Request", tags = "1000, 1001, 1002")]
    pub request: ::core::option::Option<state_request::Request>,
}
/// Nested message and enum types in `StateRequest`.
pub mod state_request {
    /// (Required) The action to take on this request.
    #[derive(Clone, PartialEq, ::prost::Oneof)]
    pub enum Request {
        /// A request to get state.
        #[prost(message, tag = "1000")]
        Get(super::StateGetRequest),
        /// A request to append to state.
        #[prost(message, tag = "1001")]
        Append(super::StateAppendRequest),
        /// A request to clear state.
        #[prost(message, tag = "1002")]
        Clear(super::StateClearRequest),
    }
}
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct StateResponse {
    /// (Required) A reference provided by the SDK which represents a requests
    /// execution. The StateResponse must have the matching id when responding
    /// to the SDK.
    #[prost(string, tag = "1")]
    pub id: ::prost::alloc::string::String,
    /// (Optional) If this is specified, then the state request has failed.
    /// A human readable string representing the reason as to why the request
    /// failed.
    #[prost(string, tag = "2")]
    pub error: ::prost::alloc::string::String,
    /// A corresponding response matching the request will be populated.
    #[prost(oneof = "state_response::Response", tags = "1000, 1001, 1002")]
    pub response: ::core::option::Option<state_response::Response>,
}
/// Nested message and enum types in `StateResponse`.
pub mod state_response {
    /// A corresponding response matching the request will be populated.
    #[derive(Clone, PartialEq, ::prost::Oneof)]
    pub enum Response {
        /// A response to getting state.
        #[prost(message, tag = "1000")]
        Get(super::StateGetResponse),
        /// A response to appending to state.
        #[prost(message, tag = "1001")]
        Append(super::StateAppendResponse),
        /// A response to clearing state.
        #[prost(message, tag = "1002")]
        Clear(super::StateClearResponse),
    }
}
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct StateKey {
    /// (Required) One of the following state keys must be set.
    #[prost(oneof = "state_key::Type", tags = "1, 2, 3, 4, 5, 6, 7")]
    pub r#type: ::core::option::Option<state_key::Type>,
}
/// Nested message and enum types in `StateKey`.
pub mod state_key {
    #[derive(Clone, PartialEq, ::prost::Message)]
    pub struct Runner {
        /// (Required) Opaque information supplied by the runner. Used to support
        /// remote references.
        /// <https://s.apache.org/beam-fn-api-send-and-receive-data>
        ///
        /// Used by state backed iterable. And in this use case, request type can
        /// only be of type get. Details see:
        /// <https://s.apache.org/beam-fn-api-state-backed-iterables>
        #[prost(bytes = "vec", tag = "1")]
        pub key: ::prost::alloc::vec::Vec<u8>,
    }
    /// Represents a request for the values associated with a specified window
    /// in a PCollection. See
    /// <https://s.apache.org/beam-fn-state-api-and-bundle-processing> for further
    /// details.
    ///
    /// Can only be used to perform StateGetRequests on side inputs of the URN
    /// beam:side_input:iterable:v1.
    ///
    /// For a PCollection<V>, the response data stream will be a concatenation
    /// of all V's. See <https://s.apache.org/beam-fn-api-send-and-receive-data>
    /// for further details.
    #[derive(Clone, PartialEq, ::prost::Message)]
    pub struct IterableSideInput {
        /// (Required) The id of the PTransform containing a side input.
        #[prost(string, tag = "1")]
        pub transform_id: ::prost::alloc::string::String,
        /// (Required) The id of the side input.
        #[prost(string, tag = "2")]
        pub side_input_id: ::prost::alloc::string::String,
        /// (Required) The window (after mapping the currently executing elements
        /// window into the side input windows domain) encoded in a nested context.
        #[prost(bytes = "vec", tag = "3")]
        pub window: ::prost::alloc::vec::Vec<u8>,
    }
    /// Represents a request for the values associated with a specified user key
    /// and window in a PCollection. See
    /// <https://s.apache.org/beam-fn-state-api-and-bundle-processing> for further
    /// details.
    ///
    /// Can only be used to perform StateGetRequests on side inputs of the URN
    /// beam:side_input:multimap:v1.
    ///
    /// For a PCollection<KV<K, V>>, the response data stream will be a
    /// concatenation of all V's associated with the specified key K. See
    /// <https://s.apache.org/beam-fn-api-send-and-receive-data> for further
    /// details.
    #[derive(Clone, PartialEq, ::prost::Message)]
    pub struct MultimapSideInput {
        /// (Required) The id of the PTransform containing a side input.
        #[prost(string, tag = "1")]
        pub transform_id: ::prost::alloc::string::String,
        /// (Required) The id of the side input.
        #[prost(string, tag = "2")]
        pub side_input_id: ::prost::alloc::string::String,
        /// (Required) The window (after mapping the currently executing elements
        /// window into the side input windows domain) encoded in a nested context.
        #[prost(bytes = "vec", tag = "3")]
        pub window: ::prost::alloc::vec::Vec<u8>,
        /// (Required) The key encoded in a nested context.
        #[prost(bytes = "vec", tag = "4")]
        pub key: ::prost::alloc::vec::Vec<u8>,
    }
    /// Represents a request for the keys associated with a specified window in a PCollection. See
    /// <https://s.apache.org/beam-fn-state-api-and-bundle-processing> for further
    /// details.
    ///
    /// Can only be used to perform StateGetRequests on side inputs of the URN
    /// beam:side_input:multimap:v1.
    ///
    /// For a PCollection<KV<K, V>>, the response data stream will be a
    /// concatenation of all K's associated with the specified window. See
    /// <https://s.apache.org/beam-fn-api-send-and-receive-data> for further
    /// details.
    #[derive(Clone, PartialEq, ::prost::Message)]
    pub struct MultimapKeysSideInput {
        /// (Required) The id of the PTransform containing a side input.
        #[prost(string, tag = "1")]
        pub transform_id: ::prost::alloc::string::String,
        /// (Required) The id of the side input.
        #[prost(string, tag = "2")]
        pub side_input_id: ::prost::alloc::string::String,
        /// (Required) The window (after mapping the currently executing elements
        /// window into the side input windows domain) encoded in a nested context.
        #[prost(bytes = "vec", tag = "3")]
        pub window: ::prost::alloc::vec::Vec<u8>,
    }
    /// Represents a request for an unordered set of values associated with a
    /// specified user key and window for a PTransform. See
    /// <https://s.apache.org/beam-fn-state-api-and-bundle-processing> for further
    /// details.
    ///
    /// The response data stream will be a concatenation of all V's associated
    /// with the specified user key and window.
    /// See <https://s.apache.org/beam-fn-api-send-and-receive-data> for further
    /// details.
    #[derive(Clone, PartialEq, ::prost::Message)]
    pub struct BagUserState {
        /// (Required) The id of the PTransform containing user state.
        #[prost(string, tag = "1")]
        pub transform_id: ::prost::alloc::string::String,
        /// (Required) The id of the user state.
        #[prost(string, tag = "2")]
        pub user_state_id: ::prost::alloc::string::String,
        /// (Required) The window encoded in a nested context.
        #[prost(bytes = "vec", tag = "3")]
        pub window: ::prost::alloc::vec::Vec<u8>,
        /// (Required) The key of the currently executing element encoded in a
        /// nested context.
        #[prost(bytes = "vec", tag = "4")]
        pub key: ::prost::alloc::vec::Vec<u8>,
    }
    /// Represents a request for the keys of a multimap associated with a specified
    /// user key and window for a PTransform. See
    /// <https://s.apache.org/beam-fn-state-api-and-bundle-processing> for further
    /// details.
    ///
    /// Can only be used to perform StateGetRequests and StateClearRequests on the
    /// user state.
    ///
    /// The response data stream will be a concatenation of all K's associated
    /// with the specified user key and window.
    /// See <https://s.apache.org/beam-fn-api-send-and-receive-data> for further
    /// details.
    #[derive(Clone, PartialEq, ::prost::Message)]
    pub struct MultimapKeysUserState {
        /// (Required) The id of the PTransform containing user state.
        #[prost(string, tag = "1")]
        pub transform_id: ::prost::alloc::string::String,
        /// (Required) The id of the user state.
        #[prost(string, tag = "2")]
        pub user_state_id: ::prost::alloc::string::String,
        /// (Required) The window encoded in a nested context.
        #[prost(bytes = "vec", tag = "3")]
        pub window: ::prost::alloc::vec::Vec<u8>,
        /// (Required) The key of the currently executing element encoded in a
        /// nested context.
        #[prost(bytes = "vec", tag = "4")]
        pub key: ::prost::alloc::vec::Vec<u8>,
    }
    /// Represents a request for the values of the map key associated with a
    /// specified user key and window for a PTransform. See
    /// <https://s.apache.org/beam-fn-state-api-and-bundle-processing> for further
    /// details.
    ///
    /// The response data stream will be a concatenation of all V's associated
    /// with the specified map key, user key, and window.
    /// See <https://s.apache.org/beam-fn-api-send-and-receive-data> for further
    /// details.
    #[derive(Clone, PartialEq, ::prost::Message)]
    pub struct MultimapUserState {
        /// (Required) The id of the PTransform containing user state.
        #[prost(string, tag = "1")]
        pub transform_id: ::prost::alloc::string::String,
        /// (Required) The id of the user state.
        #[prost(string, tag = "2")]
        pub user_state_id: ::prost::alloc::string::String,
        /// (Required) The window encoded in a nested context.
        #[prost(bytes = "vec", tag = "3")]
        pub window: ::prost::alloc::vec::Vec<u8>,
        /// (Required) The key of the currently executing element encoded in a
        /// nested context.
        #[prost(bytes = "vec", tag = "4")]
        pub key: ::prost::alloc::vec::Vec<u8>,
        /// (Required) The map key encoded in a nested context.
        #[prost(bytes = "vec", tag = "5")]
        pub map_key: ::prost::alloc::vec::Vec<u8>,
    }
    /// (Required) One of the following state keys must be set.
    #[derive(Clone, PartialEq, ::prost::Oneof)]
    pub enum Type {
        #[prost(message, tag = "1")]
        Runner(Runner),
        #[prost(message, tag = "2")]
        MultimapSideInput(MultimapSideInput),
        #[prost(message, tag = "3")]
        BagUserState(BagUserState),
        #[prost(message, tag = "4")]
        IterableSideInput(IterableSideInput),
        #[prost(message, tag = "5")]
        MultimapKeysSideInput(MultimapKeysSideInput),
        #[prost(message, tag = "6")]
        MultimapKeysUserState(MultimapKeysUserState),
        #[prost(message, tag = "7")]
        MultimapUserState(MultimapUserState),
    }
}
/// A request to get state.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct StateGetRequest {
    /// (Optional) If specified, signals to the runner that the response
    /// should resume from the following continuation token.
    ///
    /// If unspecified, signals to the runner that the response should start
    /// from the beginning of the logical continuable stream.
    #[prost(bytes = "vec", tag = "1")]
    pub continuation_token: ::prost::alloc::vec::Vec<u8>,
}
/// A response to get state representing a logical byte stream which can be
/// continued using the state API.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct StateGetResponse {
    /// (Optional) If specified, represents a token which can be used with the
    /// state API to get the next chunk of this logical byte stream. The end of
    /// the logical byte stream is signalled by this field being unset.
    #[prost(bytes = "vec", tag = "1")]
    pub continuation_token: ::prost::alloc::vec::Vec<u8>,
    /// Represents a part of a logical byte stream. Elements within
    /// the logical byte stream are encoded in the nested context and
    /// concatenated together.
    #[prost(bytes = "vec", tag = "2")]
    pub data: ::prost::alloc::vec::Vec<u8>,
}
/// A request to append state.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct StateAppendRequest {
    /// Represents a part of a logical byte stream. Elements within
    /// the logical byte stream are encoded in the nested context and
    /// multiple append requests are concatenated together.
    #[prost(bytes = "vec", tag = "1")]
    pub data: ::prost::alloc::vec::Vec<u8>,
}
/// A response to append state.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct StateAppendResponse {}
/// A request to clear state.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct StateClearRequest {}
/// A response to clear state.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct StateClearResponse {}
/// A log entry
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct LogEntry {
    /// (Required) The severity of the log statement.
    #[prost(enumeration = "log_entry::severity::Enum", tag = "1")]
    pub severity: i32,
    /// (Required) The time at which this log statement occurred.
    #[prost(message, optional, tag = "2")]
    pub timestamp: ::core::option::Option<::prost_types::Timestamp>,
    /// (Required) A human readable message.
    #[prost(string, tag = "3")]
    pub message: ::prost::alloc::string::String,
    /// (Optional) An optional trace of the functions involved. For example, in
    /// Java this can include multiple causes and multiple suppressed exceptions.
    #[prost(string, tag = "4")]
    pub trace: ::prost::alloc::string::String,
    /// (Optional) A reference to the instruction this log statement is associated
    /// with.
    #[prost(string, tag = "5")]
    pub instruction_id: ::prost::alloc::string::String,
    /// (Optional) A reference to the transform this log statement is
    /// associated with.
    #[prost(string, tag = "6")]
    pub transform_id: ::prost::alloc::string::String,
    /// (Optional) Human-readable name of the function or method being invoked,
    /// with optional context such as the class or package name. The format can
    /// vary by language. For example:
    ///    qual.if.ied.Class.method (Java)
    ///    dir/package.func (Go)
    ///    module.function (Python)
    ///    file.cc:382 (C++)
    #[prost(string, tag = "7")]
    pub log_location: ::prost::alloc::string::String,
    /// (Optional) The name of the thread this log statement is associated with.
    #[prost(string, tag = "8")]
    pub thread: ::prost::alloc::string::String,
}
/// Nested message and enum types in `LogEntry`.
pub mod log_entry {
    /// A list of log entries, enables buffering and batching of multiple
    /// log messages using the logging API.
    #[derive(Clone, PartialEq, ::prost::Message)]
    pub struct List {
        /// (Required) One or or more log messages.
        #[prost(message, repeated, tag = "1")]
        pub log_entries: ::prost::alloc::vec::Vec<super::LogEntry>,
    }
    /// The severity of the event described in a log entry, expressed as one of the
    /// severity levels listed below. For your reference, the levels are
    /// assigned the listed numeric values. The effect of using numeric values
    /// other than those listed is undefined.
    ///
    /// If you are writing log entries, you should map other severity encodings to
    /// one of these standard levels. For example, you might map all of
    /// Java's FINE, FINER, and FINEST levels to `Severity.DEBUG`.
    ///
    /// This list is intentionally not comprehensive; the intent is to provide a
    /// common set of "good enough" severity levels so that logging front ends
    /// can provide filtering and searching across log types. Users of the API are
    /// free not to use all severity levels in their log messages.
    #[derive(Clone, PartialEq, ::prost::Message)]
    pub struct Severity {}
    /// Nested message and enum types in `Severity`.
    pub mod severity {
        #[derive(
            Clone,
            Copy,
            Debug,
            PartialEq,
            Eq,
            Hash,
            PartialOrd,
            Ord,
            ::prost::Enumeration
        )]
        #[repr(i32)]
        pub enum Enum {
            /// Unspecified level information. Will be logged at the TRACE level.
            Unspecified = 0,
            Trace = 1,
            /// Debugging information.
            Debug = 2,
            /// Normal events.
            Info = 3,
            /// Normal but significant events, such as start up, shut down, or
            /// configuration.
            Notice = 4,
            /// Warning events might cause problems.
            Warn = 5,
            /// Error events are likely to cause problems.
            Error = 6,
            /// Critical events cause severe problems or brief outages and may
            /// indicate that a person must take action.
            Critical = 7,
        }
        impl Enum {
            /// String value of the enum field names used in the ProtoBuf definition.
            ///
            /// The values are not transformed in any way and thus are considered stable
            /// (if the ProtoBuf definition does not change) and safe for programmatic use.
            pub fn as_str_name(&self) -> &'static str {
                match self {
                    Enum::Unspecified => "UNSPECIFIED",
                    Enum::Trace => "TRACE",
                    Enum::Debug => "DEBUG",
                    Enum::Info => "INFO",
                    Enum::Notice => "NOTICE",
                    Enum::Warn => "WARN",
                    Enum::Error => "ERROR",
                    Enum::Critical => "CRITICAL",
                }
            }
        }
    }
}
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct LogControl {}
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct StartWorkerRequest {
    #[prost(string, tag = "1")]
    pub worker_id: ::prost::alloc::string::String,
    #[prost(message, optional, tag = "2")]
    pub control_endpoint: ::core::option::Option<
        super::super::pipeline::v1::ApiServiceDescriptor,
    >,
    #[prost(message, optional, tag = "3")]
    pub logging_endpoint: ::core::option::Option<
        super::super::pipeline::v1::ApiServiceDescriptor,
    >,
    #[prost(message, optional, tag = "4")]
    pub artifact_endpoint: ::core::option::Option<
        super::super::pipeline::v1::ApiServiceDescriptor,
    >,
    #[prost(message, optional, tag = "5")]
    pub provision_endpoint: ::core::option::Option<
        super::super::pipeline::v1::ApiServiceDescriptor,
    >,
    #[prost(map = "string, string", tag = "10")]
    pub params: ::std::collections::HashMap<
        ::prost::alloc::string::String,
        ::prost::alloc::string::String,
    >,
}
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct StartWorkerResponse {
    #[prost(string, tag = "1")]
    pub error: ::prost::alloc::string::String,
}
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct StopWorkerRequest {
    #[prost(string, tag = "1")]
    pub worker_id: ::prost::alloc::string::String,
}
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct StopWorkerResponse {
    #[prost(string, tag = "1")]
    pub error: ::prost::alloc::string::String,
}
/// Request from runner to SDK Harness asking for its status. For more details see
/// <https://s.apache.org/beam-fn-api-harness-status>
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct WorkerStatusRequest {
    /// (Required) Unique ID identifying this request.
    #[prost(string, tag = "1")]
    pub id: ::prost::alloc::string::String,
}
/// Response from SDK Harness to runner containing the debug related status info.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct WorkerStatusResponse {
    /// (Required) Unique ID from the original request.
    #[prost(string, tag = "1")]
    pub id: ::prost::alloc::string::String,
    /// (Optional) Error message if exception encountered generating the status response.
    #[prost(string, tag = "2")]
    pub error: ::prost::alloc::string::String,
    /// (Optional) Status debugging info reported by SDK harness worker. Content and
    /// format is not strongly enforced but should be print-friendly and
    /// appropriate as an HTTP response body for end user. For details of the preferred
    /// info to include in the message see
    /// <https://s.apache.org/beam-fn-api-harness-status>
    #[prost(string, tag = "3")]
    pub status_info: ::prost::alloc::string::String,
}
/// Generated client implementations.
pub mod beam_fn_control_client {
    #![allow(unused_variables, dead_code, missing_docs, clippy::let_unit_value)]
    use tonic::codegen::*;
    use tonic::codegen::http::Uri;
    /// An API that describes the work that a SDK harness is meant to do.
    /// Stable
    #[derive(Debug, Clone)]
    pub struct BeamFnControlClient<T> {
        inner: tonic::client::Grpc<T>,
    }
    impl BeamFnControlClient<tonic::transport::Channel> {
        /// Attempt to create a new client by connecting to a given endpoint.
        pub async fn connect<D>(dst: D) -> Result<Self, tonic::transport::Error>
        where
            D: std::convert::TryInto<tonic::transport::Endpoint>,
            D::Error: Into<StdError>,
        {
            let conn = tonic::transport::Endpoint::new(dst)?.connect().await?;
            Ok(Self::new(conn))
        }
    }
    impl<T> BeamFnControlClient<T>
    where
        T: tonic::client::GrpcService<tonic::body::BoxBody>,
        T::Error: Into<StdError>,
        T::ResponseBody: Body<Data = Bytes> + Send + 'static,
        <T::ResponseBody as Body>::Error: Into<StdError> + Send,
    {
        pub fn new(inner: T) -> Self {
            let inner = tonic::client::Grpc::new(inner);
            Self { inner }
        }
        pub fn with_origin(inner: T, origin: Uri) -> Self {
            let inner = tonic::client::Grpc::with_origin(inner, origin);
            Self { inner }
        }
        pub fn with_interceptor<F>(
            inner: T,
            interceptor: F,
        ) -> BeamFnControlClient<InterceptedService<T, F>>
        where
            F: tonic::service::Interceptor,
            T::ResponseBody: Default,
            T: tonic::codegen::Service<
                http::Request<tonic::body::BoxBody>,
                Response = http::Response<
                    <T as tonic::client::GrpcService<tonic::body::BoxBody>>::ResponseBody,
                >,
            >,
            <T as tonic::codegen::Service<
                http::Request<tonic::body::BoxBody>,
            >>::Error: Into<StdError> + Send + Sync,
        {
            BeamFnControlClient::new(InterceptedService::new(inner, interceptor))
        }
        /// Compress requests with the given encoding.
        ///
        /// This requires the server to support it otherwise it might respond with an
        /// error.
        #[must_use]
        pub fn send_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.inner = self.inner.send_compressed(encoding);
            self
        }
        /// Enable decompressing responses.
        #[must_use]
        pub fn accept_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.inner = self.inner.accept_compressed(encoding);
            self
        }
        /// Instructions sent by the runner to the SDK requesting different types
        /// of work.
        pub async fn control(
            &mut self,
            request: impl tonic::IntoStreamingRequest<
                Message = super::InstructionResponse,
            >,
        ) -> Result<
            tonic::Response<tonic::codec::Streaming<super::InstructionRequest>>,
            tonic::Status,
        > {
            self.inner
                .ready()
                .await
                .map_err(|e| {
                    tonic::Status::new(
                        tonic::Code::Unknown,
                        format!("Service was not ready: {}", e.into()),
                    )
                })?;
            let codec = tonic::codec::ProstCodec::default();
            let path = http::uri::PathAndQuery::from_static(
                "/org.apache.beam.model.fn_execution.v1.BeamFnControl/Control",
            );
            self.inner.streaming(request.into_streaming_request(), path, codec).await
        }
        /// Used to get the full process bundle descriptors for bundles one
        /// is asked to process.
        pub async fn get_process_bundle_descriptor(
            &mut self,
            request: impl tonic::IntoRequest<super::GetProcessBundleDescriptorRequest>,
        ) -> Result<tonic::Response<super::ProcessBundleDescriptor>, tonic::Status> {
            self.inner
                .ready()
                .await
                .map_err(|e| {
                    tonic::Status::new(
                        tonic::Code::Unknown,
                        format!("Service was not ready: {}", e.into()),
                    )
                })?;
            let codec = tonic::codec::ProstCodec::default();
            let path = http::uri::PathAndQuery::from_static(
                "/org.apache.beam.model.fn_execution.v1.BeamFnControl/GetProcessBundleDescriptor",
            );
            self.inner.unary(request.into_request(), path, codec).await
        }
    }
}
/// Generated client implementations.
pub mod beam_fn_data_client {
    #![allow(unused_variables, dead_code, missing_docs, clippy::let_unit_value)]
    use tonic::codegen::*;
    use tonic::codegen::http::Uri;
    /// Stable
    #[derive(Debug, Clone)]
    pub struct BeamFnDataClient<T> {
        inner: tonic::client::Grpc<T>,
    }
    impl BeamFnDataClient<tonic::transport::Channel> {
        /// Attempt to create a new client by connecting to a given endpoint.
        pub async fn connect<D>(dst: D) -> Result<Self, tonic::transport::Error>
        where
            D: std::convert::TryInto<tonic::transport::Endpoint>,
            D::Error: Into<StdError>,
        {
            let conn = tonic::transport::Endpoint::new(dst)?.connect().await?;
            Ok(Self::new(conn))
        }
    }
    impl<T> BeamFnDataClient<T>
    where
        T: tonic::client::GrpcService<tonic::body::BoxBody>,
        T::Error: Into<StdError>,
        T::ResponseBody: Body<Data = Bytes> + Send + 'static,
        <T::ResponseBody as Body>::Error: Into<StdError> + Send,
    {
        pub fn new(inner: T) -> Self {
            let inner = tonic::client::Grpc::new(inner);
            Self { inner }
        }
        pub fn with_origin(inner: T, origin: Uri) -> Self {
            let inner = tonic::client::Grpc::with_origin(inner, origin);
            Self { inner }
        }
        pub fn with_interceptor<F>(
            inner: T,
            interceptor: F,
        ) -> BeamFnDataClient<InterceptedService<T, F>>
        where
            F: tonic::service::Interceptor,
            T::ResponseBody: Default,
            T: tonic::codegen::Service<
                http::Request<tonic::body::BoxBody>,
                Response = http::Response<
                    <T as tonic::client::GrpcService<tonic::body::BoxBody>>::ResponseBody,
                >,
            >,
            <T as tonic::codegen::Service<
                http::Request<tonic::body::BoxBody>,
            >>::Error: Into<StdError> + Send + Sync,
        {
            BeamFnDataClient::new(InterceptedService::new(inner, interceptor))
        }
        /// Compress requests with the given encoding.
        ///
        /// This requires the server to support it otherwise it might respond with an
        /// error.
        #[must_use]
        pub fn send_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.inner = self.inner.send_compressed(encoding);
            self
        }
        /// Enable decompressing responses.
        #[must_use]
        pub fn accept_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.inner = self.inner.accept_compressed(encoding);
            self
        }
        /// Used to send data between harnesses.
        pub async fn data(
            &mut self,
            request: impl tonic::IntoStreamingRequest<Message = super::Elements>,
        ) -> Result<
            tonic::Response<tonic::codec::Streaming<super::Elements>>,
            tonic::Status,
        > {
            self.inner
                .ready()
                .await
                .map_err(|e| {
                    tonic::Status::new(
                        tonic::Code::Unknown,
                        format!("Service was not ready: {}", e.into()),
                    )
                })?;
            let codec = tonic::codec::ProstCodec::default();
            let path = http::uri::PathAndQuery::from_static(
                "/org.apache.beam.model.fn_execution.v1.BeamFnData/Data",
            );
            self.inner.streaming(request.into_streaming_request(), path, codec).await
        }
    }
}
/// Generated client implementations.
pub mod beam_fn_state_client {
    #![allow(unused_variables, dead_code, missing_docs, clippy::let_unit_value)]
    use tonic::codegen::*;
    use tonic::codegen::http::Uri;
    #[derive(Debug, Clone)]
    pub struct BeamFnStateClient<T> {
        inner: tonic::client::Grpc<T>,
    }
    impl BeamFnStateClient<tonic::transport::Channel> {
        /// Attempt to create a new client by connecting to a given endpoint.
        pub async fn connect<D>(dst: D) -> Result<Self, tonic::transport::Error>
        where
            D: std::convert::TryInto<tonic::transport::Endpoint>,
            D::Error: Into<StdError>,
        {
            let conn = tonic::transport::Endpoint::new(dst)?.connect().await?;
            Ok(Self::new(conn))
        }
    }
    impl<T> BeamFnStateClient<T>
    where
        T: tonic::client::GrpcService<tonic::body::BoxBody>,
        T::Error: Into<StdError>,
        T::ResponseBody: Body<Data = Bytes> + Send + 'static,
        <T::ResponseBody as Body>::Error: Into<StdError> + Send,
    {
        pub fn new(inner: T) -> Self {
            let inner = tonic::client::Grpc::new(inner);
            Self { inner }
        }
        pub fn with_origin(inner: T, origin: Uri) -> Self {
            let inner = tonic::client::Grpc::with_origin(inner, origin);
            Self { inner }
        }
        pub fn with_interceptor<F>(
            inner: T,
            interceptor: F,
        ) -> BeamFnStateClient<InterceptedService<T, F>>
        where
            F: tonic::service::Interceptor,
            T::ResponseBody: Default,
            T: tonic::codegen::Service<
                http::Request<tonic::body::BoxBody>,
                Response = http::Response<
                    <T as tonic::client::GrpcService<tonic::body::BoxBody>>::ResponseBody,
                >,
            >,
            <T as tonic::codegen::Service<
                http::Request<tonic::body::BoxBody>,
            >>::Error: Into<StdError> + Send + Sync,
        {
            BeamFnStateClient::new(InterceptedService::new(inner, interceptor))
        }
        /// Compress requests with the given encoding.
        ///
        /// This requires the server to support it otherwise it might respond with an
        /// error.
        #[must_use]
        pub fn send_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.inner = self.inner.send_compressed(encoding);
            self
        }
        /// Enable decompressing responses.
        #[must_use]
        pub fn accept_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.inner = self.inner.accept_compressed(encoding);
            self
        }
        /// Used to get/append/clear state stored by the runner on behalf of the SDK.
        pub async fn state(
            &mut self,
            request: impl tonic::IntoStreamingRequest<Message = super::StateRequest>,
        ) -> Result<
            tonic::Response<tonic::codec::Streaming<super::StateResponse>>,
            tonic::Status,
        > {
            self.inner
                .ready()
                .await
                .map_err(|e| {
                    tonic::Status::new(
                        tonic::Code::Unknown,
                        format!("Service was not ready: {}", e.into()),
                    )
                })?;
            let codec = tonic::codec::ProstCodec::default();
            let path = http::uri::PathAndQuery::from_static(
                "/org.apache.beam.model.fn_execution.v1.BeamFnState/State",
            );
            self.inner.streaming(request.into_streaming_request(), path, codec).await
        }
    }
}
/// Generated client implementations.
pub mod beam_fn_logging_client {
    #![allow(unused_variables, dead_code, missing_docs, clippy::let_unit_value)]
    use tonic::codegen::*;
    use tonic::codegen::http::Uri;
    /// Stable
    #[derive(Debug, Clone)]
    pub struct BeamFnLoggingClient<T> {
        inner: tonic::client::Grpc<T>,
    }
    impl BeamFnLoggingClient<tonic::transport::Channel> {
        /// Attempt to create a new client by connecting to a given endpoint.
        pub async fn connect<D>(dst: D) -> Result<Self, tonic::transport::Error>
        where
            D: std::convert::TryInto<tonic::transport::Endpoint>,
            D::Error: Into<StdError>,
        {
            let conn = tonic::transport::Endpoint::new(dst)?.connect().await?;
            Ok(Self::new(conn))
        }
    }
    impl<T> BeamFnLoggingClient<T>
    where
        T: tonic::client::GrpcService<tonic::body::BoxBody>,
        T::Error: Into<StdError>,
        T::ResponseBody: Body<Data = Bytes> + Send + 'static,
        <T::ResponseBody as Body>::Error: Into<StdError> + Send,
    {
        pub fn new(inner: T) -> Self {
            let inner = tonic::client::Grpc::new(inner);
            Self { inner }
        }
        pub fn with_origin(inner: T, origin: Uri) -> Self {
            let inner = tonic::client::Grpc::with_origin(inner, origin);
            Self { inner }
        }
        pub fn with_interceptor<F>(
            inner: T,
            interceptor: F,
        ) -> BeamFnLoggingClient<InterceptedService<T, F>>
        where
            F: tonic::service::Interceptor,
            T::ResponseBody: Default,
            T: tonic::codegen::Service<
                http::Request<tonic::body::BoxBody>,
                Response = http::Response<
                    <T as tonic::client::GrpcService<tonic::body::BoxBody>>::ResponseBody,
                >,
            >,
            <T as tonic::codegen::Service<
                http::Request<tonic::body::BoxBody>,
            >>::Error: Into<StdError> + Send + Sync,
        {
            BeamFnLoggingClient::new(InterceptedService::new(inner, interceptor))
        }
        /// Compress requests with the given encoding.
        ///
        /// This requires the server to support it otherwise it might respond with an
        /// error.
        #[must_use]
        pub fn send_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.inner = self.inner.send_compressed(encoding);
            self
        }
        /// Enable decompressing responses.
        #[must_use]
        pub fn accept_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.inner = self.inner.accept_compressed(encoding);
            self
        }
        /// Allows for the SDK to emit log entries which the runner can
        /// associate with the active job.
        pub async fn logging(
            &mut self,
            request: impl tonic::IntoStreamingRequest<Message = super::log_entry::List>,
        ) -> Result<
            tonic::Response<tonic::codec::Streaming<super::LogControl>>,
            tonic::Status,
        > {
            self.inner
                .ready()
                .await
                .map_err(|e| {
                    tonic::Status::new(
                        tonic::Code::Unknown,
                        format!("Service was not ready: {}", e.into()),
                    )
                })?;
            let codec = tonic::codec::ProstCodec::default();
            let path = http::uri::PathAndQuery::from_static(
                "/org.apache.beam.model.fn_execution.v1.BeamFnLogging/Logging",
            );
            self.inner.streaming(request.into_streaming_request(), path, codec).await
        }
    }
}
/// Generated client implementations.
pub mod beam_fn_external_worker_pool_client {
    #![allow(unused_variables, dead_code, missing_docs, clippy::let_unit_value)]
    use tonic::codegen::*;
    use tonic::codegen::http::Uri;
    #[derive(Debug, Clone)]
    pub struct BeamFnExternalWorkerPoolClient<T> {
        inner: tonic::client::Grpc<T>,
    }
    impl BeamFnExternalWorkerPoolClient<tonic::transport::Channel> {
        /// Attempt to create a new client by connecting to a given endpoint.
        pub async fn connect<D>(dst: D) -> Result<Self, tonic::transport::Error>
        where
            D: std::convert::TryInto<tonic::transport::Endpoint>,
            D::Error: Into<StdError>,
        {
            let conn = tonic::transport::Endpoint::new(dst)?.connect().await?;
            Ok(Self::new(conn))
        }
    }
    impl<T> BeamFnExternalWorkerPoolClient<T>
    where
        T: tonic::client::GrpcService<tonic::body::BoxBody>,
        T::Error: Into<StdError>,
        T::ResponseBody: Body<Data = Bytes> + Send + 'static,
        <T::ResponseBody as Body>::Error: Into<StdError> + Send,
    {
        pub fn new(inner: T) -> Self {
            let inner = tonic::client::Grpc::new(inner);
            Self { inner }
        }
        pub fn with_origin(inner: T, origin: Uri) -> Self {
            let inner = tonic::client::Grpc::with_origin(inner, origin);
            Self { inner }
        }
        pub fn with_interceptor<F>(
            inner: T,
            interceptor: F,
        ) -> BeamFnExternalWorkerPoolClient<InterceptedService<T, F>>
        where
            F: tonic::service::Interceptor,
            T::ResponseBody: Default,
            T: tonic::codegen::Service<
                http::Request<tonic::body::BoxBody>,
                Response = http::Response<
                    <T as tonic::client::GrpcService<tonic::body::BoxBody>>::ResponseBody,
                >,
            >,
            <T as tonic::codegen::Service<
                http::Request<tonic::body::BoxBody>,
            >>::Error: Into<StdError> + Send + Sync,
        {
            BeamFnExternalWorkerPoolClient::new(
                InterceptedService::new(inner, interceptor),
            )
        }
        /// Compress requests with the given encoding.
        ///
        /// This requires the server to support it otherwise it might respond with an
        /// error.
        #[must_use]
        pub fn send_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.inner = self.inner.send_compressed(encoding);
            self
        }
        /// Enable decompressing responses.
        #[must_use]
        pub fn accept_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.inner = self.inner.accept_compressed(encoding);
            self
        }
        /// Start the SDK worker with the given ID.
        pub async fn start_worker(
            &mut self,
            request: impl tonic::IntoRequest<super::StartWorkerRequest>,
        ) -> Result<tonic::Response<super::StartWorkerResponse>, tonic::Status> {
            self.inner
                .ready()
                .await
                .map_err(|e| {
                    tonic::Status::new(
                        tonic::Code::Unknown,
                        format!("Service was not ready: {}", e.into()),
                    )
                })?;
            let codec = tonic::codec::ProstCodec::default();
            let path = http::uri::PathAndQuery::from_static(
                "/org.apache.beam.model.fn_execution.v1.BeamFnExternalWorkerPool/StartWorker",
            );
            self.inner.unary(request.into_request(), path, codec).await
        }
        /// Stop the SDK worker.
        pub async fn stop_worker(
            &mut self,
            request: impl tonic::IntoRequest<super::StopWorkerRequest>,
        ) -> Result<tonic::Response<super::StopWorkerResponse>, tonic::Status> {
            self.inner
                .ready()
                .await
                .map_err(|e| {
                    tonic::Status::new(
                        tonic::Code::Unknown,
                        format!("Service was not ready: {}", e.into()),
                    )
                })?;
            let codec = tonic::codec::ProstCodec::default();
            let path = http::uri::PathAndQuery::from_static(
                "/org.apache.beam.model.fn_execution.v1.BeamFnExternalWorkerPool/StopWorker",
            );
            self.inner.unary(request.into_request(), path, codec).await
        }
    }
}
/// Generated client implementations.
pub mod beam_fn_worker_status_client {
    #![allow(unused_variables, dead_code, missing_docs, clippy::let_unit_value)]
    use tonic::codegen::*;
    use tonic::codegen::http::Uri;
    /// API for SDKs to report debug-related statuses to runner during pipeline execution.
    #[derive(Debug, Clone)]
    pub struct BeamFnWorkerStatusClient<T> {
        inner: tonic::client::Grpc<T>,
    }
    impl BeamFnWorkerStatusClient<tonic::transport::Channel> {
        /// Attempt to create a new client by connecting to a given endpoint.
        pub async fn connect<D>(dst: D) -> Result<Self, tonic::transport::Error>
        where
            D: std::convert::TryInto<tonic::transport::Endpoint>,
            D::Error: Into<StdError>,
        {
            let conn = tonic::transport::Endpoint::new(dst)?.connect().await?;
            Ok(Self::new(conn))
        }
    }
    impl<T> BeamFnWorkerStatusClient<T>
    where
        T: tonic::client::GrpcService<tonic::body::BoxBody>,
        T::Error: Into<StdError>,
        T::ResponseBody: Body<Data = Bytes> + Send + 'static,
        <T::ResponseBody as Body>::Error: Into<StdError> + Send,
    {
        pub fn new(inner: T) -> Self {
            let inner = tonic::client::Grpc::new(inner);
            Self { inner }
        }
        pub fn with_origin(inner: T, origin: Uri) -> Self {
            let inner = tonic::client::Grpc::with_origin(inner, origin);
            Self { inner }
        }
        pub fn with_interceptor<F>(
            inner: T,
            interceptor: F,
        ) -> BeamFnWorkerStatusClient<InterceptedService<T, F>>
        where
            F: tonic::service::Interceptor,
            T::ResponseBody: Default,
            T: tonic::codegen::Service<
                http::Request<tonic::body::BoxBody>,
                Response = http::Response<
                    <T as tonic::client::GrpcService<tonic::body::BoxBody>>::ResponseBody,
                >,
            >,
            <T as tonic::codegen::Service<
                http::Request<tonic::body::BoxBody>,
            >>::Error: Into<StdError> + Send + Sync,
        {
            BeamFnWorkerStatusClient::new(InterceptedService::new(inner, interceptor))
        }
        /// Compress requests with the given encoding.
        ///
        /// This requires the server to support it otherwise it might respond with an
        /// error.
        #[must_use]
        pub fn send_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.inner = self.inner.send_compressed(encoding);
            self
        }
        /// Enable decompressing responses.
        #[must_use]
        pub fn accept_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.inner = self.inner.accept_compressed(encoding);
            self
        }
        pub async fn worker_status(
            &mut self,
            request: impl tonic::IntoStreamingRequest<
                Message = super::WorkerStatusResponse,
            >,
        ) -> Result<
            tonic::Response<tonic::codec::Streaming<super::WorkerStatusRequest>>,
            tonic::Status,
        > {
            self.inner
                .ready()
                .await
                .map_err(|e| {
                    tonic::Status::new(
                        tonic::Code::Unknown,
                        format!("Service was not ready: {}", e.into()),
                    )
                })?;
            let codec = tonic::codec::ProstCodec::default();
            let path = http::uri::PathAndQuery::from_static(
                "/org.apache.beam.model.fn_execution.v1.BeamFnWorkerStatus/WorkerStatus",
            );
            self.inner.streaming(request.into_streaming_request(), path, codec).await
        }
    }
}
/// Generated server implementations.
pub mod beam_fn_control_server {
    #![allow(unused_variables, dead_code, missing_docs, clippy::let_unit_value)]
    use tonic::codegen::*;
    ///Generated trait containing gRPC methods that should be implemented for use with BeamFnControlServer.
    #[async_trait]
    pub trait BeamFnControl: Send + Sync + 'static {
        ///Server streaming response type for the Control method.
        type ControlStream: futures_core::Stream<
                Item = Result<super::InstructionRequest, tonic::Status>,
            >
            + Send
            + 'static;
        /// Instructions sent by the runner to the SDK requesting different types
        /// of work.
        async fn control(
            &self,
            request: tonic::Request<tonic::Streaming<super::InstructionResponse>>,
        ) -> Result<tonic::Response<Self::ControlStream>, tonic::Status>;
        /// Used to get the full process bundle descriptors for bundles one
        /// is asked to process.
        async fn get_process_bundle_descriptor(
            &self,
            request: tonic::Request<super::GetProcessBundleDescriptorRequest>,
        ) -> Result<tonic::Response<super::ProcessBundleDescriptor>, tonic::Status>;
    }
    /// An API that describes the work that a SDK harness is meant to do.
    /// Stable
    #[derive(Debug)]
    pub struct BeamFnControlServer<T: BeamFnControl> {
        inner: _Inner<T>,
        accept_compression_encodings: EnabledCompressionEncodings,
        send_compression_encodings: EnabledCompressionEncodings,
    }
    struct _Inner<T>(Arc<T>);
    impl<T: BeamFnControl> BeamFnControlServer<T> {
        pub fn new(inner: T) -> Self {
            Self::from_arc(Arc::new(inner))
        }
        pub fn from_arc(inner: Arc<T>) -> Self {
            let inner = _Inner(inner);
            Self {
                inner,
                accept_compression_encodings: Default::default(),
                send_compression_encodings: Default::default(),
            }
        }
        pub fn with_interceptor<F>(
            inner: T,
            interceptor: F,
        ) -> InterceptedService<Self, F>
        where
            F: tonic::service::Interceptor,
        {
            InterceptedService::new(Self::new(inner), interceptor)
        }
        /// Enable decompressing requests with the given encoding.
        #[must_use]
        pub fn accept_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.accept_compression_encodings.enable(encoding);
            self
        }
        /// Compress responses with the given encoding, if the client supports it.
        #[must_use]
        pub fn send_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.send_compression_encodings.enable(encoding);
            self
        }
    }
    impl<T, B> tonic::codegen::Service<http::Request<B>> for BeamFnControlServer<T>
    where
        T: BeamFnControl,
        B: Body + Send + 'static,
        B::Error: Into<StdError> + Send + 'static,
    {
        type Response = http::Response<tonic::body::BoxBody>;
        type Error = std::convert::Infallible;
        type Future = BoxFuture<Self::Response, Self::Error>;
        fn poll_ready(
            &mut self,
            _cx: &mut Context<'_>,
        ) -> Poll<Result<(), Self::Error>> {
            Poll::Ready(Ok(()))
        }
        fn call(&mut self, req: http::Request<B>) -> Self::Future {
            let inner = self.inner.clone();
            match req.uri().path() {
                "/org.apache.beam.model.fn_execution.v1.BeamFnControl/Control" => {
                    #[allow(non_camel_case_types)]
                    struct ControlSvc<T: BeamFnControl>(pub Arc<T>);
                    impl<
                        T: BeamFnControl,
                    > tonic::server::StreamingService<super::InstructionResponse>
                    for ControlSvc<T> {
                        type Response = super::InstructionRequest;
                        type ResponseStream = T::ControlStream;
                        type Future = BoxFuture<
                            tonic::Response<Self::ResponseStream>,
                            tonic::Status,
                        >;
                        fn call(
                            &mut self,
                            request: tonic::Request<
                                tonic::Streaming<super::InstructionResponse>,
                            >,
                        ) -> Self::Future {
                            let inner = self.0.clone();
                            let fut = async move { (*inner).control(request).await };
                            Box::pin(fut)
                        }
                    }
                    let accept_compression_encodings = self.accept_compression_encodings;
                    let send_compression_encodings = self.send_compression_encodings;
                    let inner = self.inner.clone();
                    let fut = async move {
                        let inner = inner.0;
                        let method = ControlSvc(inner);
                        let codec = tonic::codec::ProstCodec::default();
                        let mut grpc = tonic::server::Grpc::new(codec)
                            .apply_compression_config(
                                accept_compression_encodings,
                                send_compression_encodings,
                            );
                        let res = grpc.streaming(method, req).await;
                        Ok(res)
                    };
                    Box::pin(fut)
                }
                "/org.apache.beam.model.fn_execution.v1.BeamFnControl/GetProcessBundleDescriptor" => {
                    #[allow(non_camel_case_types)]
                    struct GetProcessBundleDescriptorSvc<T: BeamFnControl>(pub Arc<T>);
                    impl<
                        T: BeamFnControl,
                    > tonic::server::UnaryService<
                        super::GetProcessBundleDescriptorRequest,
                    > for GetProcessBundleDescriptorSvc<T> {
                        type Response = super::ProcessBundleDescriptor;
                        type Future = BoxFuture<
                            tonic::Response<Self::Response>,
                            tonic::Status,
                        >;
                        fn call(
                            &mut self,
                            request: tonic::Request<
                                super::GetProcessBundleDescriptorRequest,
                            >,
                        ) -> Self::Future {
                            let inner = self.0.clone();
                            let fut = async move {
                                (*inner).get_process_bundle_descriptor(request).await
                            };
                            Box::pin(fut)
                        }
                    }
                    let accept_compression_encodings = self.accept_compression_encodings;
                    let send_compression_encodings = self.send_compression_encodings;
                    let inner = self.inner.clone();
                    let fut = async move {
                        let inner = inner.0;
                        let method = GetProcessBundleDescriptorSvc(inner);
                        let codec = tonic::codec::ProstCodec::default();
                        let mut grpc = tonic::server::Grpc::new(codec)
                            .apply_compression_config(
                                accept_compression_encodings,
                                send_compression_encodings,
                            );
                        let res = grpc.unary(method, req).await;
                        Ok(res)
                    };
                    Box::pin(fut)
                }
                _ => {
                    Box::pin(async move {
                        Ok(
                            http::Response::builder()
                                .status(200)
                                .header("grpc-status", "12")
                                .header("content-type", "application/grpc")
                                .body(empty_body())
                                .unwrap(),
                        )
                    })
                }
            }
        }
    }
    impl<T: BeamFnControl> Clone for BeamFnControlServer<T> {
        fn clone(&self) -> Self {
            let inner = self.inner.clone();
            Self {
                inner,
                accept_compression_encodings: self.accept_compression_encodings,
                send_compression_encodings: self.send_compression_encodings,
            }
        }
    }
    impl<T: BeamFnControl> Clone for _Inner<T> {
        fn clone(&self) -> Self {
            Self(self.0.clone())
        }
    }
    impl<T: std::fmt::Debug> std::fmt::Debug for _Inner<T> {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            write!(f, "{:?}", self.0)
        }
    }
    impl<T: BeamFnControl> tonic::server::NamedService for BeamFnControlServer<T> {
        const NAME: &'static str = "org.apache.beam.model.fn_execution.v1.BeamFnControl";
    }
}
/// Generated server implementations.
pub mod beam_fn_data_server {
    #![allow(unused_variables, dead_code, missing_docs, clippy::let_unit_value)]
    use tonic::codegen::*;
    ///Generated trait containing gRPC methods that should be implemented for use with BeamFnDataServer.
    #[async_trait]
    pub trait BeamFnData: Send + Sync + 'static {
        ///Server streaming response type for the Data method.
        type DataStream: futures_core::Stream<
                Item = Result<super::Elements, tonic::Status>,
            >
            + Send
            + 'static;
        /// Used to send data between harnesses.
        async fn data(
            &self,
            request: tonic::Request<tonic::Streaming<super::Elements>>,
        ) -> Result<tonic::Response<Self::DataStream>, tonic::Status>;
    }
    /// Stable
    #[derive(Debug)]
    pub struct BeamFnDataServer<T: BeamFnData> {
        inner: _Inner<T>,
        accept_compression_encodings: EnabledCompressionEncodings,
        send_compression_encodings: EnabledCompressionEncodings,
    }
    struct _Inner<T>(Arc<T>);
    impl<T: BeamFnData> BeamFnDataServer<T> {
        pub fn new(inner: T) -> Self {
            Self::from_arc(Arc::new(inner))
        }
        pub fn from_arc(inner: Arc<T>) -> Self {
            let inner = _Inner(inner);
            Self {
                inner,
                accept_compression_encodings: Default::default(),
                send_compression_encodings: Default::default(),
            }
        }
        pub fn with_interceptor<F>(
            inner: T,
            interceptor: F,
        ) -> InterceptedService<Self, F>
        where
            F: tonic::service::Interceptor,
        {
            InterceptedService::new(Self::new(inner), interceptor)
        }
        /// Enable decompressing requests with the given encoding.
        #[must_use]
        pub fn accept_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.accept_compression_encodings.enable(encoding);
            self
        }
        /// Compress responses with the given encoding, if the client supports it.
        #[must_use]
        pub fn send_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.send_compression_encodings.enable(encoding);
            self
        }
    }
    impl<T, B> tonic::codegen::Service<http::Request<B>> for BeamFnDataServer<T>
    where
        T: BeamFnData,
        B: Body + Send + 'static,
        B::Error: Into<StdError> + Send + 'static,
    {
        type Response = http::Response<tonic::body::BoxBody>;
        type Error = std::convert::Infallible;
        type Future = BoxFuture<Self::Response, Self::Error>;
        fn poll_ready(
            &mut self,
            _cx: &mut Context<'_>,
        ) -> Poll<Result<(), Self::Error>> {
            Poll::Ready(Ok(()))
        }
        fn call(&mut self, req: http::Request<B>) -> Self::Future {
            let inner = self.inner.clone();
            match req.uri().path() {
                "/org.apache.beam.model.fn_execution.v1.BeamFnData/Data" => {
                    #[allow(non_camel_case_types)]
                    struct DataSvc<T: BeamFnData>(pub Arc<T>);
                    impl<T: BeamFnData> tonic::server::StreamingService<super::Elements>
                    for DataSvc<T> {
                        type Response = super::Elements;
                        type ResponseStream = T::DataStream;
                        type Future = BoxFuture<
                            tonic::Response<Self::ResponseStream>,
                            tonic::Status,
                        >;
                        fn call(
                            &mut self,
                            request: tonic::Request<tonic::Streaming<super::Elements>>,
                        ) -> Self::Future {
                            let inner = self.0.clone();
                            let fut = async move { (*inner).data(request).await };
                            Box::pin(fut)
                        }
                    }
                    let accept_compression_encodings = self.accept_compression_encodings;
                    let send_compression_encodings = self.send_compression_encodings;
                    let inner = self.inner.clone();
                    let fut = async move {
                        let inner = inner.0;
                        let method = DataSvc(inner);
                        let codec = tonic::codec::ProstCodec::default();
                        let mut grpc = tonic::server::Grpc::new(codec)
                            .apply_compression_config(
                                accept_compression_encodings,
                                send_compression_encodings,
                            );
                        let res = grpc.streaming(method, req).await;
                        Ok(res)
                    };
                    Box::pin(fut)
                }
                _ => {
                    Box::pin(async move {
                        Ok(
                            http::Response::builder()
                                .status(200)
                                .header("grpc-status", "12")
                                .header("content-type", "application/grpc")
                                .body(empty_body())
                                .unwrap(),
                        )
                    })
                }
            }
        }
    }
    impl<T: BeamFnData> Clone for BeamFnDataServer<T> {
        fn clone(&self) -> Self {
            let inner = self.inner.clone();
            Self {
                inner,
                accept_compression_encodings: self.accept_compression_encodings,
                send_compression_encodings: self.send_compression_encodings,
            }
        }
    }
    impl<T: BeamFnData> Clone for _Inner<T> {
        fn clone(&self) -> Self {
            Self(self.0.clone())
        }
    }
    impl<T: std::fmt::Debug> std::fmt::Debug for _Inner<T> {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            write!(f, "{:?}", self.0)
        }
    }
    impl<T: BeamFnData> tonic::server::NamedService for BeamFnDataServer<T> {
        const NAME: &'static str = "org.apache.beam.model.fn_execution.v1.BeamFnData";
    }
}
/// Generated server implementations.
pub mod beam_fn_state_server {
    #![allow(unused_variables, dead_code, missing_docs, clippy::let_unit_value)]
    use tonic::codegen::*;
    ///Generated trait containing gRPC methods that should be implemented for use with BeamFnStateServer.
    #[async_trait]
    pub trait BeamFnState: Send + Sync + 'static {
        ///Server streaming response type for the State method.
        type StateStream: futures_core::Stream<
                Item = Result<super::StateResponse, tonic::Status>,
            >
            + Send
            + 'static;
        /// Used to get/append/clear state stored by the runner on behalf of the SDK.
        async fn state(
            &self,
            request: tonic::Request<tonic::Streaming<super::StateRequest>>,
        ) -> Result<tonic::Response<Self::StateStream>, tonic::Status>;
    }
    #[derive(Debug)]
    pub struct BeamFnStateServer<T: BeamFnState> {
        inner: _Inner<T>,
        accept_compression_encodings: EnabledCompressionEncodings,
        send_compression_encodings: EnabledCompressionEncodings,
    }
    struct _Inner<T>(Arc<T>);
    impl<T: BeamFnState> BeamFnStateServer<T> {
        pub fn new(inner: T) -> Self {
            Self::from_arc(Arc::new(inner))
        }
        pub fn from_arc(inner: Arc<T>) -> Self {
            let inner = _Inner(inner);
            Self {
                inner,
                accept_compression_encodings: Default::default(),
                send_compression_encodings: Default::default(),
            }
        }
        pub fn with_interceptor<F>(
            inner: T,
            interceptor: F,
        ) -> InterceptedService<Self, F>
        where
            F: tonic::service::Interceptor,
        {
            InterceptedService::new(Self::new(inner), interceptor)
        }
        /// Enable decompressing requests with the given encoding.
        #[must_use]
        pub fn accept_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.accept_compression_encodings.enable(encoding);
            self
        }
        /// Compress responses with the given encoding, if the client supports it.
        #[must_use]
        pub fn send_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.send_compression_encodings.enable(encoding);
            self
        }
    }
    impl<T, B> tonic::codegen::Service<http::Request<B>> for BeamFnStateServer<T>
    where
        T: BeamFnState,
        B: Body + Send + 'static,
        B::Error: Into<StdError> + Send + 'static,
    {
        type Response = http::Response<tonic::body::BoxBody>;
        type Error = std::convert::Infallible;
        type Future = BoxFuture<Self::Response, Self::Error>;
        fn poll_ready(
            &mut self,
            _cx: &mut Context<'_>,
        ) -> Poll<Result<(), Self::Error>> {
            Poll::Ready(Ok(()))
        }
        fn call(&mut self, req: http::Request<B>) -> Self::Future {
            let inner = self.inner.clone();
            match req.uri().path() {
                "/org.apache.beam.model.fn_execution.v1.BeamFnState/State" => {
                    #[allow(non_camel_case_types)]
                    struct StateSvc<T: BeamFnState>(pub Arc<T>);
                    impl<
                        T: BeamFnState,
                    > tonic::server::StreamingService<super::StateRequest>
                    for StateSvc<T> {
                        type Response = super::StateResponse;
                        type ResponseStream = T::StateStream;
                        type Future = BoxFuture<
                            tonic::Response<Self::ResponseStream>,
                            tonic::Status,
                        >;
                        fn call(
                            &mut self,
                            request: tonic::Request<
                                tonic::Streaming<super::StateRequest>,
                            >,
                        ) -> Self::Future {
                            let inner = self.0.clone();
                            let fut = async move { (*inner).state(request).await };
                            Box::pin(fut)
                        }
                    }
                    let accept_compression_encodings = self.accept_compression_encodings;
                    let send_compression_encodings = self.send_compression_encodings;
                    let inner = self.inner.clone();
                    let fut = async move {
                        let inner = inner.0;
                        let method = StateSvc(inner);
                        let codec = tonic::codec::ProstCodec::default();
                        let mut grpc = tonic::server::Grpc::new(codec)
                            .apply_compression_config(
                                accept_compression_encodings,
                                send_compression_encodings,
                            );
                        let res = grpc.streaming(method, req).await;
                        Ok(res)
                    };
                    Box::pin(fut)
                }
                _ => {
                    Box::pin(async move {
                        Ok(
                            http::Response::builder()
                                .status(200)
                                .header("grpc-status", "12")
                                .header("content-type", "application/grpc")
                                .body(empty_body())
                                .unwrap(),
                        )
                    })
                }
            }
        }
    }
    impl<T: BeamFnState> Clone for BeamFnStateServer<T> {
        fn clone(&self) -> Self {
            let inner = self.inner.clone();
            Self {
                inner,
                accept_compression_encodings: self.accept_compression_encodings,
                send_compression_encodings: self.send_compression_encodings,
            }
        }
    }
    impl<T: BeamFnState> Clone for _Inner<T> {
        fn clone(&self) -> Self {
            Self(self.0.clone())
        }
    }
    impl<T: std::fmt::Debug> std::fmt::Debug for _Inner<T> {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            write!(f, "{:?}", self.0)
        }
    }
    impl<T: BeamFnState> tonic::server::NamedService for BeamFnStateServer<T> {
        const NAME: &'static str = "org.apache.beam.model.fn_execution.v1.BeamFnState";
    }
}
/// Generated server implementations.
pub mod beam_fn_logging_server {
    #![allow(unused_variables, dead_code, missing_docs, clippy::let_unit_value)]
    use tonic::codegen::*;
    ///Generated trait containing gRPC methods that should be implemented for use with BeamFnLoggingServer.
    #[async_trait]
    pub trait BeamFnLogging: Send + Sync + 'static {
        ///Server streaming response type for the Logging method.
        type LoggingStream: futures_core::Stream<
                Item = Result<super::LogControl, tonic::Status>,
            >
            + Send
            + 'static;
        /// Allows for the SDK to emit log entries which the runner can
        /// associate with the active job.
        async fn logging(
            &self,
            request: tonic::Request<tonic::Streaming<super::log_entry::List>>,
        ) -> Result<tonic::Response<Self::LoggingStream>, tonic::Status>;
    }
    /// Stable
    #[derive(Debug)]
    pub struct BeamFnLoggingServer<T: BeamFnLogging> {
        inner: _Inner<T>,
        accept_compression_encodings: EnabledCompressionEncodings,
        send_compression_encodings: EnabledCompressionEncodings,
    }
    struct _Inner<T>(Arc<T>);
    impl<T: BeamFnLogging> BeamFnLoggingServer<T> {
        pub fn new(inner: T) -> Self {
            Self::from_arc(Arc::new(inner))
        }
        pub fn from_arc(inner: Arc<T>) -> Self {
            let inner = _Inner(inner);
            Self {
                inner,
                accept_compression_encodings: Default::default(),
                send_compression_encodings: Default::default(),
            }
        }
        pub fn with_interceptor<F>(
            inner: T,
            interceptor: F,
        ) -> InterceptedService<Self, F>
        where
            F: tonic::service::Interceptor,
        {
            InterceptedService::new(Self::new(inner), interceptor)
        }
        /// Enable decompressing requests with the given encoding.
        #[must_use]
        pub fn accept_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.accept_compression_encodings.enable(encoding);
            self
        }
        /// Compress responses with the given encoding, if the client supports it.
        #[must_use]
        pub fn send_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.send_compression_encodings.enable(encoding);
            self
        }
    }
    impl<T, B> tonic::codegen::Service<http::Request<B>> for BeamFnLoggingServer<T>
    where
        T: BeamFnLogging,
        B: Body + Send + 'static,
        B::Error: Into<StdError> + Send + 'static,
    {
        type Response = http::Response<tonic::body::BoxBody>;
        type Error = std::convert::Infallible;
        type Future = BoxFuture<Self::Response, Self::Error>;
        fn poll_ready(
            &mut self,
            _cx: &mut Context<'_>,
        ) -> Poll<Result<(), Self::Error>> {
            Poll::Ready(Ok(()))
        }
        fn call(&mut self, req: http::Request<B>) -> Self::Future {
            let inner = self.inner.clone();
            match req.uri().path() {
                "/org.apache.beam.model.fn_execution.v1.BeamFnLogging/Logging" => {
                    #[allow(non_camel_case_types)]
                    struct LoggingSvc<T: BeamFnLogging>(pub Arc<T>);
                    impl<
                        T: BeamFnLogging,
                    > tonic::server::StreamingService<super::log_entry::List>
                    for LoggingSvc<T> {
                        type Response = super::LogControl;
                        type ResponseStream = T::LoggingStream;
                        type Future = BoxFuture<
                            tonic::Response<Self::ResponseStream>,
                            tonic::Status,
                        >;
                        fn call(
                            &mut self,
                            request: tonic::Request<
                                tonic::Streaming<super::log_entry::List>,
                            >,
                        ) -> Self::Future {
                            let inner = self.0.clone();
                            let fut = async move { (*inner).logging(request).await };
                            Box::pin(fut)
                        }
                    }
                    let accept_compression_encodings = self.accept_compression_encodings;
                    let send_compression_encodings = self.send_compression_encodings;
                    let inner = self.inner.clone();
                    let fut = async move {
                        let inner = inner.0;
                        let method = LoggingSvc(inner);
                        let codec = tonic::codec::ProstCodec::default();
                        let mut grpc = tonic::server::Grpc::new(codec)
                            .apply_compression_config(
                                accept_compression_encodings,
                                send_compression_encodings,
                            );
                        let res = grpc.streaming(method, req).await;
                        Ok(res)
                    };
                    Box::pin(fut)
                }
                _ => {
                    Box::pin(async move {
                        Ok(
                            http::Response::builder()
                                .status(200)
                                .header("grpc-status", "12")
                                .header("content-type", "application/grpc")
                                .body(empty_body())
                                .unwrap(),
                        )
                    })
                }
            }
        }
    }
    impl<T: BeamFnLogging> Clone for BeamFnLoggingServer<T> {
        fn clone(&self) -> Self {
            let inner = self.inner.clone();
            Self {
                inner,
                accept_compression_encodings: self.accept_compression_encodings,
                send_compression_encodings: self.send_compression_encodings,
            }
        }
    }
    impl<T: BeamFnLogging> Clone for _Inner<T> {
        fn clone(&self) -> Self {
            Self(self.0.clone())
        }
    }
    impl<T: std::fmt::Debug> std::fmt::Debug for _Inner<T> {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            write!(f, "{:?}", self.0)
        }
    }
    impl<T: BeamFnLogging> tonic::server::NamedService for BeamFnLoggingServer<T> {
        const NAME: &'static str = "org.apache.beam.model.fn_execution.v1.BeamFnLogging";
    }
}
/// Generated server implementations.
pub mod beam_fn_external_worker_pool_server {
    #![allow(unused_variables, dead_code, missing_docs, clippy::let_unit_value)]
    use tonic::codegen::*;
    ///Generated trait containing gRPC methods that should be implemented for use with BeamFnExternalWorkerPoolServer.
    #[async_trait]
    pub trait BeamFnExternalWorkerPool: Send + Sync + 'static {
        /// Start the SDK worker with the given ID.
        async fn start_worker(
            &self,
            request: tonic::Request<super::StartWorkerRequest>,
        ) -> Result<tonic::Response<super::StartWorkerResponse>, tonic::Status>;
        /// Stop the SDK worker.
        async fn stop_worker(
            &self,
            request: tonic::Request<super::StopWorkerRequest>,
        ) -> Result<tonic::Response<super::StopWorkerResponse>, tonic::Status>;
    }
    #[derive(Debug)]
    pub struct BeamFnExternalWorkerPoolServer<T: BeamFnExternalWorkerPool> {
        inner: _Inner<T>,
        accept_compression_encodings: EnabledCompressionEncodings,
        send_compression_encodings: EnabledCompressionEncodings,
    }
    struct _Inner<T>(Arc<T>);
    impl<T: BeamFnExternalWorkerPool> BeamFnExternalWorkerPoolServer<T> {
        pub fn new(inner: T) -> Self {
            Self::from_arc(Arc::new(inner))
        }
        pub fn from_arc(inner: Arc<T>) -> Self {
            let inner = _Inner(inner);
            Self {
                inner,
                accept_compression_encodings: Default::default(),
                send_compression_encodings: Default::default(),
            }
        }
        pub fn with_interceptor<F>(
            inner: T,
            interceptor: F,
        ) -> InterceptedService<Self, F>
        where
            F: tonic::service::Interceptor,
        {
            InterceptedService::new(Self::new(inner), interceptor)
        }
        /// Enable decompressing requests with the given encoding.
        #[must_use]
        pub fn accept_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.accept_compression_encodings.enable(encoding);
            self
        }
        /// Compress responses with the given encoding, if the client supports it.
        #[must_use]
        pub fn send_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.send_compression_encodings.enable(encoding);
            self
        }
    }
    impl<T, B> tonic::codegen::Service<http::Request<B>>
    for BeamFnExternalWorkerPoolServer<T>
    where
        T: BeamFnExternalWorkerPool,
        B: Body + Send + 'static,
        B::Error: Into<StdError> + Send + 'static,
    {
        type Response = http::Response<tonic::body::BoxBody>;
        type Error = std::convert::Infallible;
        type Future = BoxFuture<Self::Response, Self::Error>;
        fn poll_ready(
            &mut self,
            _cx: &mut Context<'_>,
        ) -> Poll<Result<(), Self::Error>> {
            Poll::Ready(Ok(()))
        }
        fn call(&mut self, req: http::Request<B>) -> Self::Future {
            let inner = self.inner.clone();
            match req.uri().path() {
                "/org.apache.beam.model.fn_execution.v1.BeamFnExternalWorkerPool/StartWorker" => {
                    #[allow(non_camel_case_types)]
                    struct StartWorkerSvc<T: BeamFnExternalWorkerPool>(pub Arc<T>);
                    impl<
                        T: BeamFnExternalWorkerPool,
                    > tonic::server::UnaryService<super::StartWorkerRequest>
                    for StartWorkerSvc<T> {
                        type Response = super::StartWorkerResponse;
                        type Future = BoxFuture<
                            tonic::Response<Self::Response>,
                            tonic::Status,
                        >;
                        fn call(
                            &mut self,
                            request: tonic::Request<super::StartWorkerRequest>,
                        ) -> Self::Future {
                            let inner = self.0.clone();
                            let fut = async move {
                                (*inner).start_worker(request).await
                            };
                            Box::pin(fut)
                        }
                    }
                    let accept_compression_encodings = self.accept_compression_encodings;
                    let send_compression_encodings = self.send_compression_encodings;
                    let inner = self.inner.clone();
                    let fut = async move {
                        let inner = inner.0;
                        let method = StartWorkerSvc(inner);
                        let codec = tonic::codec::ProstCodec::default();
                        let mut grpc = tonic::server::Grpc::new(codec)
                            .apply_compression_config(
                                accept_compression_encodings,
                                send_compression_encodings,
                            );
                        let res = grpc.unary(method, req).await;
                        Ok(res)
                    };
                    Box::pin(fut)
                }
                "/org.apache.beam.model.fn_execution.v1.BeamFnExternalWorkerPool/StopWorker" => {
                    #[allow(non_camel_case_types)]
                    struct StopWorkerSvc<T: BeamFnExternalWorkerPool>(pub Arc<T>);
                    impl<
                        T: BeamFnExternalWorkerPool,
                    > tonic::server::UnaryService<super::StopWorkerRequest>
                    for StopWorkerSvc<T> {
                        type Response = super::StopWorkerResponse;
                        type Future = BoxFuture<
                            tonic::Response<Self::Response>,
                            tonic::Status,
                        >;
                        fn call(
                            &mut self,
                            request: tonic::Request<super::StopWorkerRequest>,
                        ) -> Self::Future {
                            let inner = self.0.clone();
                            let fut = async move { (*inner).stop_worker(request).await };
                            Box::pin(fut)
                        }
                    }
                    let accept_compression_encodings = self.accept_compression_encodings;
                    let send_compression_encodings = self.send_compression_encodings;
                    let inner = self.inner.clone();
                    let fut = async move {
                        let inner = inner.0;
                        let method = StopWorkerSvc(inner);
                        let codec = tonic::codec::ProstCodec::default();
                        let mut grpc = tonic::server::Grpc::new(codec)
                            .apply_compression_config(
                                accept_compression_encodings,
                                send_compression_encodings,
                            );
                        let res = grpc.unary(method, req).await;
                        Ok(res)
                    };
                    Box::pin(fut)
                }
                _ => {
                    Box::pin(async move {
                        Ok(
                            http::Response::builder()
                                .status(200)
                                .header("grpc-status", "12")
                                .header("content-type", "application/grpc")
                                .body(empty_body())
                                .unwrap(),
                        )
                    })
                }
            }
        }
    }
    impl<T: BeamFnExternalWorkerPool> Clone for BeamFnExternalWorkerPoolServer<T> {
        fn clone(&self) -> Self {
            let inner = self.inner.clone();
            Self {
                inner,
                accept_compression_encodings: self.accept_compression_encodings,
                send_compression_encodings: self.send_compression_encodings,
            }
        }
    }
    impl<T: BeamFnExternalWorkerPool> Clone for _Inner<T> {
        fn clone(&self) -> Self {
            Self(self.0.clone())
        }
    }
    impl<T: std::fmt::Debug> std::fmt::Debug for _Inner<T> {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            write!(f, "{:?}", self.0)
        }
    }
    impl<T: BeamFnExternalWorkerPool> tonic::server::NamedService
    for BeamFnExternalWorkerPoolServer<T> {
        const NAME: &'static str = "org.apache.beam.model.fn_execution.v1.BeamFnExternalWorkerPool";
    }
}
/// Generated server implementations.
pub mod beam_fn_worker_status_server {
    #![allow(unused_variables, dead_code, missing_docs, clippy::let_unit_value)]
    use tonic::codegen::*;
    ///Generated trait containing gRPC methods that should be implemented for use with BeamFnWorkerStatusServer.
    #[async_trait]
    pub trait BeamFnWorkerStatus: Send + Sync + 'static {
        ///Server streaming response type for the WorkerStatus method.
        type WorkerStatusStream: futures_core::Stream<
                Item = Result<super::WorkerStatusRequest, tonic::Status>,
            >
            + Send
            + 'static;
        async fn worker_status(
            &self,
            request: tonic::Request<tonic::Streaming<super::WorkerStatusResponse>>,
        ) -> Result<tonic::Response<Self::WorkerStatusStream>, tonic::Status>;
    }
    /// API for SDKs to report debug-related statuses to runner during pipeline execution.
    #[derive(Debug)]
    pub struct BeamFnWorkerStatusServer<T: BeamFnWorkerStatus> {
        inner: _Inner<T>,
        accept_compression_encodings: EnabledCompressionEncodings,
        send_compression_encodings: EnabledCompressionEncodings,
    }
    struct _Inner<T>(Arc<T>);
    impl<T: BeamFnWorkerStatus> BeamFnWorkerStatusServer<T> {
        pub fn new(inner: T) -> Self {
            Self::from_arc(Arc::new(inner))
        }
        pub fn from_arc(inner: Arc<T>) -> Self {
            let inner = _Inner(inner);
            Self {
                inner,
                accept_compression_encodings: Default::default(),
                send_compression_encodings: Default::default(),
            }
        }
        pub fn with_interceptor<F>(
            inner: T,
            interceptor: F,
        ) -> InterceptedService<Self, F>
        where
            F: tonic::service::Interceptor,
        {
            InterceptedService::new(Self::new(inner), interceptor)
        }
        /// Enable decompressing requests with the given encoding.
        #[must_use]
        pub fn accept_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.accept_compression_encodings.enable(encoding);
            self
        }
        /// Compress responses with the given encoding, if the client supports it.
        #[must_use]
        pub fn send_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.send_compression_encodings.enable(encoding);
            self
        }
    }
    impl<T, B> tonic::codegen::Service<http::Request<B>> for BeamFnWorkerStatusServer<T>
    where
        T: BeamFnWorkerStatus,
        B: Body + Send + 'static,
        B::Error: Into<StdError> + Send + 'static,
    {
        type Response = http::Response<tonic::body::BoxBody>;
        type Error = std::convert::Infallible;
        type Future = BoxFuture<Self::Response, Self::Error>;
        fn poll_ready(
            &mut self,
            _cx: &mut Context<'_>,
        ) -> Poll<Result<(), Self::Error>> {
            Poll::Ready(Ok(()))
        }
        fn call(&mut self, req: http::Request<B>) -> Self::Future {
            let inner = self.inner.clone();
            match req.uri().path() {
                "/org.apache.beam.model.fn_execution.v1.BeamFnWorkerStatus/WorkerStatus" => {
                    #[allow(non_camel_case_types)]
                    struct WorkerStatusSvc<T: BeamFnWorkerStatus>(pub Arc<T>);
                    impl<
                        T: BeamFnWorkerStatus,
                    > tonic::server::StreamingService<super::WorkerStatusResponse>
                    for WorkerStatusSvc<T> {
                        type Response = super::WorkerStatusRequest;
                        type ResponseStream = T::WorkerStatusStream;
                        type Future = BoxFuture<
                            tonic::Response<Self::ResponseStream>,
                            tonic::Status,
                        >;
                        fn call(
                            &mut self,
                            request: tonic::Request<
                                tonic::Streaming<super::WorkerStatusResponse>,
                            >,
                        ) -> Self::Future {
                            let inner = self.0.clone();
                            let fut = async move {
                                (*inner).worker_status(request).await
                            };
                            Box::pin(fut)
                        }
                    }
                    let accept_compression_encodings = self.accept_compression_encodings;
                    let send_compression_encodings = self.send_compression_encodings;
                    let inner = self.inner.clone();
                    let fut = async move {
                        let inner = inner.0;
                        let method = WorkerStatusSvc(inner);
                        let codec = tonic::codec::ProstCodec::default();
                        let mut grpc = tonic::server::Grpc::new(codec)
                            .apply_compression_config(
                                accept_compression_encodings,
                                send_compression_encodings,
                            );
                        let res = grpc.streaming(method, req).await;
                        Ok(res)
                    };
                    Box::pin(fut)
                }
                _ => {
                    Box::pin(async move {
                        Ok(
                            http::Response::builder()
                                .status(200)
                                .header("grpc-status", "12")
                                .header("content-type", "application/grpc")
                                .body(empty_body())
                                .unwrap(),
                        )
                    })
                }
            }
        }
    }
    impl<T: BeamFnWorkerStatus> Clone for BeamFnWorkerStatusServer<T> {
        fn clone(&self) -> Self {
            let inner = self.inner.clone();
            Self {
                inner,
                accept_compression_encodings: self.accept_compression_encodings,
                send_compression_encodings: self.send_compression_encodings,
            }
        }
    }
    impl<T: BeamFnWorkerStatus> Clone for _Inner<T> {
        fn clone(&self) -> Self {
            Self(self.0.clone())
        }
    }
    impl<T: std::fmt::Debug> std::fmt::Debug for _Inner<T> {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            write!(f, "{:?}", self.0)
        }
    }
    impl<T: BeamFnWorkerStatus> tonic::server::NamedService
    for BeamFnWorkerStatusServer<T> {
        const NAME: &'static str = "org.apache.beam.model.fn_execution.v1.BeamFnWorkerStatus";
    }
}
/// A request to get the provision info of a SDK harness worker instance.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct GetProvisionInfoRequest {}
/// A response containing the provision info of a SDK harness worker instance.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct GetProvisionInfoResponse {
    #[prost(message, optional, tag = "1")]
    pub info: ::core::option::Option<ProvisionInfo>,
}
/// Runtime provisioning information for a SDK harness worker instance,
/// such as pipeline options, resource constraints and other job metadata
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ProvisionInfo {
    /// (required) Pipeline options. For non-template jobs, the options are
    /// identical to what is passed to job submission.
    #[prost(message, optional, tag = "3")]
    pub pipeline_options: ::core::option::Option<::prost_types::Struct>,
    /// (required) The artifact retrieval token produced by
    /// LegacyArtifactStagingService.CommitManifestResponse.
    #[prost(string, tag = "6")]
    pub retrieval_token: ::prost::alloc::string::String,
    /// (optional) The endpoint that the runner is hosting for the SDK to submit
    /// status reports to during pipeline execution. This field will only be
    /// populated if the runner supports SDK status reports. For more details see
    /// <https://s.apache.org/beam-fn-api-harness-status>
    #[prost(message, optional, tag = "7")]
    pub status_endpoint: ::core::option::Option<
        super::super::pipeline::v1::ApiServiceDescriptor,
    >,
    /// (optional) The logging endpoint this SDK should use.
    #[prost(message, optional, tag = "8")]
    pub logging_endpoint: ::core::option::Option<
        super::super::pipeline::v1::ApiServiceDescriptor,
    >,
    /// (optional) The artifact retrieval endpoint this SDK should use.
    #[prost(message, optional, tag = "9")]
    pub artifact_endpoint: ::core::option::Option<
        super::super::pipeline::v1::ApiServiceDescriptor,
    >,
    /// (optional) The control endpoint this SDK should use.
    #[prost(message, optional, tag = "10")]
    pub control_endpoint: ::core::option::Option<
        super::super::pipeline::v1::ApiServiceDescriptor,
    >,
    /// The set of dependencies that should be staged into this environment.
    #[prost(message, repeated, tag = "11")]
    pub dependencies: ::prost::alloc::vec::Vec<
        super::super::pipeline::v1::ArtifactInformation,
    >,
    /// (optional) A set of capabilities that this SDK is allowed to use in its
    /// interactions with this runner.
    #[prost(string, repeated, tag = "12")]
    pub runner_capabilities: ::prost::alloc::vec::Vec<::prost::alloc::string::String>,
    /// (optional) Runtime environment metadata that are static throughout the
    /// pipeline execution.
    #[prost(map = "string, string", tag = "13")]
    pub metadata: ::std::collections::HashMap<
        ::prost::alloc::string::String,
        ::prost::alloc::string::String,
    >,
    /// (optional) If this environment supports SIBLING_WORKERS, used to indicate
    /// the ids of sibling workers, if any, that should be started in addition
    /// to this worker (which already has its own worker id).
    #[prost(string, repeated, tag = "14")]
    pub sibling_worker_ids: ::prost::alloc::vec::Vec<::prost::alloc::string::String>,
}
/// Generated client implementations.
pub mod provision_service_client {
    #![allow(unused_variables, dead_code, missing_docs, clippy::let_unit_value)]
    use tonic::codegen::*;
    use tonic::codegen::http::Uri;
    /// A service to provide runtime provisioning information to the SDK harness
    /// worker instances -- such as pipeline options, resource constraints and
    /// other job metadata -- needed by an SDK harness instance to initialize.
    #[derive(Debug, Clone)]
    pub struct ProvisionServiceClient<T> {
        inner: tonic::client::Grpc<T>,
    }
    impl ProvisionServiceClient<tonic::transport::Channel> {
        /// Attempt to create a new client by connecting to a given endpoint.
        pub async fn connect<D>(dst: D) -> Result<Self, tonic::transport::Error>
        where
            D: std::convert::TryInto<tonic::transport::Endpoint>,
            D::Error: Into<StdError>,
        {
            let conn = tonic::transport::Endpoint::new(dst)?.connect().await?;
            Ok(Self::new(conn))
        }
    }
    impl<T> ProvisionServiceClient<T>
    where
        T: tonic::client::GrpcService<tonic::body::BoxBody>,
        T::Error: Into<StdError>,
        T::ResponseBody: Body<Data = Bytes> + Send + 'static,
        <T::ResponseBody as Body>::Error: Into<StdError> + Send,
    {
        pub fn new(inner: T) -> Self {
            let inner = tonic::client::Grpc::new(inner);
            Self { inner }
        }
        pub fn with_origin(inner: T, origin: Uri) -> Self {
            let inner = tonic::client::Grpc::with_origin(inner, origin);
            Self { inner }
        }
        pub fn with_interceptor<F>(
            inner: T,
            interceptor: F,
        ) -> ProvisionServiceClient<InterceptedService<T, F>>
        where
            F: tonic::service::Interceptor,
            T::ResponseBody: Default,
            T: tonic::codegen::Service<
                http::Request<tonic::body::BoxBody>,
                Response = http::Response<
                    <T as tonic::client::GrpcService<tonic::body::BoxBody>>::ResponseBody,
                >,
            >,
            <T as tonic::codegen::Service<
                http::Request<tonic::body::BoxBody>,
            >>::Error: Into<StdError> + Send + Sync,
        {
            ProvisionServiceClient::new(InterceptedService::new(inner, interceptor))
        }
        /// Compress requests with the given encoding.
        ///
        /// This requires the server to support it otherwise it might respond with an
        /// error.
        #[must_use]
        pub fn send_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.inner = self.inner.send_compressed(encoding);
            self
        }
        /// Enable decompressing responses.
        #[must_use]
        pub fn accept_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.inner = self.inner.accept_compressed(encoding);
            self
        }
        /// Get provision information for the SDK harness worker instance.
        pub async fn get_provision_info(
            &mut self,
            request: impl tonic::IntoRequest<super::GetProvisionInfoRequest>,
        ) -> Result<tonic::Response<super::GetProvisionInfoResponse>, tonic::Status> {
            self.inner
                .ready()
                .await
                .map_err(|e| {
                    tonic::Status::new(
                        tonic::Code::Unknown,
                        format!("Service was not ready: {}", e.into()),
                    )
                })?;
            let codec = tonic::codec::ProstCodec::default();
            let path = http::uri::PathAndQuery::from_static(
                "/org.apache.beam.model.fn_execution.v1.ProvisionService/GetProvisionInfo",
            );
            self.inner.unary(request.into_request(), path, codec).await
        }
    }
}
/// Generated server implementations.
pub mod provision_service_server {
    #![allow(unused_variables, dead_code, missing_docs, clippy::let_unit_value)]
    use tonic::codegen::*;
    ///Generated trait containing gRPC methods that should be implemented for use with ProvisionServiceServer.
    #[async_trait]
    pub trait ProvisionService: Send + Sync + 'static {
        /// Get provision information for the SDK harness worker instance.
        async fn get_provision_info(
            &self,
            request: tonic::Request<super::GetProvisionInfoRequest>,
        ) -> Result<tonic::Response<super::GetProvisionInfoResponse>, tonic::Status>;
    }
    /// A service to provide runtime provisioning information to the SDK harness
    /// worker instances -- such as pipeline options, resource constraints and
    /// other job metadata -- needed by an SDK harness instance to initialize.
    #[derive(Debug)]
    pub struct ProvisionServiceServer<T: ProvisionService> {
        inner: _Inner<T>,
        accept_compression_encodings: EnabledCompressionEncodings,
        send_compression_encodings: EnabledCompressionEncodings,
    }
    struct _Inner<T>(Arc<T>);
    impl<T: ProvisionService> ProvisionServiceServer<T> {
        pub fn new(inner: T) -> Self {
            Self::from_arc(Arc::new(inner))
        }
        pub fn from_arc(inner: Arc<T>) -> Self {
            let inner = _Inner(inner);
            Self {
                inner,
                accept_compression_encodings: Default::default(),
                send_compression_encodings: Default::default(),
            }
        }
        pub fn with_interceptor<F>(
            inner: T,
            interceptor: F,
        ) -> InterceptedService<Self, F>
        where
            F: tonic::service::Interceptor,
        {
            InterceptedService::new(Self::new(inner), interceptor)
        }
        /// Enable decompressing requests with the given encoding.
        #[must_use]
        pub fn accept_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.accept_compression_encodings.enable(encoding);
            self
        }
        /// Compress responses with the given encoding, if the client supports it.
        #[must_use]
        pub fn send_compressed(mut self, encoding: CompressionEncoding) -> Self {
            self.send_compression_encodings.enable(encoding);
            self
        }
    }
    impl<T, B> tonic::codegen::Service<http::Request<B>> for ProvisionServiceServer<T>
    where
        T: ProvisionService,
        B: Body + Send + 'static,
        B::Error: Into<StdError> + Send + 'static,
    {
        type Response = http::Response<tonic::body::BoxBody>;
        type Error = std::convert::Infallible;
        type Future = BoxFuture<Self::Response, Self::Error>;
        fn poll_ready(
            &mut self,
            _cx: &mut Context<'_>,
        ) -> Poll<Result<(), Self::Error>> {
            Poll::Ready(Ok(()))
        }
        fn call(&mut self, req: http::Request<B>) -> Self::Future {
            let inner = self.inner.clone();
            match req.uri().path() {
                "/org.apache.beam.model.fn_execution.v1.ProvisionService/GetProvisionInfo" => {
                    #[allow(non_camel_case_types)]
                    struct GetProvisionInfoSvc<T: ProvisionService>(pub Arc<T>);
                    impl<
                        T: ProvisionService,
                    > tonic::server::UnaryService<super::GetProvisionInfoRequest>
                    for GetProvisionInfoSvc<T> {
                        type Response = super::GetProvisionInfoResponse;
                        type Future = BoxFuture<
                            tonic::Response<Self::Response>,
                            tonic::Status,
                        >;
                        fn call(
                            &mut self,
                            request: tonic::Request<super::GetProvisionInfoRequest>,
                        ) -> Self::Future {
                            let inner = self.0.clone();
                            let fut = async move {
                                (*inner).get_provision_info(request).await
                            };
                            Box::pin(fut)
                        }
                    }
                    let accept_compression_encodings = self.accept_compression_encodings;
                    let send_compression_encodings = self.send_compression_encodings;
                    let inner = self.inner.clone();
                    let fut = async move {
                        let inner = inner.0;
                        let method = GetProvisionInfoSvc(inner);
                        let codec = tonic::codec::ProstCodec::default();
                        let mut grpc = tonic::server::Grpc::new(codec)
                            .apply_compression_config(
                                accept_compression_encodings,
                                send_compression_encodings,
                            );
                        let res = grpc.unary(method, req).await;
                        Ok(res)
                    };
                    Box::pin(fut)
                }
                _ => {
                    Box::pin(async move {
                        Ok(
                            http::Response::builder()
                                .status(200)
                                .header("grpc-status", "12")
                                .header("content-type", "application/grpc")
                                .body(empty_body())
                                .unwrap(),
                        )
                    })
                }
            }
        }
    }
    impl<T: ProvisionService> Clone for ProvisionServiceServer<T> {
        fn clone(&self) -> Self {
            let inner = self.inner.clone();
            Self {
                inner,
                accept_compression_encodings: self.accept_compression_encodings,
                send_compression_encodings: self.send_compression_encodings,
            }
        }
    }
    impl<T: ProvisionService> Clone for _Inner<T> {
        fn clone(&self) -> Self {
            Self(self.0.clone())
        }
    }
    impl<T: std::fmt::Debug> std::fmt::Debug for _Inner<T> {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            write!(f, "{:?}", self.0)
        }
    }
    impl<T: ProvisionService> tonic::server::NamedService for ProvisionServiceServer<T> {
        const NAME: &'static str = "org.apache.beam.model.fn_execution.v1.ProvisionService";
    }
}
