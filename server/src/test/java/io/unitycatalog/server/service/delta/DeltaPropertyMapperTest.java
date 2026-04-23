package io.unitycatalog.server.service.delta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.unitycatalog.server.delta.model.ClusteringDomainMetadata;
import io.unitycatalog.server.delta.model.DeltaProtocol;
import io.unitycatalog.server.delta.model.DomainMetadataUpdates;
import io.unitycatalog.server.delta.model.RowTrackingDomainMetadata;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.utils.TableProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DeltaPropertyMapper}. This class is shared by create, update, and commit
 * endpoints, so its wire-format projections and consistency rules are pinned here directly rather
 * than only indirectly through one endpoint's integration tests.
 */
public class DeltaPropertyMapperTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  // ---------- deriveFromProtocol ----------

  @Test
  public void deriveFromProtocolProducesFeatureProperties() {
    DeltaProtocol protocol =
        new DeltaProtocol()
            .minReaderVersion(3)
            .minWriterVersion(7)
            .readerFeatures(
                List.of(DeltaTableFeatures.DELETION_VECTORS, DeltaTableFeatures.V2_CHECKPOINT))
            .writerFeatures(
                List.of(DeltaTableFeatures.CATALOG_MANAGED, DeltaTableFeatures.DELETION_VECTORS));
    Map<String, String> props = DeltaPropertyMapper.deriveFromProtocol(protocol);
    assertThat(props)
        .containsEntry(featureKey(DeltaTableFeatures.CATALOG_MANAGED), "supported")
        .containsEntry(featureKey(DeltaTableFeatures.DELETION_VECTORS), "supported")
        .containsEntry(featureKey(DeltaTableFeatures.V2_CHECKPOINT), "supported");
    // Reader-writer feature appears once even though it's in both lists.
    assertThat(props.keySet())
        .filteredOn(k -> k.startsWith(TableProperties.DELTA_FEATURE_PREFIX))
        .doesNotHaveDuplicates();
  }

  @Test
  public void deriveFromProtocolReturnsEmptyForNull() {
    assertThat(DeltaPropertyMapper.deriveFromProtocol(null)).isEmpty();
  }

  @Test
  public void deriveFromProtocolReturnsEmptyForEmptyFeatureLists() {
    DeltaProtocol protocol = new DeltaProtocol().minReaderVersion(3).minWriterVersion(7);
    // null feature lists (no setter call) -- handler tolerates both null and empty.
    assertThat(DeltaPropertyMapper.deriveFromProtocol(protocol)).isEmpty();
  }

  // ---------- deriveFromDomainMetadata ----------

  @Test
  public void deriveFromDomainMetadataClusteringEncodesAsJsonArrayOfPaths() throws Exception {
    DomainMetadataUpdates dm =
        new DomainMetadataUpdates()
            .deltaClustering(
                new ClusteringDomainMetadata()
                    .clusteringColumns(List.of(List.of("id"), List.of("address", "city"))));
    Map<String, String> props = DeltaPropertyMapper.deriveFromDomainMetadata(dm);
    String json = props.get(TableProperties.DELTA_CLUSTERING_COLUMNS);
    JsonNode parsed = JSON.readTree(json);
    // Nested paths stay as arrays -- don't collapse into dotted strings.
    assertThat(parsed.isArray()).isTrue();
    assertThat(parsed.size()).isEqualTo(2);
    assertThat(parsed.get(0).get(0).asText()).isEqualTo("id");
    assertThat(parsed.get(1).get(0).asText()).isEqualTo("address");
    assertThat(parsed.get(1).get(1).asText()).isEqualTo("city");
  }

  @Test
  public void deriveFromDomainMetadataRowTrackingEncodesHighWaterMark() {
    DomainMetadataUpdates dm =
        new DomainMetadataUpdates()
            .deltaRowTracking(new RowTrackingDomainMetadata().rowIdHighWaterMark(42L));
    Map<String, String> props = DeltaPropertyMapper.deriveFromDomainMetadata(dm);
    assertThat(props)
        .containsEntry(TableProperties.DELTA_ROW_TRACKING_ROW_ID_HIGH_WATER_MARK, "42");
  }

  @Test
  public void deriveFromDomainMetadataReturnsEmptyForNull() {
    assertThat(DeltaPropertyMapper.deriveFromDomainMetadata(null)).isEmpty();
  }

  @Test
  public void deriveFromDomainMetadataOmitsUnsetEntries() {
    // A DomainMetadataUpdates with neither clustering nor row-tracking fields set yields no
    // properties. Pins the "only write properties for entries the client actually provided"
    // contract for the shared derive API.
    assertThat(DeltaPropertyMapper.deriveFromDomainMetadata(new DomainMetadataUpdates())).isEmpty();
  }

  // ---------- mergeDerivedWithClient ----------

  @Test
  public void mergeClientPropertiesWinOnConflict() {
    DeltaProtocol protocol =
        new DeltaProtocol()
            .minReaderVersion(3)
            .minWriterVersion(7)
            .writerFeatures(List.of(DeltaTableFeatures.CATALOG_MANAGED));
    Map<String, String> client =
        Map.of(featureKey(DeltaTableFeatures.CATALOG_MANAGED), "client-override");
    Map<String, String> merged = DeltaPropertyMapper.mergeDerivedWithClient(protocol, null, client);
    // Client value wins -- callers can pin engine-managed values or override defaults.
    assertThat(merged)
        .containsEntry(featureKey(DeltaTableFeatures.CATALOG_MANAGED), "client-override");
  }

  @Test
  public void mergeTolerantOfAllNulls() {
    assertThat(DeltaPropertyMapper.mergeDerivedWithClient(null, null, null)).isEmpty();
  }

  @Test
  public void mergeCombinesProtocolDomainMetadataAndClient() {
    DeltaProtocol protocol =
        new DeltaProtocol()
            .minReaderVersion(3)
            .minWriterVersion(7)
            .writerFeatures(List.of(DeltaTableFeatures.ROW_TRACKING));
    DomainMetadataUpdates dm =
        new DomainMetadataUpdates()
            .deltaRowTracking(new RowTrackingDomainMetadata().rowIdHighWaterMark(100L));
    Map<String, String> client = Map.of("custom.key", "custom.value");
    Map<String, String> merged = DeltaPropertyMapper.mergeDerivedWithClient(protocol, dm, client);
    assertThat(merged)
        .containsEntry(featureKey(DeltaTableFeatures.ROW_TRACKING), "supported")
        .containsEntry(TableProperties.DELTA_ROW_TRACKING_ROW_ID_HIGH_WATER_MARK, "100")
        .containsEntry("custom.key", "custom.value");
  }

  // ---------- validateDomainMetadataAgainstProtocol ----------

  @Test
  public void validateClusteringDomainMetadataRequiresClusteringFeature() {
    DomainMetadataUpdates dm =
        new DomainMetadataUpdates()
            .deltaClustering(
                new ClusteringDomainMetadata().clusteringColumns(List.of(List.of("id"))));
    assertThatThrownBy(() -> DeltaPropertyMapper.validateDomainMetadataAgainstProtocol(null, dm))
        .isInstanceOf(BaseException.class)
        .hasMessageContaining("'" + DeltaTableFeatures.CLUSTERING + "' writer feature");
  }

  @Test
  public void validateRowTrackingDomainMetadataRequiresRowTrackingFeature() {
    DomainMetadataUpdates dm =
        new DomainMetadataUpdates()
            .deltaRowTracking(new RowTrackingDomainMetadata().rowIdHighWaterMark(0L));
    DeltaProtocol protocolWithoutRowTracking =
        new DeltaProtocol()
            .minReaderVersion(3)
            .minWriterVersion(7)
            .writerFeatures(List.of(DeltaTableFeatures.DELETION_VECTORS));
    assertThatThrownBy(
            () ->
                DeltaPropertyMapper.validateDomainMetadataAgainstProtocol(
                    protocolWithoutRowTracking, dm))
        .isInstanceOf(BaseException.class)
        .hasMessageContaining("'" + DeltaTableFeatures.ROW_TRACKING + "' writer feature");
  }

  @Test
  public void validateAcceptsDomainMetadataWithMatchingFeatures() {
    DeltaProtocol protocol =
        new DeltaProtocol()
            .minReaderVersion(3)
            .minWriterVersion(7)
            .writerFeatures(
                List.of(DeltaTableFeatures.CLUSTERING, DeltaTableFeatures.ROW_TRACKING));
    DomainMetadataUpdates dm =
        new DomainMetadataUpdates()
            .deltaClustering(
                new ClusteringDomainMetadata().clusteringColumns(List.of(List.of("id"))))
            .deltaRowTracking(new RowTrackingDomainMetadata().rowIdHighWaterMark(7L));
    // Should not throw.
    DeltaPropertyMapper.validateDomainMetadataAgainstProtocol(protocol, dm);
  }

  @Test
  public void validateNullDomainMetadataIsAccepted() {
    // Null domain-metadata is always fine regardless of what the protocol declares.
    DeltaPropertyMapper.validateDomainMetadataAgainstProtocol(null, null);
    DeltaPropertyMapper.validateDomainMetadataAgainstProtocol(
        new DeltaProtocol().writerFeatures(List.of(DeltaTableFeatures.CLUSTERING)), null);
  }

  private static String featureKey(String feature) {
    return TableProperties.DELTA_FEATURE_PREFIX + feature;
  }
}
