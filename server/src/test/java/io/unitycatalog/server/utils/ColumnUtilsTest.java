package io.unitycatalog.server.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.unitycatalog.server.delta.model.ArrayType;
import io.unitycatalog.server.delta.model.DecimalType;
import io.unitycatalog.server.delta.model.MapType;
import io.unitycatalog.server.delta.model.PrimitiveType;
import io.unitycatalog.server.delta.model.StructField;
import io.unitycatalog.server.delta.model.StructType;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.model.ColumnInfo;
import io.unitycatalog.server.model.ColumnTypeName;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for ColumnUtils.toStructField parsing typeJson into typed Delta StructField. */
public class ColumnUtilsTest {

  private static ColumnInfo col(String name, String typeJson) {
    return new ColumnInfo().name(name).typeJson(typeJson);
  }

  // ---------- Primitives ----------

  @Test
  public void testPrimitive() {
    StructField f =
        ColumnUtils.toStructField(
            col("id", "{\"name\":\"id\",\"type\":\"long\",\"nullable\":false,\"metadata\":{}}"));
    assertThat(f.getName()).isEqualTo("id");
    assertThat(f.getNullable()).isFalse();
    assertThat(f.getType()).isInstanceOf(PrimitiveType.class);
    assertThat(f.getType().getType()).isEqualTo("long");
    assertThat(f.getMetadata()).isEmpty();
  }

  @Test
  public void testDecimal() {
    StructField f =
        ColumnUtils.toStructField(
            col(
                "price",
                "{\"name\":\"price\",\"type\":\"decimal(10,2)\","
                    + "\"nullable\":true,\"metadata\":{}}"));
    assertThat(f.getType()).isInstanceOf(DecimalType.class);
    DecimalType dt = (DecimalType) f.getType();
    assertThat(dt.getPrecision()).isEqualTo(10);
    assertThat(dt.getScale()).isEqualTo(2);
  }

  // ---------- Complex types with Spark camelCase ----------

  @Test
  public void testArrayCamelCase() {
    StructField f =
        ColumnUtils.toStructField(
            col(
                "tags",
                "{\"name\":\"tags\",\"type\":{\"type\":\"array\","
                    + "\"elementType\":\"string\",\"containsNull\":true},"
                    + "\"nullable\":true,\"metadata\":{}}"));
    assertThat(f.getType()).isInstanceOf(ArrayType.class);
    ArrayType at = (ArrayType) f.getType();
    assertThat(at.getElementType()).isInstanceOf(PrimitiveType.class);
    assertThat(at.getElementType().getType()).isEqualTo("string");
    assertThat(at.getContainsNull()).isTrue();
  }

  @Test
  public void testMapCamelCase() {
    StructField f =
        ColumnUtils.toStructField(
            col(
                "scores",
                "{\"name\":\"scores\",\"type\":{\"type\":\"map\","
                    + "\"keyType\":\"string\",\"valueType\":\"double\","
                    + "\"valueContainsNull\":false},"
                    + "\"nullable\":true,\"metadata\":{}}"));
    assertThat(f.getType()).isInstanceOf(MapType.class);
    MapType mt = (MapType) f.getType();
    assertThat(mt.getKeyType().getType()).isEqualTo("string");
    assertThat(mt.getValueType().getType()).isEqualTo("double");
    assertThat(mt.getValueContainsNull()).isFalse();
  }

  @Test
  public void testStructCamelCase() {
    StructField f =
        ColumnUtils.toStructField(
            col(
                "addr",
                "{\"name\":\"addr\",\"type\":{\"type\":\"struct\","
                    + "\"fields\":[{\"name\":\"zip\",\"type\":\"integer\","
                    + "\"nullable\":false,\"metadata\":{}}]},"
                    + "\"nullable\":true,\"metadata\":{}}"));
    assertThat(f.getType()).isInstanceOf(StructType.class);
    StructType st = (StructType) f.getType();
    assertThat(st.getFields()).hasSize(1);
    assertThat(st.getFields().get(0).getName()).isEqualTo("zip");
    assertThat(st.getFields().get(0).getType().getType()).isEqualTo("integer");
  }

  // ---------- Nested complex ----------

  @Test
  public void testNestedMapArrayStructCamelCase() {
    // map<string, array<struct<v:double>>>
    StructField f =
        ColumnUtils.toStructField(
            col(
                "data",
                "{\"name\":\"data\",\"type\":{\"type\":\"map\","
                    + "\"keyType\":\"string\","
                    + "\"valueType\":{\"type\":\"array\","
                    + "\"elementType\":{\"type\":\"struct\","
                    + "\"fields\":[{\"name\":\"v\",\"type\":\"double\","
                    + "\"nullable\":false,\"metadata\":{}}]},"
                    + "\"containsNull\":true},"
                    + "\"valueContainsNull\":true},"
                    + "\"nullable\":true,\"metadata\":{}}"));
    MapType mt = (MapType) f.getType();
    ArrayType at = (ArrayType) mt.getValueType();
    StructType st = (StructType) at.getElementType();
    assertThat(st.getFields().get(0).getType().getType()).isEqualTo("double");
  }

  // ---------- Metadata ----------

  @Test
  public void testMetadataPreserved() {
    StructField f =
        ColumnUtils.toStructField(
            col(
                "id",
                "{\"name\":\"id\",\"type\":\"long\",\"nullable\":false,"
                    + "\"metadata\":{\"delta.columnMapping.id\":1,"
                    + "\"comment\":\"primary key\"}}"));
    assertThat(f.getMetadata()).containsEntry("comment", "primary key");
    assertThat(f.getMetadata()).containsEntry("delta.columnMapping.id", 1);
  }

  // ---------- Roundtrip (read camelCase -> write camelCase) ----------

  @Test
  public void testRoundtripPrimitive() {
    String typeJson = "{\"name\":\"id\",\"type\":\"long\"," + "\"nullable\":false,\"metadata\":{}}";
    StructField f = ColumnUtils.toStructField(col("id", typeJson));
    String written = ColumnUtils.toTypeJson(f);
    assertThat(written).contains("\"type\":\"long\"");
    assertThat(written).contains("\"name\":\"id\"");
  }

  @Test
  public void testRoundtripArray() {
    String typeJson =
        "{\"name\":\"tags\",\"type\":{\"type\":\"array\","
            + "\"elementType\":\"string\",\"containsNull\":true},"
            + "\"nullable\":true,\"metadata\":{}}";
    StructField f = ColumnUtils.toStructField(col("tags", typeJson));
    String written = ColumnUtils.toTypeJson(f);
    // Must serialize back to camelCase, not kebab-case
    assertThat(written).contains("\"elementType\"");
    assertThat(written).contains("\"containsNull\"");
    assertThat(written).doesNotContain("\"element-type\"");
    assertThat(written).doesNotContain("\"contains-null\"");
  }

  @Test
  public void testRoundtripMap() {
    String typeJson =
        "{\"name\":\"m\",\"type\":{\"type\":\"map\","
            + "\"keyType\":\"string\",\"valueType\":\"double\","
            + "\"valueContainsNull\":false},"
            + "\"nullable\":true,\"metadata\":{}}";
    StructField f = ColumnUtils.toStructField(col("m", typeJson));
    String written = ColumnUtils.toTypeJson(f);
    assertThat(written).contains("\"keyType\"");
    assertThat(written).contains("\"valueType\"");
    assertThat(written).contains("\"valueContainsNull\"");
    assertThat(written).doesNotContain("\"key-type\"");
  }

  @Test
  public void testRoundtripNestedPreservesStructure() {
    String typeJson =
        "{\"name\":\"data\",\"type\":{\"type\":\"map\","
            + "\"keyType\":\"string\","
            + "\"valueType\":{\"type\":\"array\","
            + "\"elementType\":{\"type\":\"struct\","
            + "\"fields\":[{\"name\":\"v\",\"type\":\"double\","
            + "\"nullable\":false,\"metadata\":{}}]},"
            + "\"containsNull\":true},"
            + "\"valueContainsNull\":true},"
            + "\"nullable\":true,\"metadata\":{}}";
    StructField f = ColumnUtils.toStructField(col("data", typeJson));
    String written = ColumnUtils.toTypeJson(f);
    // Re-read and verify structure
    StructField f2 = ColumnUtils.toStructField(col("data", written));
    MapType mt = (MapType) f2.getType();
    ArrayType at = (ArrayType) mt.getValueType();
    StructType st = (StructType) at.getElementType();
    assertThat(st.getFields().get(0).getName()).isEqualTo("v");
    assertThat(st.getFields().get(0).getType().getType()).isEqualTo("double");
  }

  // ---------- Error handling ----------

  @Test
  public void testNullTypeJson() {
    assertThatThrownBy(() -> ColumnUtils.toStructField(col("bad", null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("null/empty typeJson");
  }

  @Test
  public void testMalformedTypeJson() {
    assertThatThrownBy(() -> ColumnUtils.toStructField(col("bad", "not json")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to parse");
  }

  // ---------- toColumnInfo (DRC StructField -> UC ColumnInfo) ----------

  @Test
  public void testToColumnInfoPrimitive() {
    StructField field =
        new StructField()
            .name("id")
            .type(new PrimitiveType().type("long"))
            .nullable(false)
            .metadata(Map.of());
    ColumnInfo info = ColumnUtils.toColumnInfo(field, 0);
    assertThat(info.getName()).isEqualTo("id");
    assertThat(info.getNullable()).isFalse();
    assertThat(info.getPosition()).isEqualTo(0);
    assertThat(info.getTypeName()).isEqualTo(ColumnTypeName.LONG);
    // LONG -> "bigint" via the SQL-style alias map.
    assertThat(info.getTypeText()).isEqualTo("bigint");
    assertThat(info.getTypeJson()).contains("\"type\":\"long\"");
  }

  @Test
  public void testToColumnInfoDecimalPreservesPrecisionAndScale() {
    StructField field =
        new StructField()
            .name("amount")
            .type(new DecimalType().precision(10).scale(2))
            .nullable(true)
            .metadata(Map.of());
    ColumnInfo info = ColumnUtils.toColumnInfo(field, 1);
    assertThat(info.getTypeName()).isEqualTo(ColumnTypeName.DECIMAL);
    // Precision/scale must reach typeText so DESCRIBE TABLE renders the right SQL type.
    assertThat(info.getTypeText()).isEqualTo("decimal(10,2)");
  }

  @Test
  public void testToColumnInfoComplex() {
    StructField arr =
        new StructField()
            .name("tags")
            .type(new ArrayType().type("array").elementType(new PrimitiveType().type("string")))
            .nullable(true)
            .metadata(Map.of());
    ColumnInfo arrInfo = ColumnUtils.toColumnInfo(arr, 0);
    assertThat(arrInfo.getTypeName()).isEqualTo(ColumnTypeName.ARRAY);
    // typeText is the Spark catalogString-equivalent, recursively parameterized.
    assertThat(arrInfo.getTypeText()).isEqualTo("array<string>");

    StructField map =
        new StructField()
            .name("attrs")
            .type(
                new MapType()
                    .type("map")
                    .keyType(new PrimitiveType().type("string"))
                    .valueType(new PrimitiveType().type("double")))
            .nullable(true)
            .metadata(Map.of());
    ColumnInfo mapInfo = ColumnUtils.toColumnInfo(map, 0);
    assertThat(mapInfo.getTypeName()).isEqualTo(ColumnTypeName.MAP);
    assertThat(mapInfo.getTypeText()).isEqualTo("map<string,double>");

    StructField struct =
        new StructField()
            .name("nested")
            .type(
                new StructType()
                    .type("struct")
                    .fields(
                        List.of(
                            new StructField()
                                .name("zip")
                                .type(new PrimitiveType().type("integer"))
                                .nullable(false)
                                .metadata(Map.of()),
                            new StructField()
                                .name("city")
                                .type(new PrimitiveType().type("string"))
                                .nullable(true)
                                .metadata(Map.of()))))
            .nullable(true)
            .metadata(Map.of());
    ColumnInfo structInfo = ColumnUtils.toColumnInfo(struct, 0);
    assertThat(structInfo.getTypeName()).isEqualTo(ColumnTypeName.STRUCT);
    assertThat(structInfo.getTypeText()).isEqualTo("struct<zip:int,city:string>");
  }

  @Test
  public void testToColumnInfoNestedCatalogString() {
    // map<string, array<struct<v:double>>> -- the recursion composes the right way down.
    StructField field =
        new StructField()
            .name("data")
            .type(
                new MapType()
                    .type("map")
                    .keyType(new PrimitiveType().type("string"))
                    .valueType(
                        new ArrayType()
                            .type("array")
                            .elementType(
                                new StructType()
                                    .type("struct")
                                    .fields(
                                        List.of(
                                            new StructField()
                                                .name("v")
                                                .type(new PrimitiveType().type("double"))
                                                .nullable(false)
                                                .metadata(Map.of()))))))
            .nullable(true)
            .metadata(Map.of());
    assertThat(ColumnUtils.toColumnInfo(field, 0).getTypeText())
        .isEqualTo("map<string,array<struct<v:double>>>");
  }

  @Test
  public void testToColumnInfoLiftsCommentFromMetadata() {
    // Delta spec stores column comments in metadata.comment; UCSingleCatalog lifts them into
    // ColumnInfo.comment via field.getComment(), and this mapper does the same so DESCRIBE
    // renders the comment regardless of which client wrote the table.
    StructField field =
        new StructField()
            .name("id")
            .type(new PrimitiveType().type("long"))
            .nullable(false)
            .metadata(Map.of("comment", "primary key"));
    assertThat(ColumnUtils.toColumnInfo(field, 0).getComment()).isEqualTo("primary key");
  }

  @Test
  public void testToColumnInfoNoCommentWhenMetadataAbsentOrNonString() {
    StructField noMeta =
        new StructField()
            .name("x")
            .type(new PrimitiveType().type("long"))
            .nullable(true)
            .metadata(Map.of());
    assertThat(ColumnUtils.toColumnInfo(noMeta, 0).getComment()).isNull();

    StructField nonStringComment =
        new StructField()
            .name("y")
            .type(new PrimitiveType().type("long"))
            .nullable(true)
            .metadata(Map.of("comment", 42));
    // Non-string comment values (spec-invalid but tolerated) are ignored, not coerced.
    assertThat(ColumnUtils.toColumnInfo(nonStringComment, 0).getComment()).isNull();
  }

  @Test
  public void testToColumnInfoNullPrimitive() {
    // Spark's NullType serializes as "null" in Delta's typeJson -- mapped to ColumnTypeName.NULL.
    StructField field =
        new StructField()
            .name("n")
            .type(new PrimitiveType().type("null"))
            .nullable(true)
            .metadata(Map.of());
    ColumnInfo info = ColumnUtils.toColumnInfo(field, 0);
    assertThat(info.getTypeName()).isEqualTo(ColumnTypeName.NULL);
    assertThat(info.getTypeText()).isEqualTo("null");
  }

  @Test
  public void testToColumnInfoUnsupportedPrimitiveRejected() {
    // The server-side ColumnTypeName has no UNKNOWN_DEFAULT_OPEN_API sentinel (that's a
    // client-only fallback added by the OpenAPI generator). An unrecognized primitive is
    // surfaced as INVALID_ARGUMENT so the caller sees a 400 with a clear message rather than a
    // silently stored column with the wrong type.
    StructField field =
        new StructField()
            .name("x")
            .type(new PrimitiveType().type("hyperdecimal"))
            .nullable(true)
            .metadata(Map.of());
    assertThatThrownBy(() -> ColumnUtils.toColumnInfo(field, 0))
        .isInstanceOf(BaseException.class)
        .hasMessageContaining("Unsupported Delta primitive type");
  }

  // ---------- applyPartitionColumns ----------

  @Test
  public void testApplyPartitionColumnsStampsIndicesByName() {
    List<ColumnInfo> columns =
        new ArrayList<>(
            List.of(
                new ColumnInfo().name("id").position(0),
                new ColumnInfo().name("region").position(1),
                new ColumnInfo().name("date").position(2)));
    // Order of the partition list is the partition-index order; not the column position.
    ColumnUtils.applyPartitionColumns(columns, List.of("date", "region"));
    assertThat(columns.get(0).getPartitionIndex()).isNull();
    assertThat(columns.get(1).getPartitionIndex()).isEqualTo(1); // region -> index 1
    assertThat(columns.get(2).getPartitionIndex()).isEqualTo(0); // date   -> index 0
  }

  @Test
  public void testApplyPartitionColumnsNullAndEmptyAreNoOp() {
    List<ColumnInfo> columns = new ArrayList<>(List.of(new ColumnInfo().name("id").position(0)));
    ColumnUtils.applyPartitionColumns(columns, null);
    ColumnUtils.applyPartitionColumns(columns, List.of());
    assertThat(columns.get(0).getPartitionIndex()).isNull();
  }

  @Test
  public void testApplyPartitionColumnsUnknownColumnRejected() {
    List<ColumnInfo> columns = new ArrayList<>(List.of(new ColumnInfo().name("id").position(0)));
    assertThatThrownBy(() -> ColumnUtils.applyPartitionColumns(columns, List.of("nope")))
        .isInstanceOf(BaseException.class)
        .hasMessageContaining("partition-columns references unknown column: nope");
  }
}
