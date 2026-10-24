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
package org.apache.beam.sdk.util;

import static org.apache.beam.sdk.schemas.Schema.TypeName.BOOLEAN;
import static org.apache.beam.sdk.schemas.Schema.TypeName.BYTE;
import static org.apache.beam.sdk.schemas.Schema.TypeName.DATETIME;
import static org.apache.beam.sdk.schemas.Schema.TypeName.DECIMAL;
import static org.apache.beam.sdk.schemas.Schema.TypeName.DOUBLE;
import static org.apache.beam.sdk.schemas.Schema.TypeName.FLOAT;
import static org.apache.beam.sdk.schemas.Schema.TypeName.INT16;
import static org.apache.beam.sdk.schemas.Schema.TypeName.INT32;
import static org.apache.beam.sdk.schemas.Schema.TypeName.INT64;
import static org.apache.beam.sdk.schemas.Schema.TypeName.MAP;
import static org.apache.beam.sdk.schemas.Schema.TypeName.STRING;
import static org.apache.beam.sdk.util.RowJsonValueExtractors.extractBoolean;
import static org.apache.beam.sdk.util.RowJsonValueExtractors.extractByte;
import static org.apache.beam.sdk.util.RowJsonValueExtractors.extractDate;
import static org.apache.beam.sdk.util.RowJsonValueExtractors.extractDatetime;
import static org.apache.beam.sdk.util.RowJsonValueExtractors.extractDecimal;
import static org.apache.beam.sdk.util.RowJsonValueExtractors.extractDouble;
import static org.apache.beam.sdk.util.RowJsonValueExtractors.extractFloat;
import static org.apache.beam.sdk.util.RowJsonValueExtractors.extractInt;
import static org.apache.beam.sdk.util.RowJsonValueExtractors.extractLocalDatetime;
import static org.apache.beam.sdk.util.RowJsonValueExtractors.extractLong;
import static org.apache.beam.sdk.util.RowJsonValueExtractors.extractShort;
import static org.apache.beam.sdk.util.RowJsonValueExtractors.extractString;
import static org.apache.beam.sdk.util.RowJsonValueExtractors.extractTime;
import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.Schema.Field;
import org.apache.beam.sdk.schemas.Schema.FieldType;
import org.apache.beam.sdk.schemas.Schema.TypeName;
import org.apache.beam.sdk.schemas.logicaltypes.SqlTypes;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableList;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableSet;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joda.time.ReadableInstant;

/**
 * Jackson serializer and deserializer for {@link Row Rows}.
 *
 * <p>Supports converting between JSON primitive types and:
 *
 * <ul>
 *   <li>{@link Schema.TypeName#BYTE}
 *   <li>{@link Schema.TypeName#INT16}
 *   <li>{@link Schema.TypeName#INT32}
 *   <li>{@link Schema.TypeName#INT64}
 *   <li>{@link Schema.TypeName#FLOAT}
 *   <li>{@link Schema.TypeName#DOUBLE}
 *   <li>{@link Schema.TypeName#BOOLEAN}
 *   <li>{@link Schema.TypeName#STRING}
 *   <li>{@link Schema.TypeName#DECIMAL}
 *   <li>{@link Schema.TypeName#DATETIME}
 * </ul>
 */
@SuppressWarnings({
  "nullness" // TODO(https://github.com/apache/beam/issues/20497)
})
public class RowJson {
  private static final ImmutableSet<TypeName> SUPPORTED_TYPES =
      ImmutableSet.of(
          BYTE, INT16, INT32, INT64, FLOAT, DOUBLE, BOOLEAN, STRING, DECIMAL, DATETIME, MAP);
  private static final ImmutableSet<String> KNOWN_LOGICAL_TYPE_IDENTIFIERS =
      ImmutableSet.of(
          SqlTypes.DATE.getIdentifier(),
          SqlTypes.TIME.getIdentifier(),
          SqlTypes.DATETIME.getIdentifier());

  private enum JsonFieldKind {
    BYTE,
    INT16,
    INT32,
    INT64,
    FLOAT,
    DOUBLE,
    BOOLEAN,
    STRING,
    DECIMAL,
    DATETIME,
    SQL_DATE,
    SQL_TIME,
    SQL_DATETIME,
    ROW,
    ARRAY,
    MAP,
    LOGICAL_TYPE
  }

  private static final class SchemaPlan implements Serializable {
    final Schema schema;
    final int fieldCount;
    final long allPresentMask;
    final FieldPlan[] fields;

    SchemaPlan(Schema schema) {
      this.schema = schema;
      this.fieldCount = schema.getFieldCount();
      this.allPresentMask = (fieldCount == 64) ? -1L : ((1L << fieldCount) - 1L);
      this.fields = new FieldPlan[fieldCount];
      for (int i = 0; i < fieldCount; i++) {
        Field field = schema.getField(i);
        this.fields[i] = new FieldPlan(field.getName(), field.getType());
      }
    }
  }

  private static final class FieldPlan implements Serializable {
    final String name;
    final String internedName;
    final SerializedString serializedName;
    final TypeName typeName;
    final JsonFieldKind kind;
    final boolean nullable;
    final @Nullable SchemaPlan rowPlan;
    final @Nullable FieldPlan elementPlan;
    final @Nullable FieldPlan mapValuePlan;
    final Schema.@Nullable LogicalType<Object, Object> logicalType;
    final @Nullable FieldPlan basePlan;

    @SuppressWarnings("unchecked")
    FieldPlan(String name, FieldType fieldType) {
      this.name = name;
      this.internedName = name.intern();
      this.serializedName = new SerializedString(name);
      this.typeName = fieldType.getTypeName();
      this.nullable = fieldType.getNullable();

      SchemaPlan resolvedRowPlan = null;
      FieldPlan resolvedElementPlan = null;
      FieldPlan resolvedMapValuePlan = null;
      Schema.LogicalType<Object, Object> resolvedLogicalType = null;
      FieldPlan resolvedBasePlan = null;
      JsonFieldKind resolvedKind;

      switch (this.typeName) {
        case BYTE:
          resolvedKind = JsonFieldKind.BYTE;
          break;
        case INT16:
          resolvedKind = JsonFieldKind.INT16;
          break;
        case INT32:
          resolvedKind = JsonFieldKind.INT32;
          break;
        case INT64:
          resolvedKind = JsonFieldKind.INT64;
          break;
        case FLOAT:
          resolvedKind = JsonFieldKind.FLOAT;
          break;
        case DOUBLE:
          resolvedKind = JsonFieldKind.DOUBLE;
          break;
        case BOOLEAN:
          resolvedKind = JsonFieldKind.BOOLEAN;
          break;
        case STRING:
          resolvedKind = JsonFieldKind.STRING;
          break;
        case DECIMAL:
          resolvedKind = JsonFieldKind.DECIMAL;
          break;
        case DATETIME:
          resolvedKind = JsonFieldKind.DATETIME;
          break;
        case ROW:
          resolvedKind = JsonFieldKind.ROW;
          resolvedRowPlan = new SchemaPlan(fieldType.getRowSchema());
          break;
        case ARRAY:
        case ITERABLE:
          resolvedKind = JsonFieldKind.ARRAY;
          resolvedElementPlan = new FieldPlan(name + "[]", fieldType.getCollectionElementType());
          break;
        case MAP:
          resolvedKind = JsonFieldKind.MAP;
          resolvedMapValuePlan = new FieldPlan(name, fieldType.getMapValueType());
          break;
        case LOGICAL_TYPE:
          resolvedLogicalType = (Schema.LogicalType<Object, Object>) fieldType.getLogicalType();
          String identifier = resolvedLogicalType.getIdentifier();
          if (SqlTypes.DATE.getIdentifier().equals(identifier)) {
            resolvedKind = JsonFieldKind.SQL_DATE;
          } else if (SqlTypes.TIME.getIdentifier().equals(identifier)) {
            resolvedKind = JsonFieldKind.SQL_TIME;
          } else if (SqlTypes.DATETIME.getIdentifier().equals(identifier)) {
            resolvedKind = JsonFieldKind.SQL_DATETIME;
          } else {
            resolvedKind = JsonFieldKind.LOGICAL_TYPE;
            resolvedBasePlan = new FieldPlan(name, resolvedLogicalType.getBaseType());
          }
          break;
        default:
          throw new UnsupportedRowJsonException(
              "Field type '" + this.typeName + "' is not supported when converting JSON to Row.");
      }

      this.kind = resolvedKind;
      this.rowPlan = resolvedRowPlan;
      this.elementPlan = resolvedElementPlan;
      this.mapValuePlan = resolvedMapValuePlan;
      this.logicalType = resolvedLogicalType;
      this.basePlan = resolvedBasePlan;
    }
  }

  /**
   * Throws {@link UnsupportedRowJsonException} if {@code schema} contains an unsupported field
   * type.
   */
  public static void verifySchemaSupported(Schema schema) {
    ImmutableList<UnsupportedField> unsupportedFields = findUnsupportedFields(schema);
    if (!unsupportedFields.isEmpty()) {
      throw new UnsupportedRowJsonException(
          String.format(
              "Field type%s %s not supported when converting between JSON and Rows. Supported types are: %s",
              unsupportedFields.size() > 1 ? "s" : "",
              unsupportedFields.toString(),
              SUPPORTED_TYPES.toString()));
    }
  }

  private static class UnsupportedField {
    final String descriptor;
    final TypeName typeName;

    UnsupportedField(String descriptor, TypeName typeName) {
      this.descriptor = descriptor;
      this.typeName = typeName;
    }

    @Override
    public String toString() {
      return this.descriptor + "=" + this.typeName;
    }
  }

  private static ImmutableList<UnsupportedField> findUnsupportedFields(Schema schema) {
    return schema.getFields().stream()
        .flatMap((field) -> findUnsupportedFields(field).stream())
        .collect(toImmutableList());
  }

  private static ImmutableList<UnsupportedField> findUnsupportedFields(Field field) {
    return findUnsupportedFields(field.getType(), field.getName());
  }

  private static ImmutableList<UnsupportedField> findUnsupportedFields(
      FieldType fieldType, String fieldName) {
    TypeName fieldTypeName = fieldType.getTypeName();

    if (fieldTypeName.isCompositeType()) {
      return fieldType.getRowSchema().getFields().stream()
          .flatMap(
              (field) ->
                  findUnsupportedFields(field.getType(), fieldName + "." + field.getName())
                      .stream())
          .collect(toImmutableList());
    }

    if (fieldTypeName.isCollectionType()) {
      return findUnsupportedFields(fieldType.getCollectionElementType(), fieldName + "[]");
    }

    if (fieldTypeName.isMapType()) {
      if (!STRING.equals(fieldType.getMapKeyType().getTypeName())) {
        return ImmutableList.of(
            new UnsupportedField(fieldName + ".key", fieldType.getMapKeyType().getTypeName()));
      }
      return findUnsupportedFields(fieldType.getMapValueType(), fieldName + "{}");
    }

    if (fieldTypeName.isLogicalType()) {
      if (KNOWN_LOGICAL_TYPE_IDENTIFIERS.contains(fieldType.getLogicalType().getIdentifier())) {
        return ImmutableList.of();
      } else {
        return findUnsupportedFields(fieldType.getLogicalType().getBaseType(), fieldName);
      }
    }

    if (!SUPPORTED_TYPES.contains(fieldTypeName)) {
      return ImmutableList.of(new UnsupportedField(fieldName, fieldTypeName));
    }

    return ImmutableList.of();
  }

  /** Jackson deserializer for parsing JSON into {@link Row Rows}. */
  public static class RowJsonDeserializer extends StdDeserializer<Row> {

    /**
     * An enumeration type for specifying how {@link RowJsonDeserializer} should expect null values
     * to be represented.
     *
     * <p>For example, when parsing JSON for the Schema {@code (str: REQUIRED STRING, int: NULLABLE
     * INT64)}:
     *
     * <ul>
     *   <li>If configured with {@code REQUIRE_NULL}, {@code {"str": "foo", "int": null}} would be
     *       accepted.
     *   <li>If configured with {@code REQUIRE_MISSING}, {@code {"str": "bar"}} would be accepted,
     *       and would yield a {@link Row} with {@code null} for the {@code int} field.
     *   <li>If configured with {@code ALLOW_MISSING_OR_NULL}, either JSON string would be accepted.
     * </ul>
     */
    public enum NullBehavior {
      /**
       * Specifies that a null value may be represented as either a missing field or a null value in
       * the input JSON.
       */
      ACCEPT_MISSING_OR_NULL,
      /**
       * Specifies that a null value must be represented with a null value in JSON. If the field is
       * missing an {@link UnsupportedRowJsonException} will be thrown.
       */
      REQUIRE_NULL,
      /**
       * Specifies that a null value must be represented with a missing field in JSON. If the field
       * has a null value an {@link UnsupportedRowJsonException} will be thrown.
       */
      REQUIRE_MISSING,
    }

    private final FieldPlan rootFieldPlan;
    private NullBehavior nullBehavior = NullBehavior.ACCEPT_MISSING_OR_NULL;

    /** Creates a deserializer for a {@link Row} {@link Schema}. */
    public static RowJsonDeserializer forSchema(Schema schema) {
      verifySchemaSupported(schema);
      return new RowJsonDeserializer(schema);
    }

    private RowJsonDeserializer(Schema schema) {
      super(Row.class);
      this.rootFieldPlan = new FieldPlan("root", FieldType.row(schema));
    }

    /**
     * Sets the behaviour of the deserializer when retrieving null values in the input JSON. See
     * {@link NullBehavior} for a description of the options. Default value is {@code
     * ACCEPT_MISSING_OR_NULL}.
     */
    public RowJsonDeserializer withNullBehavior(NullBehavior behavior) {
      this.nullBehavior = behavior;
      return this;
    }

    @Override
    public Row deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
        throws IOException {
      if (jsonParser.currentToken() == null) {
        jsonParser.nextToken();
      }
      // Parse and convert the root object to Row as if it's a nested field with name 'root'
      return (Row) extractJsonValue(rootFieldPlan, jsonParser);
    }

    private @Nullable Object extractJsonValue(FieldPlan fieldPlan, JsonParser jsonParser)
        throws IOException {
      JsonToken token = jsonParser.currentToken();
      if (fieldPlan.nullable) {
        if (token == null) {
          switch (this.nullBehavior) {
            case ACCEPT_MISSING_OR_NULL:
            case REQUIRE_MISSING:
              return null;
            case REQUIRE_NULL:
              throw new UnsupportedRowJsonException(
                  "Field '" + fieldPlan.name + "' is not present in the JSON object.");
          }
        }

        if (token == JsonToken.VALUE_NULL) {
          switch (this.nullBehavior) {
            case ACCEPT_MISSING_OR_NULL:
            case REQUIRE_NULL:
              return null;
            case REQUIRE_MISSING:
              throw new UnsupportedRowJsonException(
                  "Field '" + fieldPlan.name + "' has a null value in the JSON object.");
          }
        }
      } else {
        // field is not nullable
        if (token == null) {
          throw new UnsupportedRowJsonException(
              "Non-nullable field '" + fieldPlan.name + "' is not present in the JSON object.");
        } else if (token == JsonToken.VALUE_NULL) {
          throw new UnsupportedRowJsonException(
              "Non-nullable field '" + fieldPlan.name + "' has value null in the JSON object.");
        }
      }

      switch (fieldPlan.kind) {
        case ROW:
          return jsonObjectToRow(fieldPlan.name, fieldPlan.rowPlan, jsonParser);
        case ARRAY:
          return jsonArrayToList(fieldPlan, jsonParser);
        case MAP:
          return jsonObjectToMap(fieldPlan, jsonParser);
        case LOGICAL_TYPE:
          return fieldPlan.logicalType.toInputType(
              extractJsonValue(fieldPlan.basePlan, jsonParser));
        default:
          return extractPrimitiveValue(fieldPlan, jsonParser);
      }
    }

    private static Object extractPrimitiveValue(FieldPlan fieldPlan, JsonParser jsonParser)
        throws IOException {
      try {
        switch (fieldPlan.kind) {
          case BYTE:
            return extractByte(jsonParser);
          case INT16:
            return extractShort(jsonParser);
          case INT32:
            return extractInt(jsonParser);
          case INT64:
            return extractLong(jsonParser);
          case FLOAT:
            return extractFloat(jsonParser);
          case DOUBLE:
            return extractDouble(jsonParser);
          case BOOLEAN:
            return extractBoolean(jsonParser);
          case STRING:
            return extractString(jsonParser);
          case DECIMAL:
            return extractDecimal(jsonParser);
          case DATETIME:
            return extractDatetime(jsonParser).toInstant();
          case SQL_DATE:
            return extractDate(jsonParser);
          case SQL_TIME:
            return extractTime(jsonParser);
          case SQL_DATETIME:
            return extractLocalDatetime(jsonParser);
          default:
            throw new UnsupportedRowJsonException(
                "Field type '"
                    + fieldPlan.typeName
                    + "' is not supported when converting JSON to Row.");
        }
      } catch (RuntimeException e) {
        throw new UnsupportedRowJsonException(
            "Unable to get value from field '"
                + fieldPlan.name
                + "'. Schema type '"
                + fieldPlan.typeName
                + "'. JSON node type "
                + tokenNodeTypeName(jsonParser.currentToken()),
            e);
      }
    }

    private Row jsonObjectToRow(String fieldName, SchemaPlan rowPlan, JsonParser jsonParser)
        throws IOException {
      if (jsonParser.currentToken() != JsonToken.START_OBJECT) {
        throw new UnsupportedRowJsonException(
            "Expected JSON object for field '"
                + fieldName
                + "'. Unable to convert '"
                + jsonParser.getValueAsString("")
                + "' to Beam Row, it is not a JSON object. Currently only JSON objects can be parsed to Beam Rows");
      }

      int fieldCount = rowPlan.fieldCount;
      FieldPlan[] fields = rowPlan.fields;
      Schema rowSchema = rowPlan.schema;
      Object[] values = new Object[fieldCount];

      if (fieldCount <= 64) {
        long presentMask = 0L;
        int expectedIndex = 0;
        while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
          String propertyName = jsonParser.currentName();
          jsonParser.nextToken();
          int index = -1;
          FieldPlan matchedField = null;
          if (expectedIndex < fieldCount) {
            FieldPlan candidate = fields[expectedIndex];
            if (candidate.internedName.equals(propertyName)) {
              index = expectedIndex;
              matchedField = candidate;
              expectedIndex++;
            }
          }
          if (matchedField == null && rowSchema.hasField(propertyName)) {
            index = rowSchema.indexOf(propertyName);
            matchedField = fields[index];
            expectedIndex = index + 1;
          }
          if (matchedField != null) {
            presentMask |= (1L << index);
            values[index] = extractJsonValue(matchedField, jsonParser);
          } else {
            jsonParser.skipChildren();
          }
        }

        if (presentMask != rowPlan.allPresentMask) {
          for (int i = 0; i < fieldCount; i++) {
            if ((presentMask & (1L << i)) == 0L) {
              validateMissingField(fields[i]);
            }
          }
        }
      } else {
        boolean[] fieldPresent = new boolean[fieldCount];
        int expectedIndex = 0;
        while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
          String propertyName = jsonParser.currentName();
          jsonParser.nextToken();
          int index = -1;
          FieldPlan matchedField = null;
          if (expectedIndex < fieldCount) {
            FieldPlan candidate = fields[expectedIndex];
            if (candidate.internedName.equals(propertyName)) {
              index = expectedIndex;
              matchedField = candidate;
              expectedIndex++;
            }
          }
          if (matchedField == null && rowSchema.hasField(propertyName)) {
            index = rowSchema.indexOf(propertyName);
            matchedField = fields[index];
            expectedIndex = index + 1;
          }
          if (matchedField != null) {
            fieldPresent[index] = true;
            values[index] = extractJsonValue(matchedField, jsonParser);
          } else {
            jsonParser.skipChildren();
          }
        }

        for (int i = 0; i < fieldCount; i++) {
          if (!fieldPresent[i]) {
            validateMissingField(fields[i]);
          }
        }
      }

      return Row.withSchema(rowSchema).attachValues(values);
    }

    private void validateMissingField(FieldPlan fieldPlan) {
      if (!fieldPlan.nullable) {
        throw new UnsupportedRowJsonException(
            "Non-nullable field '" + fieldPlan.name + "' is not present in the JSON object.");
      } else if (this.nullBehavior == NullBehavior.REQUIRE_NULL) {
        throw new UnsupportedRowJsonException(
            "Field '" + fieldPlan.name + "' is not present in the JSON object.");
      }
    }

    private Object jsonArrayToList(FieldPlan arrayFieldPlan, JsonParser jsonParser)
        throws IOException {
      JsonToken token = jsonParser.currentToken();
      if (token != JsonToken.START_ARRAY) {
        throw new UnsupportedRowJsonException(
            "Expected JSON array for field '"
                + arrayFieldPlan.name
                + "'. Instead got "
                + tokenNodeTypeName(token));
      }

      if (jsonParser.nextToken() == JsonToken.END_ARRAY) {
        return ImmutableList.of();
      }
      FieldPlan elementPlan = arrayFieldPlan.elementPlan;
      ImmutableList.Builder<Object> builder = ImmutableList.builder();
      do {
        builder.add(extractJsonValue(elementPlan, jsonParser));
      } while (jsonParser.nextToken() != JsonToken.END_ARRAY);
      return builder.build();
    }

    private Map<String, Object> jsonObjectToMap(FieldPlan mapFieldPlan, JsonParser jsonParser)
        throws IOException {
      JsonToken token = jsonParser.currentToken();
      if (token != JsonToken.START_OBJECT) {
        throw new UnsupportedRowJsonException(
            "Expected JSON object for field '"
                + mapFieldPlan.name
                + "'. Instead got "
                + tokenNodeTypeName(token));
      }

      FieldPlan mapValuePlan = mapFieldPlan.mapValuePlan;
      Map<String, Object> result = new HashMap<>();
      while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
        String key = jsonParser.currentName();
        jsonParser.nextToken();
        result.put(key, extractJsonValue(mapValuePlan, jsonParser));
      }
      return result;
    }

    private static String tokenNodeTypeName(@Nullable JsonToken token) {
      if (token == null) {
        return "MISSING";
      }
      switch (token) {
        case START_OBJECT:
        case END_OBJECT:
        case FIELD_NAME:
          return "OBJECT";
        case START_ARRAY:
        case END_ARRAY:
          return "ARRAY";
        case VALUE_STRING:
          return "STRING";
        case VALUE_NUMBER_INT:
        case VALUE_NUMBER_FLOAT:
          return "NUMBER";
        case VALUE_TRUE:
        case VALUE_FALSE:
          return "BOOLEAN";
        case VALUE_NULL:
          return "NULL";
        case VALUE_EMBEDDED_OBJECT:
          return "POJO";
        default:
          return token.name();
      }
    }
  }

  /** Jackson serializer for converting {@link Row Rows} to JSON. */
  public static class RowJsonSerializer extends StdSerializer<Row> {

    private final SchemaPlan rootPlan;
    private boolean dropNullsOnWrite = false;

    /** Creates a serializer for a {@link Row} {@link Schema}. */
    public static RowJsonSerializer forSchema(Schema schema) {
      verifySchemaSupported(schema);
      return new RowJsonSerializer(schema);
    }

    private RowJsonSerializer(Schema schema) {
      super(Row.class);
      this.rootPlan = new SchemaPlan(schema);
    }

    /** Serializer drops nulls on write if set to true instead of writing fieldName: null. */
    public RowJsonSerializer withDropNullsOnWrite(Boolean dropNullsOnWrite) {
      this.dropNullsOnWrite = Boolean.TRUE.equals(dropNullsOnWrite);
      return this;
    }

    @Override
    public void serialize(Row value, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      writeRow(value, this.rootPlan, gen);
    }

    private void writeRow(Row row, SchemaPlan plan, JsonGenerator gen) throws IOException {
      gen.writeStartObject();
      FieldPlan[] fields = plan.fields;
      for (int i = 0, n = fields.length; i < n; ++i) {
        FieldPlan field = fields[i];
        if (field.nullable && row.isNull(i)) {
          if (!dropNullsOnWrite) {
            gen.writeFieldName(field.serializedName);
            gen.writeNull();
          }
          continue;
        }
        gen.writeFieldName(field.serializedName);
        writeFieldValue(row, i, field, gen);
      }
      gen.writeEndObject();
    }

    private void writeFieldValue(Row row, int index, FieldPlan field, JsonGenerator gen)
        throws IOException {
      switch (field.kind) {
        case BOOLEAN:
          gen.writeBoolean(row.getPrimitiveBoolean(index));
          break;
        case STRING:
          gen.writeString((String) row.getValue(index));
          break;
        case BYTE:
          gen.writeNumber(row.getPrimitiveByte(index));
          break;
        case DOUBLE:
          gen.writeNumber(row.getPrimitiveDouble(index));
          break;
        case FLOAT:
          gen.writeNumber(row.getPrimitiveFloat(index));
          break;
        case INT16:
          gen.writeNumber(row.getPrimitiveInt16(index));
          break;
        case INT32:
          gen.writeNumber(row.getPrimitiveInt32(index));
          break;
        case INT64:
          gen.writeNumber(row.getPrimitiveInt64(index));
          break;
        case DECIMAL:
          gen.writeNumber((BigDecimal) row.getValue(index));
          break;
        case DATETIME:
          gen.writeString(((ReadableInstant) row.getValue(index)).toString());
          break;
        case SQL_DATE:
          gen.writeString(((LocalDate) row.getValue(index)).toString());
          break;
        case SQL_TIME:
          gen.writeString(((LocalTime) row.getValue(index)).toString());
          break;
        case SQL_DATETIME:
          gen.writeString(((LocalDateTime) row.getValue(index)).toString());
          break;
        case ROW:
          writeRow((Row) row.getValue(index), field.rowPlan, gen);
          break;
        default:
          writeCompositeOrLogicalValue(gen, field, row.getValue(index));
          break;
      }
    }

    private void writeCompositeOrLogicalValue(JsonGenerator gen, FieldPlan field, Object value)
        throws IOException {
      switch (field.kind) {
        case ARRAY:
          gen.writeStartArray();
          FieldPlan elementPlan = field.elementPlan;
          if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            for (int i = 0, size = list.size(); i < size; i++) {
              writeValue(gen, elementPlan, list.get(i));
            }
          } else {
            for (Object element : (Iterable<Object>) value) {
              writeValue(gen, elementPlan, element);
            }
          }
          gen.writeEndArray();
          break;
        case MAP:
          gen.writeStartObject();
          FieldPlan mapValuePlan = field.mapValuePlan;
          for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) value).entrySet()) {
            gen.writeFieldName(entry.getKey().toString());
            writeValue(gen, mapValuePlan, entry.getValue());
          }
          gen.writeEndObject();
          break;
        case LOGICAL_TYPE:
          writeValue(gen, field.basePlan, field.logicalType.toBaseType(value));
          break;
        default:
          throw new IllegalArgumentException("Unsupported field type: " + field.typeName);
      }
    }

    private void writeValue(JsonGenerator gen, FieldPlan field, Object value) throws IOException {
      switch (field.kind) {
        case BOOLEAN:
          gen.writeBoolean((boolean) value);
          break;
        case STRING:
          gen.writeString((String) value);
          break;
        case BYTE:
          gen.writeNumber((byte) value);
          break;
        case DOUBLE:
          gen.writeNumber((double) value);
          break;
        case FLOAT:
          gen.writeNumber((float) value);
          break;
        case INT16:
          gen.writeNumber((short) value);
          break;
        case INT32:
          gen.writeNumber((int) value);
          break;
        case INT64:
          gen.writeNumber((long) value);
          break;
        case DECIMAL:
          gen.writeNumber((BigDecimal) value);
          break;
        case DATETIME:
          // ReadableInstant.toString produces ISO8601 format string
          gen.writeString(((ReadableInstant) value).toString());
          break;
        case SQL_DATE:
          gen.writeString(((LocalDate) value).toString());
          break;
        case SQL_TIME:
          gen.writeString(((LocalTime) value).toString());
          break;
        case SQL_DATETIME:
          gen.writeString(((LocalDateTime) value).toString());
          break;
        case ROW:
          writeRow((Row) value, field.rowPlan, gen);
          break;
        default:
          writeCompositeOrLogicalValue(gen, field, value);
          break;
      }
    }
  }

  /** Gets thrown when Row parsing or serialization fails for any reason. */
  public static class UnsupportedRowJsonException extends RuntimeException {

    UnsupportedRowJsonException(String message, Throwable reason) {
      super(message, reason);
    }

    UnsupportedRowJsonException(String message) {
      super(message);
    }
  }
}
