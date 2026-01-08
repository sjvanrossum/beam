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
package org.apache.beam.sdk.extensions.sql.impl.planner;

import io.substrait.proto.Expression;
import io.substrait.proto.Plan;
import io.substrait.proto.PlanRel;
import io.substrait.proto.ReadRel;
import io.substrait.proto.Rel;
import io.substrait.proto.RelRoot;
import org.apache.beam.sdk.extensions.sql.impl.BeamSqlEnv;
import org.apache.beam.sdk.extensions.sql.impl.CalciteQueryPlanner;
import org.apache.beam.sdk.extensions.sql.impl.rel.BaseRelTest;
import org.apache.beam.sdk.extensions.sql.impl.rel.BeamRelNode;
import org.apache.beam.sdk.extensions.sql.impl.rel.BeamSqlRelUtils;
import org.apache.beam.sdk.extensions.sql.meta.catalog.InMemoryCatalogManager;
import org.apache.beam.sdk.extensions.sql.meta.provider.test.TestBoundedTable;
import org.apache.beam.sdk.extensions.sql.meta.provider.text.TextTableProvider;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.vendor.calcite.v1_41_0.org.apache.calcite.plan.RelOptRule;
import org.apache.beam.vendor.calcite.v1_41_0.org.apache.calcite.tools.RuleSets;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableList;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests the behavior of {@code CalciteQueryPlanner}. Note that this is not for the JDBC path. It
 * will be the behavior of SQLTransform path.
 */
public class CalciteQueryPlannerTest extends BaseRelTest {
  @Rule public final TestPipeline pipeline = TestPipeline.create();

  private static final Plan PLAN;

  static {
    try {
      // TODO: Get plan converter to use the default schema
      // select * from beam.medium_table
      PLAN =
          Plan.parseFrom(
              new byte[] {
                26, -128, 1, 18, 126, 10, 94, 10, 92, 18, 52, 10, 13, 117, 110, 98, 111, 117, 110,
                100, 101, 100, 95, 107, 101, 121, 10, 9, 108, 97, 114, 103, 101, 95, 107, 101, 121,
                10, 2, 105, 100, 18, 20, 10, 4, 42, 2, 16, 2, 10, 4, 42, 2, 16, 2, 10, 4, 42, 2, 16,
                2, 24, 2, 34, 14, 10, 10, 10, 0, 10, 2, 8, 1, 10, 2, 8, 2, 16, 1, 58, 20, 10, 4, 98,
                101, 97, 109, 10, 12, 109, 101, 100, 105, 117, 109, 95, 116, 97, 98, 108, 101, 18,
                13, 117, 110, 98, 111, 117, 110, 100, 101, 100, 95, 107, 101, 121, 18, 9, 108, 97,
                114, 103, 101, 95, 107, 101, 121, 18, 2, 105, 100, 50, 10, 16, 53, 42, 6, 68, 117,
                99, 107, 68, 66
              });
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  @Before
  public void prepare() {
    registerTable(
        "medium_table",
        TestBoundedTable.of(
                Schema.FieldType.INT32, "unbounded_key",
                Schema.FieldType.INT32, "large_key",
                Schema.FieldType.INT32, "id")
            .addRows(1, 1, 1, 1, 1, 2, 1, 1, 3, 1, 1, 4, 1, 1, 5));
  }

  @Test
  public void testclusterCostHandlerUsesBeamCost() {
    String sql = "select * from medium_table";
    BeamRelNode root = env.parseQuery(sql);
    Assert.assertTrue(
        root.getCluster().getPlanner().getCost(root, root.getCluster().getMetadataQuery())
            instanceof BeamCostModel);
  }

  @Test
  public void testNonCumulativeCostMetadataHandler() {
    String sql = "select * from medium_table";
    BeamRelNode root = env.parseQuery(sql);
    Assert.assertTrue(
        root.getCluster().getMetadataQuery().getNonCumulativeCost(root) instanceof BeamCostModel);
    Assert.assertFalse(
        root.getCluster().getMetadataQuery().getNonCumulativeCost(root).isInfinite());
  }

  @Test
  public void testCumulativeCostMetaDataHandler() {
    // This handler is not our handler. It tests if the cumulative handler of Calcite works as
    // expected.
    String sql = "select * from medium_table";
    BeamRelNode root = env.parseQuery(sql);
    Assert.assertTrue(
        root.getCluster().getMetadataQuery().getCumulativeCost(root) instanceof BeamCostModel);
    Assert.assertFalse(root.getCluster().getMetadataQuery().getCumulativeCost(root).isInfinite());
  }

  private Plan createValuesPlan() {
    // Row 1: (1, "foo")
    Expression.Literal.Struct row1 =
        Expression.Literal.Struct.newBuilder()
            .addFields(Expression.Literal.newBuilder().setI32(1))
            .addFields(Expression.Literal.newBuilder().setString("foo"))
            .build();

    // Row 2: (2, "bar")
    Expression.Literal.Struct row2 =
        Expression.Literal.Struct.newBuilder()
            .addFields(Expression.Literal.newBuilder().setI32(2))
            .addFields(Expression.Literal.newBuilder().setString("bar"))
            .build();

    // Create the VirtualTable relation
    ReadRel readRel =
        ReadRel.newBuilder()
            .setVirtualTable(
                ReadRel.VirtualTable.newBuilder().addValues(row1).addValues(row2).build())
            .build();

    // Wrap in RelRoot to provide column names ("id", "name")
    RelRoot root =
        RelRoot.newBuilder()
            .setInput(Rel.newBuilder().setRead(readRel).build())
            .addNames("id")
            .addNames("name")
            .build();

    // Build the final Plan
    return Plan.newBuilder().addRelations(PlanRel.newBuilder().setRoot(root).build()).build();
  }

  // TODO: this instantiation should live somewhere else
  private static BeamSqlEnv getBeamSqlEnv() {
    InMemoryCatalogManager catalogManager = new InMemoryCatalogManager();
    catalogManager.registerTableProvider(new TextTableProvider());
    BeamSqlEnv.BeamSqlEnvBuilder sqlEnvBuilder = BeamSqlEnv.builder(catalogManager);
    sqlEnvBuilder.setQueryPlannerClassName(CalciteQueryPlanner.class.getCanonicalName());
    PipelineOptions options = PipelineOptionsFactory.create();
    sqlEnvBuilder.setPipelineOptions(options);

    // All the Beam rules and also the SparkConnect rules
    // ... this seems to only work right when they are put into a single RuleSet
    // for spark we needed another set of rules for converting custom rels
    sqlEnvBuilder.setRuleSets(
        ImmutableList.of(
            RuleSets.ofList(
                ImmutableList.<RelOptRule>builder().addAll(BeamRuleSets.getAllRules()).build())));
    BeamSqlEnv sqlEnv = sqlEnvBuilder.build();
    return sqlEnv;
  }

  @Test
  public void testNamedTableExecution() throws Exception {
    // Convert the Substrait Plan to a BeamRelNode
    BeamRelNode beamRelNode = env.convertToBeamRel(PLAN);

    // Convert to PCollection and run
    PCollection<Row> output = BeamSqlRelUtils.toPCollection(pipeline, beamRelNode);

    Schema schema =
        Schema.builder()
            .addInt32Field("unbounded_key")
            .addInt32Field("large_key")
            .addInt32Field("id")
            .build();

    PAssert.that(output)
        .containsInAnyOrder(
            Row.withSchema(schema).addValues(1).addValues(1).addValues(1).build(),
            Row.withSchema(schema).addValues(1).addValues(1).addValues(2).build(),
            Row.withSchema(schema).addValues(1).addValues(1).addValues(3).build(),
            Row.withSchema(schema).addValues(1).addValues(1).addValues(4).build(),
            Row.withSchema(schema).addValues(1).addValues(1).addValues(5).build());

    pipeline.run().waitUntilFinish();
  }

  @Test
  public void testclusterCostHandlerUsesBeamCostWithSubstrait() {
    BeamRelNode root = env.convertToBeamRel(PLAN);
    Assert.assertTrue(
        root.getCluster().getPlanner().getCost(root, root.getCluster().getMetadataQuery())
            instanceof BeamCostModel);
  }

  @Test
  public void testNonCumulativeCostMetadataHandlerWithSubstrait() {
    BeamRelNode root = env.convertToBeamRel(PLAN);
    Assert.assertTrue(
        root.getCluster().getMetadataQuery().getNonCumulativeCost(root) instanceof BeamCostModel);
    Assert.assertFalse(
        root.getCluster().getMetadataQuery().getNonCumulativeCost(root).isInfinite());
  }

  @Test
  public void testCumulativeCostMetaDataHandlerWithSubstrait() {
    // This handler is not our handler. It tests if the cumulative handler of Calcite works as
    // expected.
    BeamRelNode root = env.convertToBeamRel(PLAN);
    Assert.assertTrue(
        root.getCluster().getMetadataQuery().getCumulativeCost(root) instanceof BeamCostModel);
    Assert.assertFalse(root.getCluster().getMetadataQuery().getCumulativeCost(root).isInfinite());
  }
}
