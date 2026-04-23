package io.unitycatalog.server.service.delta;

/**
 * Delta protocol table-feature identifiers referenced by the Delta REST Catalog surface. These are
 * the spec-defined feature names that appear in {@code protocol.reader-features} and {@code
 * protocol.writer-features} and, via {@link DeltaPropertyMapper}, as {@code delta.feature.<name>}
 * entries on the stored UC property map.
 *
 * <p>Centralising them prevents typos at the points where the server makes feature-specific
 * decisions (e.g. "MANAGED tables must declare catalogManaged") from silently mismatching the
 * features the client actually wrote into the Delta log.
 */
public final class DeltaTableFeatures {

  private DeltaTableFeatures() {}

  /**
   * The writer feature that identifies a UC catalog-managed Delta table. UC MANAGED tables MUST
   * declare this; without it the client is claiming to manage commits independently, which
   * contradicts the MANAGED contract.
   */
  public static final String CATALOG_MANAGED = "catalogManaged";

  public static final String DELETION_VECTORS = "deletionVectors";
  public static final String V2_CHECKPOINT = "v2Checkpoint";
  public static final String VACUUM_PROTOCOL_CHECK = "vacuumProtocolCheck";
  public static final String IN_COMMIT_TIMESTAMP = "inCommitTimestamp";
  public static final String COLUMN_MAPPING = "columnMapping";
  public static final String DOMAIN_METADATA = "domainMetadata";
  public static final String ROW_TRACKING = "rowTracking";
  public static final String CLUSTERING = "clustering";
}
