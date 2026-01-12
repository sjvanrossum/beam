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
package org.apache.beam.sdk.extensions.sql;

import io.substrait.proto.AggregateFunction;
import io.substrait.proto.AggregateRel;
import io.substrait.proto.AggregateRel.Grouping;
import io.substrait.proto.AggregateRel.Measure;
import io.substrait.proto.Expression;
import io.substrait.proto.Expression.FieldReference;
import io.substrait.proto.Expression.FieldReference.RootReference;
import io.substrait.proto.Expression.Literal;
import io.substrait.proto.Expression.MaskExpression;
import io.substrait.proto.Expression.MaskExpression.StructItem;
import io.substrait.proto.Expression.MaskExpression.StructSelect;
import io.substrait.proto.Expression.ReferenceSegment;
import io.substrait.proto.Expression.ReferenceSegment.StructField;
import io.substrait.proto.Expression.ScalarFunction;
import io.substrait.proto.FunctionArgument;
import io.substrait.proto.NamedStruct;
import io.substrait.proto.Plan;
import io.substrait.proto.PlanRel;
import io.substrait.proto.ProjectRel;
import io.substrait.proto.ReadRel;
import io.substrait.proto.ReadRel.NamedTable;
import io.substrait.proto.Rel;
import io.substrait.proto.RelCommon;
import io.substrait.proto.RelCommon.Emit;
import io.substrait.proto.RelRoot;
import io.substrait.proto.SimpleExtensionDeclaration;
import io.substrait.proto.SimpleExtensionDeclaration.ExtensionFunction;
import io.substrait.proto.SimpleExtensionURN;
import io.substrait.proto.Type;
import io.substrait.proto.Version;
import java.util.List;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.values.Row;
import org.junit.Rule;
import org.junit.Test;

public class BeamSqlSubstraitTest {
  @Rule public final TestPipeline pipeline = TestPipeline.create();

  /*
   Reference query (DuckDB):
   SELECT
     first_char,
     COUNT(*) AS tweet_count,
     SUM(likes) AS tweet_likes
   FROM (
     SELECT
       SUBSTRING(tweet, 1, 1) AS first_char,
       likes
     FROM read_parquet(
       'gs://cloud-samples-data-us-central1/bigquery/federated-formats-reference-file-schema/*-twitter.parquet',
       union_by_name=True))
   GROUP BY 1;
  */

  // DuckDB Substrait Extension (v1.2.2) output with fixes
  private static final Plan PLAN =
      Plan.newBuilder()
          .setVersion(
              Version.newBuilder()
                  .setMajorNumber(0)
                  /* Changed from 53 to 77 to reflect Substrait version. */
                  .setMinorNumber(77)
                  .setPatchNumber(0)
                  /* Changed from DuckDB to manual due to changes. */
                  .setProducer("manual"))
          /* Changed from extension_uris to extension_urns due to deprecation. */
          /* Added missing declaration for extension:io.substrait:functions_string. */
          .addExtensionUrns(
              SimpleExtensionURN.newBuilder()
                  .setExtensionUrnAnchor(0)
                  .setUrn("extension:io.substrait:functions_string"))
          .addExtensionUrns(
              SimpleExtensionURN.newBuilder()
                  .setExtensionUrnAnchor(1)
                  .setUrn("extension:io.substrait:functions_aggregate_generic"))
          .addExtensionUrns(
              SimpleExtensionURN.newBuilder()
                  .setExtensionUrnAnchor(2)
                  .setUrn("extension:io.substrait:functions_arithmetic"))
          .addExtensions(
              SimpleExtensionDeclaration.newBuilder()
                  .setExtensionFunction(
                      ExtensionFunction.newBuilder()
                          .setExtensionUrnReference(0)
                          .setFunctionAnchor(1)
                          /* Changed name from substring to substring:str_i32_i32 to match registration. */
                          .setName("substring:str_i32_i32")))
          .addExtensions(
              SimpleExtensionDeclaration.newBuilder()
                  .setExtensionFunction(
                      ExtensionFunction.newBuilder()
                          .setExtensionUrnReference(1)
                          .setFunctionAnchor(2)
                          /* Changed name from count to count: to match registration. */
                          .setName("count:")))
          .addExtensions(
              SimpleExtensionDeclaration.newBuilder()
                  .setExtensionFunction(
                      ExtensionFunction.newBuilder()
                          .setExtensionUrnReference(2)
                          .setFunctionAnchor(3)
                          .setName("sum:i64")))
          .addRelations(
              PlanRel.newBuilder()
                  .setRoot(
                      RelRoot.newBuilder()
                          .setInput(
                              Rel.newBuilder()
                                  .setAggregate(
                                      AggregateRel.newBuilder()
                                          .setInput(
                                              Rel.newBuilder()
                                                  .setProject(
                                                      ProjectRel.newBuilder()
                                                          .setCommon(
                                                              RelCommon.newBuilder()
                                                                  .setEmit(
                                                                      Emit.newBuilder()
                                                                          .addOutputMapping(2)
                                                                          .addOutputMapping(1)))
                                                          .setInput(
                                                              Rel.newBuilder()
                                                                  .setRead(
                                                                      ReadRel.newBuilder()
                                                                          /* Added common to propagate ReadRel.projection to ProjectRel. */
                                                                          .setCommon(
                                                                              RelCommon.newBuilder()
                                                                                  .setEmit(
                                                                                      Emit
                                                                                          .newBuilder()
                                                                                          .addOutputMapping(
                                                                                              1)
                                                                                          .addOutputMapping(
                                                                                              3)))
                                                                          .setBaseSchema(
                                                                              NamedStruct
                                                                                  .newBuilder()
                                                                                  .addNames(
                                                                                      "username")
                                                                                  .addNames("tweet")
                                                                                  .addNames(
                                                                                      "timestamp")
                                                                                  .addNames("likes")
                                                                                  .setStruct(
                                                                                      Type.Struct
                                                                                          .newBuilder()
                                                                                          .addTypes(
                                                                                              Type
                                                                                                  .newBuilder()
                                                                                                  .setString(
                                                                                                      Type
                                                                                                          .String
                                                                                                          .newBuilder()
                                                                                                          /* Changed from NULLABILITY_NULLABLE to NULLABILITY_REQUIRED based on input properties. */
                                                                                                          .setNullability(
                                                                                                              Type
                                                                                                                  .Nullability
                                                                                                                  .NULLABILITY_REQUIRED)))
                                                                                          .addTypes(
                                                                                              Type
                                                                                                  .newBuilder()
                                                                                                  .setString(
                                                                                                      Type
                                                                                                          .String
                                                                                                          .newBuilder()
                                                                                                          /* Changed from NULLABILITY_NULLABLE to NULLABILITY_REQUIRED based on input properties. */
                                                                                                          .setNullability(
                                                                                                              Type
                                                                                                                  .Nullability
                                                                                                                  .NULLABILITY_REQUIRED)))
                                                                                          .addTypes(
                                                                                              Type
                                                                                                  .newBuilder()
                                                                                                  .setString(
                                                                                                      Type
                                                                                                          .String
                                                                                                          .newBuilder()
                                                                                                          .setNullability(
                                                                                                              Type
                                                                                                                  .Nullability
                                                                                                                  .NULLABILITY_NULLABLE)))
                                                                                          .addTypes(
                                                                                              Type
                                                                                                  .newBuilder()
                                                                                                  .setI64(
                                                                                                      Type
                                                                                                          .I64
                                                                                                          .newBuilder()
                                                                                                          .setNullability(
                                                                                                              Type
                                                                                                                  .Nullability
                                                                                                                  .NULLABILITY_NULLABLE)))
                                                                                          .setNullability(
                                                                                              Type
                                                                                                  .Nullability
                                                                                                  .NULLABILITY_REQUIRED)))
                                                                          .setProjection(
                                                                              MaskExpression
                                                                                  .newBuilder()
                                                                                  .setSelect(
                                                                                      StructSelect
                                                                                          .newBuilder()
                                                                                          .addStructItems(
                                                                                              StructItem
                                                                                                  .newBuilder()
                                                                                                  .setField(
                                                                                                      1))
                                                                                          .addStructItems(
                                                                                              StructItem
                                                                                                  .newBuilder()
                                                                                                  .setField(
                                                                                                      3)))
                                                                                  .setMaintainSingularStruct(
                                                                                      true))
                                                                          /* Changed from local_files to named_table for repeatability of tests. */
                                                                          .setNamedTable(
                                                                              NamedTable
                                                                                  .newBuilder()
                                                                                  .addNames("beam")
                                                                                  .addNames(
                                                                                      "PCOLLECTION"))))
                                                          .addExpressions(
                                                              Expression.newBuilder()
                                                                  .setScalarFunction(
                                                                      ScalarFunction.newBuilder()
                                                                          .setFunctionReference(1)
                                                                          .addArguments(
                                                                              FunctionArgument
                                                                                  .newBuilder()
                                                                                  .setValue(
                                                                                      Expression
                                                                                          .newBuilder()
                                                                                          .setSelection(
                                                                                              FieldReference
                                                                                                  .newBuilder()
                                                                                                  .setDirectReference(
                                                                                                      ReferenceSegment
                                                                                                          .newBuilder()
                                                                                                          .setStructField(
                                                                                                              StructField
                                                                                                                  .newBuilder()
                                                                                                                  .setField(
                                                                                                                      0)))
                                                                                                  .setRootReference(
                                                                                                      RootReference
                                                                                                          .newBuilder()))))
                                                                          .addArguments(
                                                                              FunctionArgument
                                                                                  .newBuilder()
                                                                                  .setValue(
                                                                                      Expression
                                                                                          .newBuilder()
                                                                                          .setLiteral(
                                                                                              Literal
                                                                                                  .newBuilder()
                                                                                                  /* Changed type from i64 to i32 to match function signature. */
                                                                                                  .setI32(
                                                                                                      1))))
                                                                          .addArguments(
                                                                              FunctionArgument
                                                                                  .newBuilder()
                                                                                  .setValue(
                                                                                      Expression
                                                                                          .newBuilder()
                                                                                          .setLiteral(
                                                                                              Literal
                                                                                                  .newBuilder()
                                                                                                  /* Changed type from i64 to i32 to match function signature. */
                                                                                                  .setI32(
                                                                                                      1))))
                                                                          .setOutputType(
                                                                              Type.newBuilder()
                                                                                  .setString(
                                                                                      Type.String
                                                                                          .newBuilder()
                                                                                          /* Changed from NULLABILITY_NULLABLE to NULLABILITY_REQUIRED based on input properties. */
                                                                                          .setNullability(
                                                                                              Type
                                                                                                  .Nullability
                                                                                                  .NULLABILITY_REQUIRED)))))))
                                          /* Added grouping_expressions due to deprecation of Grouping.grouping_expressions. */
                                          .addGroupingExpressions(
                                              Expression.newBuilder()
                                                  .setSelection(
                                                      FieldReference.newBuilder()
                                                          .setDirectReference(
                                                              ReferenceSegment.newBuilder()
                                                                  .setStructField(
                                                                      StructField.newBuilder()
                                                                          .setField(0)))
                                                          .setRootReference(
                                                              RootReference.newBuilder())))
                                          .addGroupings(
                                              Grouping.newBuilder()
                                                  /* Changed from grouping_expressions to expression_references due to deprecation. */
                                                  .addExpressionReferences(0))
                                          .addMeasures(
                                              Measure.newBuilder()
                                                  .setMeasure(
                                                      AggregateFunction.newBuilder()
                                                          .setFunctionReference(2)
                                                          .setOutputType(
                                                              Type.newBuilder()
                                                                  .setI64(
                                                                      Type.I64
                                                                          .newBuilder()
                                                                          /* Changed from NULLABILITY_NULLABLE to NULLABILITY_REQUIRED to match function signature. */
                                                                          .setNullability(
                                                                              Type.Nullability
                                                                                  .NULLABILITY_REQUIRED)))))
                                          .addMeasures(
                                              Measure.newBuilder()
                                                  .setMeasure(
                                                      AggregateFunction.newBuilder()
                                                          .setFunctionReference(3)
                                                          .addArguments(
                                                              FunctionArgument.newBuilder()
                                                                  .setValue(
                                                                      Expression.newBuilder()
                                                                          .setSelection(
                                                                              FieldReference
                                                                                  .newBuilder()
                                                                                  .setDirectReference(
                                                                                      ReferenceSegment
                                                                                          .newBuilder()
                                                                                          .setStructField(
                                                                                              StructField
                                                                                                  .newBuilder()
                                                                                                  .setField(
                                                                                                      1)))
                                                                                  .setRootReference(
                                                                                      RootReference
                                                                                          .newBuilder()))))
                                                          .setOutputType(
                                                              Type.newBuilder()
                                                                  /* Changed type from decimal to i64 to match function signature. */
                                                                  .setI64(
                                                                      Type.I64
                                                                          .newBuilder()
                                                                          .setNullability(
                                                                              Type.Nullability
                                                                                  .NULLABILITY_NULLABLE)))))))
                          /* Changed from 0 to first_char because the alias was not propagated. */
                          .addNames("first_char")
                          .addNames("tweet_count")
                          .addNames("tweet_likes")))
          .build();
  private static final Schema INPUT_SCHEMA =
      Schema.builder()
          .addStringField("username")
          .addStringField("tweet")
          .addNullableStringField("timestamp")
          .addNullableInt64Field("likes")
          .build();
  private static final Schema OUTPUT_SCHEMA =
      Schema.builder()
          .addStringField("first_char")
          .addNullableInt64Field("tweet_count")
          .addNullableInt64Field("tweet_likes")
          .build();
  // Source:
  // gs://cloud-samples-data-us-central1/bigquery/federated-formats-reference-file-schema/*-twitter.parquet
  private static final List<Row> INPUT_ROWS =
      TestUtils.RowsBuilder.of(INPUT_SCHEMA)
          .addRows("user1", "Good morning", "20151023", 10l)
          .addRows("user2", "Have fun today", "20130517", 35l)
          .addRows("user3", "Congratulations Rob", "20170128", 300l)
          .addRows("user4", "Happy birthday", "20200809", 50l)
          .addRows("user5", "Good luck on your exams", "20161201", 146l)
          .addRows("user6", "You can totally do this", "20220623", 756l)
          .addRows("user7", "No pressure, no diamonds", "20100329", 86l)
          .addRows("user8", "Stay foolish to stay sane", "20161129", null)
          .addRows("user9", "When nothing goes right, go left", "20080707", null)
          .addRows("user10", "Try Again. Fail again. Fail better", "20110822", null)
          .addRows("user11", "Impossible is for the unwilling", "20120228", null)
          .addRows("user12", "Once you choose hope, anything’s possible", "20180415", null)
          .addRows("user13", "I can and I will", "20210725", null)
          .addRows("user14", "Take the risk or lose the chance", "20091005", null)
          .addRows("user15", "Good things happen to those who hustle", null, null)
          .addRows("user16", "Solitary trees, if they grow at all, grow strong", null, null)
          .addRows(
              "user17", "Go forth on your path, as it exists only through your walking", null, null)
          .addRows("user18", "He who is brave is free", null, null)
          .addRows("user19", "Prove them wrong", null, null)
          .addRows("user20", "Don’t tell people your plans. Show them your results", null, null)
          .addRows(
              "user21", "We can do anything we want to if we stick to it long enough", null, null)
          .getRows();
  // Result
  private static final List<Row> OUTPUT_ROWS =
      TestUtils.RowsBuilder.of(OUTPUT_SCHEMA)
          .addRows("C", 1l, 300l)
          .addRows("D", 1l, null)
          .addRows("G", 4l, 156l)
          .addRows("H", 3l, 85l)
          .addRows("I", 2l, null)
          .addRows("N", 1l, 86l)
          .addRows("O", 1l, null)
          .addRows("P", 1l, null)
          .addRows("S", 2l, null)
          .addRows("T", 2l, null)
          .addRows("W", 2l, null)
          .addRows("Y", 1l, 756l)
          .getRows();

  @Test
  public void testGroupByFirstCharacterOfTweetWithCountStarAndLikesSum() {
    PAssert.that(
            pipeline
                .apply("boundedInput", Create.of(INPUT_ROWS).withRowSchema(INPUT_SCHEMA))
                .apply("substraitPlan", SqlTransform.fromSubstrait(PLAN)))
        .containsInAnyOrder(OUTPUT_ROWS);

    pipeline.run();
  }
}
