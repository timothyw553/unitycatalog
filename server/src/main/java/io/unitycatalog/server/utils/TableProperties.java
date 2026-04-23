package io.unitycatalog.server.utils;

public class TableProperties {
  /** Key for identifying Unity Catalog table ID in Delta commits. */
  public static final String UC_TABLE_ID_KEY = "io.unitycatalog.tableId";

  /** Last metadata-changing commit version (delta.lastUpdateVersion). */
  public static final String LAST_UPDATE_VERSION = "delta.lastUpdateVersion";

  /** Timestamp of the last metadata-changing commit (delta.lastCommitTimestamp). */
  public static final String LAST_COMMIT_TIMESTAMP = "delta.lastCommitTimestamp";

  /**
   * Prefix for per-feature properties written by the engine for every feature declared in the
   * protocol. Projection is {@code delta.feature.<name> = supported}; the suffix is the feature
   * name as it appears in {@code protocol.reader-features} / {@code protocol.writer-features}.
   */
  public static final String DELTA_FEATURE_PREFIX = "delta.feature.";

  /**
   * Clustering columns, written as a JSON-encoded list of column paths (each path itself a list of
   * segment names, so nested columns stay as arrays rather than collapsing to dotted strings).
   * Mirrors the {@code delta.clustering} domain-metadata entry.
   */
  public static final String DELTA_CLUSTERING_COLUMNS = "delta.clusteringColumns";

  /**
   * Row-tracking high water mark, mirroring the {@code delta.rowTracking.rowIdHighWaterMark} from
   * the {@code delta.rowTracking} domain-metadata entry.
   */
  public static final String DELTA_ROW_TRACKING_ROW_ID_HIGH_WATER_MARK =
      "delta.rowTracking.rowIdHighWaterMark";
}
