package io.unitycatalog.server.service.delta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.unitycatalog.server.delta.model.ClusteringDomainMetadata;
import io.unitycatalog.server.delta.model.CreateTableRequest;
import io.unitycatalog.server.delta.model.DataSourceFormat;
import io.unitycatalog.server.delta.model.DeltaProtocol;
import io.unitycatalog.server.delta.model.DomainMetadataUpdates;
import io.unitycatalog.server.delta.model.PrimitiveType;
import io.unitycatalog.server.delta.model.RowTrackingDomainMetadata;
import io.unitycatalog.server.delta.model.StructField;
import io.unitycatalog.server.delta.model.StructType;
import io.unitycatalog.server.delta.model.TableType;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.model.CreateTable;
import io.unitycatalog.server.utils.TableProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DeltaCreateTableMapper}. */
public class DeltaCreateTableMapperTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  public void deriveFeaturePropertiesFromProtocol() {
    CreateTable created =
        DeltaCreateTableMapper.toCreateTable("cat", "sch", baseRequest(TableType.MANAGED));
    assertThat(created.getProperties())
        .containsEntry(featureKey(DeltaTableFeatures.CATALOG_MANAGED), "supported")
        .containsEntry(featureKey(DeltaTableFeatures.DELETION_VECTORS), "supported")
        .containsEntry(featureKey(DeltaTableFeatures.IN_COMMIT_TIMESTAMP), "supported");
    // Reader-only features land in the same property namespace; reader-writer features collapse
    // to a single entry rather than appearing twice.
    assertThat(created.getProperties().keySet())
        .filteredOn(k -> k.startsWith(TableProperties.DELTA_FEATURE_PREFIX))
        .doesNotHaveDuplicates();
  }

  @Test
  public void clientPropertiesWinOnConflict() {
    CreateTableRequest req =
        baseRequest(TableType.MANAGED)
            // Override the derived value: catalog must store the client's view, not its own.
            .properties(Map.of(featureKey(DeltaTableFeatures.CATALOG_MANAGED), "client-override"));
    CreateTable created = DeltaCreateTableMapper.toCreateTable("cat", "sch", req);
    assertThat(created.getProperties())
        .containsEntry(featureKey(DeltaTableFeatures.CATALOG_MANAGED), "client-override");
  }

  @Test
  public void clusteringDomainMetadataEncodedAsJsonArrayOfPaths() throws Exception {
    CreateTableRequest req =
        baseRequest(TableType.MANAGED)
            .protocol(protocolWith(DeltaTableFeatures.CLUSTERING))
            .domainMetadata(
                new DomainMetadataUpdates()
                    .deltaClustering(
                        new ClusteringDomainMetadata()
                            .clusteringColumns(
                                List.of(
                                    List.of("id"), // top-level column
                                    List.of("address", "city"))))); // nested column path

    CreateTable created = DeltaCreateTableMapper.toCreateTable("cat", "sch", req);
    String json = created.getProperties().get(TableProperties.DELTA_CLUSTERING_COLUMNS);
    JsonNode parsed = JSON.readTree(json);
    // Nested paths must remain element arrays, not collapsed into a single dotted string.
    assertThat(parsed.isArray()).isTrue();
    assertThat(parsed.size()).isEqualTo(2);
    assertThat(parsed.get(0).get(0).asText()).isEqualTo("id");
    assertThat(parsed.get(1).get(0).asText()).isEqualTo("address");
    assertThat(parsed.get(1).get(1).asText()).isEqualTo("city");
  }

  @Test
  public void rowTrackingDomainMetadataEncodedAsHighWaterMarkProperty() {
    CreateTableRequest req =
        baseRequest(TableType.MANAGED)
            .protocol(protocolWith(DeltaTableFeatures.ROW_TRACKING))
            .domainMetadata(
                new DomainMetadataUpdates()
                    .deltaRowTracking(new RowTrackingDomainMetadata().rowIdHighWaterMark(42L)));
    CreateTable created = DeltaCreateTableMapper.toCreateTable("cat", "sch", req);
    assertThat(created.getProperties())
        .containsEntry(TableProperties.DELTA_ROW_TRACKING_ROW_ID_HIGH_WATER_MARK, "42");
  }

  @Test
  public void clusteringDomainMetadataRequiresClusteringFeature() {
    CreateTableRequest req =
        baseRequest(TableType.MANAGED)
            .domainMetadata(
                new DomainMetadataUpdates()
                    .deltaClustering(
                        new ClusteringDomainMetadata().clusteringColumns(List.of(List.of("id")))));
    assertThatThrownBy(() -> DeltaCreateTableMapper.toCreateTable("cat", "sch", req))
        .isInstanceOf(BaseException.class)
        .hasMessageContaining("'clustering' writer feature");
  }

  @Test
  public void rowTrackingDomainMetadataRequiresRowTrackingFeature() {
    CreateTableRequest req =
        baseRequest(TableType.MANAGED)
            .domainMetadata(
                new DomainMetadataUpdates()
                    .deltaRowTracking(new RowTrackingDomainMetadata().rowIdHighWaterMark(0L)));
    assertThatThrownBy(() -> DeltaCreateTableMapper.toCreateTable("cat", "sch", req))
        .isInstanceOf(BaseException.class)
        .hasMessageContaining("'rowTracking' writer feature");
  }

  @Test
  public void managedTableRequiresCatalogManagedWriterFeature() {
    CreateTableRequest req =
        baseRequest(TableType.MANAGED)
            // Drop catalogManaged from the writer features.
            .protocol(
                new DeltaProtocol()
                    .minReaderVersion(3)
                    .minWriterVersion(7)
                    .readerFeatures(List.of(DeltaTableFeatures.DELETION_VECTORS))
                    .writerFeatures(List.of(DeltaTableFeatures.DELETION_VECTORS)));
    assertThatThrownBy(() -> DeltaCreateTableMapper.toCreateTable("cat", "sch", req))
        .isInstanceOf(BaseException.class)
        .hasMessageContaining(DeltaTableFeatures.CATALOG_MANAGED);
  }

  @Test
  public void externalTableDoesNotRequireCatalogManaged() {
    CreateTableRequest req =
        baseRequest(TableType.EXTERNAL)
            .protocol(
                new DeltaProtocol()
                    .minReaderVersion(3)
                    .minWriterVersion(7)
                    .readerFeatures(List.of(DeltaTableFeatures.DELETION_VECTORS))
                    .writerFeatures(List.of(DeltaTableFeatures.DELETION_VECTORS)));
    CreateTable created = DeltaCreateTableMapper.toCreateTable("cat", "sch", req);
    assertThat(created.getTableType()).isEqualTo(io.unitycatalog.server.model.TableType.EXTERNAL);
    assertThat(created.getProperties())
        .containsEntry(featureKey(DeltaTableFeatures.DELETION_VECTORS), "supported");
  }

  @Test
  public void unsupportedDataSourceFormatRejected() {
    CreateTableRequest req =
        baseRequest(TableType.MANAGED).dataSourceFormat(DataSourceFormat.ICEBERG);
    assertThatThrownBy(() -> DeltaCreateTableMapper.toCreateTable("cat", "sch", req))
        .isInstanceOf(BaseException.class)
        .hasMessageContaining("Unsupported data-source-format");
  }

  // --- fixtures ---

  private static CreateTableRequest baseRequest(TableType type) {
    return new CreateTableRequest()
        .name("tbl")
        .location("s3://b/p")
        .tableType(type)
        .dataSourceFormat(DataSourceFormat.DELTA)
        .columns(
            new StructType()
                .type("struct")
                .fields(
                    List.of(
                        new StructField()
                            .name("id")
                            .type(new PrimitiveType().type("long"))
                            .nullable(false)
                            .metadata(Map.of()))))
        .protocol(
            new DeltaProtocol()
                .minReaderVersion(3)
                .minWriterVersion(7)
                .readerFeatures(
                    List.of(DeltaTableFeatures.DELETION_VECTORS, DeltaTableFeatures.V2_CHECKPOINT))
                .writerFeatures(
                    List.of(
                        DeltaTableFeatures.CATALOG_MANAGED,
                        DeltaTableFeatures.DELETION_VECTORS,
                        DeltaTableFeatures.IN_COMMIT_TIMESTAMP)))
        .properties(Map.of());
  }

  private static DeltaProtocol protocolWith(String extraWriterFeature) {
    return new DeltaProtocol()
        .minReaderVersion(3)
        .minWriterVersion(7)
        .readerFeatures(List.of(DeltaTableFeatures.DELETION_VECTORS))
        .writerFeatures(
            List.of(
                DeltaTableFeatures.CATALOG_MANAGED,
                DeltaTableFeatures.DELETION_VECTORS,
                extraWriterFeature));
  }

  private static String featureKey(String feature) {
    return TableProperties.DELTA_FEATURE_PREFIX + feature;
  }
}
