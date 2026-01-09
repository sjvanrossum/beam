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

import com.google.protobuf.InvalidProtocolBufferException;
import io.substrait.proto.Plan;
import java.text.ParseException;
import java.util.List;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.Row;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

public class BeamSqlSubstraitTest {
  @Rule public final TestPipeline pipeline = TestPipeline.create();

  static Plan planProto;
  static Schema schema;
  static List<Row> rows;
  PCollection<Row> input;

  @BeforeClass
  public static void prepareClass() throws InvalidProtocolBufferException, ParseException {
    /*
      extension_uris {
        extension_uri_anchor: 1
        uri: "https://github.com/substrait-io/substrait/blob/main/extensions/functions_aggregate_generic.yaml"
      }
      extension_uris {
        extension_uri_anchor: 2
        uri: "https://github.com/substrait-io/substrait/blob/main/extensions/functions_arithmetic.yaml"
      }
      extension_uris {
        extension_uri_anchor: 3
        uri: "https://github.com/substrait-io/substrait/blob/main/extensions/functions_string.yaml"
      }
      extensions {
        extension_function {
          extension_uri_reference: 3
          function_anchor: 1
          name: "substring:str_i32_i32"
        }
      }
      extensions {
        extension_function {
          extension_uri_reference: 1
          function_anchor: 2
          name: "count:"
        }
      }
      extensions {
        extension_function {
          extension_uri_reference: 2
          function_anchor: 3
          name: "sum:i64"
        }
      }
      relations {
        root {
          input {
            aggregate {
              input {
                project {
                  common {
                    emit {
                      output_mapping: 4
                      output_mapping: 3
                    }
                  }
                  input {
                    read {
                      base_schema {
                        names: "username"
                        names: "tweet"
                        names: "timestamp"
                        names: "likes"
                        struct {
                          types {
                            string {
                              nullability: NULLABILITY_REQUIRED
                            }
                          }
                          types {
                            string {
                              nullability: NULLABILITY_REQUIRED
                            }
                          }
                          types {
                            string {
                              nullability: NULLABILITY_NULLABLE
                            }
                          }
                          types {
                            i64 {
                              nullability: NULLABILITY_NULLABLE
                            }
                          }
                          nullability: NULLABILITY_REQUIRED
                        }
                      }
                      named_table {
                        names: "beam"
                        names: "PCOLLECTION"
                      }
                    }
                  }
                  expressions {
                    scalar_function {
                      function_reference: 1
                      output_type {
                        string {
                          nullability: NULLABILITY_REQUIRED
                        }
                      }
                      arguments {
                        value {
                          selection {
                            direct_reference {
                              struct_field {
                                field: 1
                              }
                            }
                            root_reference {
                            }
                          }
                        }
                      }
                      arguments {
                        value {
                          literal {
                            i32: 1
                          }
                        }
                      }
                      arguments {
                        value {
                          literal {
                            i32: 1
                          }
                        }
                      }
                    }
                  }
                }
              }
              groupings {
                grouping_expressions {
                  selection {
                    direct_reference {
                      struct_field {
                      }
                    }
                    root_reference {
                    }
                  }
                }
              }
              measures {
                measure {
                  function_reference: 2
                  output_type {
                    i64 {
                      nullability: NULLABILITY_NULLABLE
                    }
                  }
                }
              }
              measures {
                measure {
                  function_reference: 3
                  output_type {
                    i64 {
                      nullability: NULLABILITY_NULLABLE
                    }
                  }
                  arguments {
                    value {
                      selection {
                        direct_reference {
                          struct_field {
                            field: 1
                          }
                        }
                        root_reference {
                        }
                      }
                    }
                  }
                }
              }
            }
          }
          names: "first_char"
          names: "tweet_count"
          names: "tweet_likes"
        }
      }
    */
    planProto =
        Plan.parseFrom(
            new byte[] {
              10, 99, 8, 1, 18, 95, 104, 116, 116, 112, 115, 58, 47, 47, 103, 105, 116, 104, 117,
              98, 46, 99, 111, 109, 47, 115, 117, 98, 115, 116, 114, 97, 105, 116, 45, 105, 111, 47,
              115, 117, 98, 115, 116, 114, 97, 105, 116, 47, 98, 108, 111, 98, 47, 109, 97, 105,
              110, 47, 101, 120, 116, 101, 110, 115, 105, 111, 110, 115, 47, 102, 117, 110, 99, 116,
              105, 111, 110, 115, 95, 97, 103, 103, 114, 101, 103, 97, 116, 101, 95, 103, 101, 110,
              101, 114, 105, 99, 46, 121, 97, 109, 108, 10, 92, 8, 2, 18, 88, 104, 116, 116, 112,
              115, 58, 47, 47, 103, 105, 116, 104, 117, 98, 46, 99, 111, 109, 47, 115, 117, 98, 115,
              116, 114, 97, 105, 116, 45, 105, 111, 47, 115, 117, 98, 115, 116, 114, 97, 105, 116,
              47, 98, 108, 111, 98, 47, 109, 97, 105, 110, 47, 101, 120, 116, 101, 110, 115, 105,
              111, 110, 115, 47, 102, 117, 110, 99, 116, 105, 111, 110, 115, 95, 97, 114, 105, 116,
              104, 109, 101, 116, 105, 99, 46, 121, 97, 109, 108, 10, 88, 8, 3, 18, 84, 104, 116,
              116, 112, 115, 58, 47, 47, 103, 105, 116, 104, 117, 98, 46, 99, 111, 109, 47, 115,
              117, 98, 115, 116, 114, 97, 105, 116, 45, 105, 111, 47, 115, 117, 98, 115, 116, 114,
              97, 105, 116, 47, 98, 108, 111, 98, 47, 109, 97, 105, 110, 47, 101, 120, 116, 101,
              110, 115, 105, 111, 110, 115, 47, 102, 117, 110, 99, 116, 105, 111, 110, 115, 95, 115,
              116, 114, 105, 110, 103, 46, 121, 97, 109, 108, 18, 29, 26, 27, 8, 3, 16, 1, 26, 21,
              115, 117, 98, 115, 116, 114, 105, 110, 103, 58, 115, 116, 114, 95, 105, 51, 50, 95,
              105, 51, 50, 18, 14, 26, 12, 8, 1, 16, 2, 26, 6, 99, 111, 117, 110, 116, 58, 18, 15,
              26, 13, 8, 2, 16, 3, 26, 7, 115, 117, 109, 58, 105, 54, 52, 26, -13, 1, 18, -16, 1,
              10, -57, 1, 34, -60, 1, 18, -113, 1, 58, -116, 1, 10, 6, 18, 4, 10, 2, 4, 3, 18, 88,
              10, 86, 18, 63, 10, 8, 117, 115, 101, 114, 110, 97, 109, 101, 10, 5, 116, 119, 101,
              101, 116, 10, 9, 116, 105, 109, 101, 115, 116, 97, 109, 112, 10, 5, 108, 105, 107,
              101, 115, 18, 26, 10, 4, 98, 2, 16, 2, 10, 4, 98, 2, 16, 2, 10, 4, 98, 2, 16, 1, 10,
              4, 58, 2, 16, 1, 24, 2, 58, 19, 10, 4, 98, 101, 97, 109, 10, 11, 80, 67, 79, 76, 76,
              69, 67, 84, 73, 79, 78, 26, 40, 26, 38, 8, 1, 26, 4, 98, 2, 16, 2, 34, 12, 26, 10, 18,
              8, 10, 4, 18, 2, 8, 1, 34, 0, 34, 6, 26, 4, 10, 2, 40, 1, 34, 6, 26, 4, 10, 2, 40, 1,
              26, 10, 10, 8, 18, 6, 10, 2, 18, 0, 34, 0, 34, 10, 10, 8, 8, 2, 42, 4, 58, 2, 16, 1,
              34, 24, 10, 22, 8, 3, 42, 4, 58, 2, 16, 1, 58, 12, 26, 10, 18, 8, 10, 4, 18, 2, 8, 1,
              34, 0, 18, 10, 102, 105, 114, 115, 116, 95, 99, 104, 97, 114, 18, 11, 116, 119, 101,
              101, 116, 95, 99, 111, 117, 110, 116, 18, 11, 116, 119, 101, 101, 116, 95, 108, 105,
              107, 101, 115
            });
    schema =
        Schema.builder()
            .addStringField("username")
            .addStringField("tweet")
            .addNullableStringField("timestamp")
            .addNullableInt64Field("likes")
            .build();
    rows =
        TestUtils.RowsBuilder.of(schema)
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
                "user17",
                "Go forth on your path, as it exists only through your walking",
                null,
                null)
            .addRows("user18", "He who is brave is free", null, null)
            .addRows("user19", "Prove them wrong", null, null)
            .addRows("user20", "Don’t tell people your plans. Show them your results", null, null)
            .addRows(
                "user21", "We can do anything we want to if we stick to it long enough", null, null)
            .getRows();
  }

  @Before
  public void preparePCollections() {
    input = pipeline.apply("boundedInput", Create.of(rows).withRowSchema(schema));
  }

  @Test
  public void testGroupByFirstCharacterOfTweetWithCountStarAndLikesSum() {
    PCollection<Row> result = input.apply("substraitPlan", SqlTransform.fromSubstrait(planProto));

    Schema resultSchema =
        Schema.builder()
            .addStringField("first_char")
            .addNullableInt64Field("tweet_count")
            .addNullableInt64Field("tweet_likes")
            .build();
    PAssert.that(result)
        .containsInAnyOrder(
            Row.withSchema(resultSchema).addValues("C", 1l, 300l).build(),
            Row.withSchema(resultSchema).addValues("D", 1l, null).build(),
            Row.withSchema(resultSchema).addValues("G", 4l, 156l).build(),
            Row.withSchema(resultSchema).addValues("H", 3l, 85l).build(),
            Row.withSchema(resultSchema).addValues("I", 2l, null).build(),
            Row.withSchema(resultSchema).addValues("N", 1l, 86l).build(),
            Row.withSchema(resultSchema).addValues("O", 1l, null).build(),
            Row.withSchema(resultSchema).addValues("P", 1l, null).build(),
            Row.withSchema(resultSchema).addValues("S", 2l, null).build(),
            Row.withSchema(resultSchema).addValues("T", 2l, null).build(),
            Row.withSchema(resultSchema).addValues("W", 2l, null).build(),
            Row.withSchema(resultSchema).addValues("Y", 1l, 756l).build());

    pipeline.run();
  }
}
