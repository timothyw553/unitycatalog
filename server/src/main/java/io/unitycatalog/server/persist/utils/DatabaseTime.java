package io.unitycatalog.server.persist.utils;

import java.util.Date;
import org.hibernate.Session;

/** Supplies the database clock used by cross-server lifecycle transitions. */
public final class DatabaseTime {
  private DatabaseTime() {}

  public static Date now(Session session) {
    return session.doReturningWork(
        connection -> {
          String expression = switch (connection.getMetaData().getDatabaseProductName()) {
            // PostgreSQL CURRENT_TIMESTAMP is fixed at transaction start. A lifecycle transaction
            // may wait for a row lock, so use its statement-time clock instead.
            case "PostgreSQL" -> "clock_timestamp()";
            case "MySQL", "MariaDB" -> "CURRENT_TIMESTAMP(6)";
            default -> "CURRENT_TIMESTAMP";
          };
          try (var statement = connection.createStatement();
              var result = statement.executeQuery("SELECT " + expression)) {
            result.next();
            return new Date(result.getTimestamp(1).getTime());
          }
        });
  }
}
