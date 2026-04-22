package io.unitycatalog.server.service.delta;

import io.unitycatalog.server.delta.model.CredentialOperation;
import io.unitycatalog.server.delta.model.StagingTableResponse;
import io.unitycatalog.server.delta.model.StagingTableResponseRequiredProtocol;
import io.unitycatalog.server.delta.model.StagingTableResponseSuggestedProtocol;
import io.unitycatalog.server.delta.model.TableType;
import io.unitycatalog.server.model.StagingTableInfo;
import io.unitycatalog.server.model.TemporaryCredentials;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Builds the Delta REST Catalog {@link StagingTableResponse} from the UC {@link StagingTableInfo}
 * plus freshly-vended cloud credentials.
 *
 * <p>The required / suggested protocol and properties are the UC catalog-managed Delta contract
 * that external writers must honor when writing the initial commit:
 *
 * <ul>
 *   <li>Required features / properties are non-negotiable: the initial commit must enable each
 *       required feature and set each required property to the specified value. {@code
 *       catalogManaged} is the defining invariant; the rest follow from running a modern Delta
 *       protocol with catalog-managed commits.
 *   <li>Suggested features / properties are strong recommendations; clients should enable them if
 *       they can but won't be rejected if they can't.
 *   <li>Properties with a {@code null} value mean "client generates the value at commit time" (e.g.
 *       the row-tracking materialized column names must have a random UUID suffix).
 * </ul>
 */
public final class DeltaStagingTableMapper {

  private DeltaStagingTableMapper() {}

  // Reader version 3 + writer version 7 are required to use table features.
  private static final int REQUIRED_MIN_READER_VERSION = 3;
  private static final int REQUIRED_MIN_WRITER_VERSION = 7;

  // Required features for a UC catalog-managed Delta table, grouped by their reader/writer
  // classification in delta-io TableFeature.scala. A reader-writer feature must appear in BOTH
  // the reader-features and writer-features lists on the wire; a writer-only feature appears
  // only in writer-features.
  private static final List<String> REQUIRED_READER_WRITER_FEATURES =
      List.of(
          DeltaTableFeatures.CATALOG_MANAGED,
          DeltaTableFeatures.DELETION_VECTORS,
          DeltaTableFeatures.V2_CHECKPOINT,
          DeltaTableFeatures.VACUUM_PROTOCOL_CHECK);
  private static final List<String> REQUIRED_WRITER_ONLY_FEATURES =
      List.of(DeltaTableFeatures.IN_COMMIT_TIMESTAMP);
  private static final List<String> REQUIRED_WRITER_FEATURES =
      concat(REQUIRED_READER_WRITER_FEATURES, REQUIRED_WRITER_ONLY_FEATURES);

  // Suggested features, grouped the same way. domainMetadata is conditionally required with
  // rowTracking but is advertised as suggested so clients that enable row tracking enable it too.
  private static final List<String> SUGGESTED_READER_WRITER_FEATURES =
      List.of(DeltaTableFeatures.COLUMN_MAPPING);
  private static final List<String> SUGGESTED_WRITER_ONLY_FEATURES =
      List.of(DeltaTableFeatures.DOMAIN_METADATA, DeltaTableFeatures.ROW_TRACKING);
  private static final List<String> SUGGESTED_WRITER_FEATURES =
      concat(SUGGESTED_READER_WRITER_FEATURES, SUGGESTED_WRITER_ONLY_FEATURES);

  // Required properties with fixed values. io.unitycatalog.tableId is filled in per-request from
  // the UC-allocated table UUID.
  private static final Map<String, String> BASE_REQUIRED_PROPERTIES =
      Map.of(
          "delta.checkpointPolicy", "v2",
          "delta.checkpoint.writeStatsAsJson", "false",
          "delta.checkpoint.writeStatsAsStruct", "true",
          "delta.enableDeletionVectors", "true",
          "delta.enableInCommitTimestamps", "true");

  // Required properties that the engine must compute at commit time. Null value here means
  // "must be present in the initial commit, UC does not constrain the value" per the
  // StagingTableResponse spec. Both are engine-managed and tied to the inCommitTimestamp feature.
  private static final List<String> ENGINE_GENERATED_REQUIRED_PROPERTY_KEYS =
      List.of(
          "delta.inCommitTimestampEnablementVersion", "delta.inCommitTimestampEnablementTimestamp");

  // Suggested properties. Null values mean the client generates the value at commit time
  // (required when the corresponding feature is enabled). Built via a helper because Map.of
  // rejects null values; wrapped unmodifiable so we can share the same instance across all
  // responses without risking mutation by downstream callers.
  private static final Map<String, String> SUGGESTED_PROPERTIES = buildSuggestedProperties();

  private static Map<String, String> buildSuggestedProperties() {
    Map<String, String> props = new HashMap<>();
    props.put("delta.enableRowTracking", "true");
    props.put("delta.rowTracking.materializedRowIdColumnName", null);
    props.put("delta.rowTracking.materializedRowCommitVersionColumnName", null);
    return Collections.unmodifiableMap(props);
  }

  /** Builds a {@link StagingTableResponse} from a freshly-created staging table + credentials. */
  public static StagingTableResponse toStagingTableResponse(
      StagingTableInfo info, TemporaryCredentials credentials) {
    var creds =
        DeltaCredentialsMapper.toCredentialsResponse(
            info.getStagingLocation(), credentials, CredentialOperation.READ_WRITE);

    Map<String, String> requiredProperties = new HashMap<>(BASE_REQUIRED_PROPERTIES);
    // The rule-based property binds the Delta table to the UC-allocated UUID.
    requiredProperties.put("io.unitycatalog.tableId", info.getId());
    for (String key : ENGINE_GENERATED_REQUIRED_PROPERTY_KEYS) {
      requiredProperties.put(key, null);
    }

    return new StagingTableResponse()
        .tableId(UUID.fromString(info.getId()))
        .tableType(TableType.MANAGED)
        .location(info.getStagingLocation())
        .storageCredentials(creds.getStorageCredentials())
        .requiredProtocol(
            new StagingTableResponseRequiredProtocol()
                .minReaderVersion(REQUIRED_MIN_READER_VERSION)
                .minWriterVersion(REQUIRED_MIN_WRITER_VERSION)
                .readerFeatures(REQUIRED_READER_WRITER_FEATURES)
                .writerFeatures(REQUIRED_WRITER_FEATURES))
        .suggestedProtocol(
            new StagingTableResponseSuggestedProtocol()
                .readerFeatures(SUGGESTED_READER_WRITER_FEATURES)
                .writerFeatures(SUGGESTED_WRITER_FEATURES))
        .requiredProperties(requiredProperties)
        .suggestedProperties(SUGGESTED_PROPERTIES);
  }

  private static List<String> concat(List<String> readerWriterFeatures, List<String> writerOnly) {
    return Stream.concat(readerWriterFeatures.stream(), writerOnly.stream()).toList();
  }
}
