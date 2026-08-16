package io.unitycatalog.server.persist.dao;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.Date;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Durable work item for deleting one managed table's storage. */
@Entity
@Table(
    name = "uc_table_cleanup_tasks",
    indexes = {@Index(name = "idx_table_cleanup_ready", columnList = "next_attempt_at")})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableCleanupTaskDAO {
  /** The table UUID is also the task's idempotency key. */
  @Id
  @Column(name = "table_id", nullable = false)
  private UUID tableId;

  @Column(name = "schema_id", nullable = false)
  private UUID schemaId;

  @Column(name = "storage_location", nullable = false, length = 2048)
  private String storageLocation;

  @Column(name = "created_at", nullable = false)
  private Date createdAt;

  @Column(name = "next_attempt_at", nullable = false)
  private Date nextAttemptAt;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;
}
