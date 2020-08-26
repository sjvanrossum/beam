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
package org.apache.beam.sdk.io.googleads;

import com.google.ads.googleads.lib.GoogleAdsClient;
import com.google.ads.googleads.v3.enums.SummaryRowSettingEnum.SummaryRowSetting;
import com.google.ads.googleads.v3.services.GoogleAdsRow;
import com.google.ads.googleads.v3.services.GoogleAdsServiceClient;
import com.google.ads.googleads.v3.services.SearchGoogleAdsStreamRequest;
import com.google.ads.googleads.v3.services.SearchGoogleAdsStreamResponse;
import com.google.api.gax.rpc.ServerStream;
import com.google.auto.value.AutoValue;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.transforms.*;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.values.*;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.annotations.VisibleForTesting;

public class GoogleAdsIO {
  public static Read read() {
    return new AutoValue_GoogleAdsIO_Read.Builder().build();
  }

  public static ReadAll readAll() {
    return new AutoValue_GoogleAdsIO_ReadAll.Builder().build();
  }

  @AutoValue
  public abstract static class Read extends PTransform<PBegin, PCollection<GoogleAdsRow>> {
    abstract ValueProvider<Long> getCustomerId();

    abstract ValueProvider<String> getQuery();

    abstract GoogleAdsClient getGoogleAdsClient();

    abstract Builder toBuilder();

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setCustomerId(ValueProvider<Long> customerId);

      abstract Builder setQuery(ValueProvider<String> query);

      abstract Builder setGoogleAdsClient(GoogleAdsClient googleAdsClient);

      abstract Read build();
    }

    public Read withCustomerId(Long customerId) {
      return withCustomerId(ValueProvider.StaticValueProvider.of(customerId));
    }

    public Read withCustomerId(ValueProvider<Long> customerId) {
      return toBuilder().setCustomerId(customerId).build();
    }

    public Read withQuery(String query) {
      return withQuery(ValueProvider.StaticValueProvider.of(query));
    }

    public Read withQuery(ValueProvider<String> query) {
      return toBuilder().setQuery(query).build();
    }

    public Read withGoogleAdsClient(GoogleAdsClient googleAdsClient) {
      return toBuilder().setGoogleAdsClient(googleAdsClient).build();
    }

    @Override
    public PCollection<GoogleAdsRow> expand(PBegin input) {
      return input
          .apply(Create.of((Void) null))
          .apply(MapElements
              .into(new TypeDescriptor<SearchGoogleAdsStreamRequest>() {})
              .via(ignored -> SearchGoogleAdsStreamRequest
                  .newBuilder()
                  .setCustomerId(Long.toString(getCustomerId().get()))
                  .setQuery(getQuery().get())
                  .setSummaryRowSetting(SummaryRowSetting.NO_SUMMARY_ROW)
                  .build()))
          .apply(readAll().withGoogleAdsClient(getGoogleAdsClient()));
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      super.populateDisplayData(builder);

      builder
          .add(DisplayData.item("customerId", getCustomerId()).withLabel("Customer ID"))
          .add(DisplayData.item("query", getQuery()).withLabel("Query"));
    }
  }

  @AutoValue
  public abstract static class ReadAll extends PTransform<PCollection<SearchGoogleAdsStreamRequest>, PCollection<GoogleAdsRow>> {

    abstract GoogleAdsClient getGoogleAdsClient();

    abstract Builder toBuilder();

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setGoogleAdsClient(GoogleAdsClient googleAdsClient);

      abstract ReadAll build();
    }

    public ReadAll withGoogleAdsClient(GoogleAdsClient googleAdsClient) {
      return toBuilder().setGoogleAdsClient(googleAdsClient).build();
    }

    @Override
    public PCollection<GoogleAdsRow> expand(PCollection<SearchGoogleAdsStreamRequest> input) {
      return input.apply(ParDo.of(new ReadAllFn(getGoogleAdsClient())));
    }

    @VisibleForTesting
    static class ReadAllFn extends DoFn<SearchGoogleAdsStreamRequest, GoogleAdsRow> {
      private final GoogleAdsClient client;
      private transient GoogleAdsServiceClient serviceClient;

      ReadAllFn(GoogleAdsClient client) {
        this.client = client;
      }

      @Setup
      public void setup() {
        serviceClient = client.getLatestVersion().createGoogleAdsServiceClient();
      }

      @Teardown
      public void teardown() {
        serviceClient.close();
      }

      @ProcessElement
      public void processElement(ProcessContext c) {
        ServerStream<SearchGoogleAdsStreamResponse> stream = serviceClient.searchStreamCallable().call(c.element());

        for (SearchGoogleAdsStreamResponse response : stream) {
          for (GoogleAdsRow row : response.getResultsList()) {
            c.output(row);
          }
        }
      }
    }
  }
}
