package io.unitycatalog.server.service.delta;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.unitycatalog.server.delta.model.ClusteringDomainMetadata;
import io.unitycatalog.server.delta.model.DeltaProtocol;
import io.unitycatalog.server.delta.model.DomainMetadataUpdates;
import io.unitycatalog.server.delta.model.RowTrackingDomainMetadata;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.utils.TableProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps Delta REST Catalog {@code protocol} and {@code domain-metadata} blocks onto UC table
 * properties, and validates their internal consistency. Designed to be shared by create, update,
 * and commit endpoints: the mapping rules are specified by the Delta spec and don't vary by
 * endpoint (only create is wired up today; update and commit are on follow-ups).
 *
 * <p>Every feature in {@code protocol.reader-features} and {@code protocol.writer-features} yields
 * {@code delta.feature.<name> = supported}. Domain-metadata entries yield their Delta-documented
 * table-property projections:
 *
 * <ul>
 *   <li>{@code delta.clustering.clusteringColumns} -> {@code delta.clusteringColumns} (JSON-encoded
 *       list of column paths; paths are arrays of segment names so nested columns survive as arrays
 *       rather than collapsing into dotted strings).
 *   <li>{@code delta.rowTracking.rowIdHighWaterMark} -> {@code
 *       delta.rowTracking.rowIdHighWaterMark}.
 * </ul>
 *
 * <p>On consistency: a domain-metadata entry is only meaningful when the matching writer feature is
 * declared in the protocol. {@link #validateDomainMetadataAgainstProtocol} enforces that invariant;
 * a mismatch would produce a Delta log that UC and Delta engines disagree about.
 */
public final class DeltaPropertyMapper {

  private DeltaPropertyMapper() {}

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Returns the stored UC property map formed by merging protocol-derived, domain-metadata-derived,
   * and client-supplied entries. Client-supplied entries take precedence so callers can pin
   * engine-managed values (e.g. row-tracking materialized column names) or override a derived value
   * if needed.
   */
  public static Map<String, String> mergeDerivedWithClient(
      DeltaProtocol protocol,
      DomainMetadataUpdates domainMetadata,
      Map<String, String> clientProperties) {
    Map<String, String> merged = new HashMap<>();
    merged.putAll(deriveFromProtocol(protocol));
    merged.putAll(deriveFromDomainMetadata(domainMetadata));
    if (clientProperties != null) {
      merged.putAll(clientProperties);
    }
    return merged;
  }

  /**
   * Produces the {@code delta.feature.*} properties implied by a protocol block. Reader-writer
   * features collapse to a single key per feature name (both reader and writer lists mention them,
   * but the property appears once).
   */
  public static Map<String, String> deriveFromProtocol(DeltaProtocol protocol) {
    if (protocol == null) return Map.of();
    Map<String, String> props = new HashMap<>();
    addFeatureProperties(props, protocol.getReaderFeatures());
    addFeatureProperties(props, protocol.getWriterFeatures());
    return props;
  }

  /** Produces the table properties implied by a domain-metadata block. */
  public static Map<String, String> deriveFromDomainMetadata(DomainMetadataUpdates domainMetadata) {
    if (domainMetadata == null) return Map.of();
    Map<String, String> props = new HashMap<>();
    ClusteringDomainMetadata clustering = domainMetadata.getDeltaClustering();
    if (clustering != null && clustering.getClusteringColumns() != null) {
      // Use a proper JSON encoder rather than string-joining so nested column paths don't
      // collapse into dotted strings.
      props.put(
          TableProperties.DELTA_CLUSTERING_COLUMNS, toJson(clustering.getClusteringColumns()));
    }
    RowTrackingDomainMetadata rowTracking = domainMetadata.getDeltaRowTracking();
    if (rowTracking != null && rowTracking.getRowIdHighWaterMark() != null) {
      props.put(
          TableProperties.DELTA_ROW_TRACKING_ROW_ID_HIGH_WATER_MARK,
          String.valueOf(rowTracking.getRowIdHighWaterMark()));
    }
    return props;
  }

  /**
   * Validates that each declared domain-metadata entry is backed by the matching writer feature in
   * the protocol. A {@code delta.clustering} entry requires the {@code clustering} feature; a
   * {@code delta.rowTracking} entry requires the {@code rowTracking} feature. A null protocol or
   * null domain-metadata is permitted (caller-specific endpoints decide when either is mandatory).
   */
  public static void validateDomainMetadataAgainstProtocol(
      DeltaProtocol protocol, DomainMetadataUpdates domainMetadata) {
    if (domainMetadata == null) return;
    List<String> writerFeatures =
        protocol != null && protocol.getWriterFeatures() != null
            ? protocol.getWriterFeatures()
            : List.of();
    if (domainMetadata.getDeltaClustering() != null
        && !writerFeatures.contains(DeltaTableFeatures.CLUSTERING)) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT,
          "domain-metadata.delta.clustering requires the '"
              + DeltaTableFeatures.CLUSTERING
              + "' writer feature.");
    }
    if (domainMetadata.getDeltaRowTracking() != null
        && !writerFeatures.contains(DeltaTableFeatures.ROW_TRACKING)) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT,
          "domain-metadata.delta.rowTracking requires the '"
              + DeltaTableFeatures.ROW_TRACKING
              + "' writer feature.");
    }
  }

  private static void addFeatureProperties(Map<String, String> props, List<String> features) {
    if (features == null) return;
    for (String feature : features) {
      props.put(TableProperties.DELTA_FEATURE_PREFIX + feature, "supported");
    }
  }

  private static String toJson(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new BaseException(
          ErrorCode.INTERNAL, "Failed to encode domain-metadata as JSON: " + e.getMessage());
    }
  }
}
