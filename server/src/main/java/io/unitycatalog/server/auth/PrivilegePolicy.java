package io.unitycatalog.server.auth;

import io.unitycatalog.server.model.SecurableType;
import io.unitycatalog.server.model.TableType;
import io.unitycatalog.server.persist.model.Privileges;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Defines which privileges are meaningful for each kind of securable. */
public final class PrivilegePolicy {

  private static final Set<Privileges> METASTORE_PRIVILEGES =
      Set.of(
          Privileges.CREATE_CATALOG,
          Privileges.CREATE_EXTERNAL_LOCATION,
          Privileges.CREATE_STORAGE_CREDENTIAL,
          Privileges.READ_METADATA);

  private static final Set<Privileges> CATALOG_PRIVILEGES =
      Set.of(
          Privileges.ALL_PRIVILEGES,
          Privileges.APPLY_TAG,
          Privileges.BROWSE,
          Privileges.CREATE_FUNCTION,
          Privileges.CREATE_MATERIALIZED_VIEW,
          Privileges.CREATE_MODEL,
          Privileges.CREATE_MODEL_VERSION,
          Privileges.CREATE_SCHEMA,
          Privileges.CREATE_TABLE,
          Privileges.CREATE_VOLUME,
          Privileges.EXECUTE,
          Privileges.EXTERNAL_USE_SCHEMA,
          Privileges.MANAGE,
          Privileges.MODIFY,
          Privileges.READ_METADATA,
          Privileges.READ_VOLUME,
          Privileges.REFRESH,
          Privileges.SELECT,
          Privileges.USE_CATALOG,
          Privileges.USE_SCHEMA,
          Privileges.WRITE_VOLUME);

  private static final Set<Privileges> SCHEMA_PRIVILEGES =
      Set.of(
          Privileges.ALL_PRIVILEGES,
          Privileges.APPLY_TAG,
          Privileges.CREATE_FUNCTION,
          Privileges.CREATE_MATERIALIZED_VIEW,
          Privileges.CREATE_MODEL,
          Privileges.CREATE_MODEL_VERSION,
          Privileges.CREATE_TABLE,
          Privileges.CREATE_VOLUME,
          Privileges.EXECUTE,
          Privileges.EXTERNAL_USE_SCHEMA,
          Privileges.MANAGE,
          Privileges.MODIFY,
          Privileges.READ_METADATA,
          Privileges.READ_VOLUME,
          Privileges.REFRESH,
          Privileges.SELECT,
          Privileges.USE_SCHEMA,
          Privileges.WRITE_VOLUME);

  private static final Set<Privileges> TABLE_COMMON_PRIVILEGES =
      Set.of(
          Privileges.ALL_PRIVILEGES,
          Privileges.APPLY_TAG,
          Privileges.MANAGE,
          Privileges.READ_METADATA,
          Privileges.SELECT);

  private static final Map<SecurableType, Set<Privileges>> PRIVILEGES_BY_SECURABLE =
      createPrivilegesBySecurable();

  private PrivilegePolicy() {}

  private static Map<SecurableType, Set<Privileges>> createPrivilegesBySecurable() {
    EnumMap<SecurableType, Set<Privileges>> privileges = new EnumMap<>(SecurableType.class);
    privileges.put(SecurableType.METASTORE, METASTORE_PRIVILEGES);
    privileges.put(SecurableType.CATALOG, CATALOG_PRIVILEGES);
    privileges.put(SecurableType.SCHEMA, SCHEMA_PRIVILEGES);
    privileges.put(
        SecurableType.FUNCTION,
        Set.of(
            Privileges.ALL_PRIVILEGES,
            Privileges.APPLY_TAG,
            Privileges.EXECUTE,
            Privileges.MANAGE,
            Privileges.READ_METADATA));
    privileges.put(
        SecurableType.VOLUME,
        Set.of(
            Privileges.ALL_PRIVILEGES,
            Privileges.APPLY_TAG,
            Privileges.MANAGE,
            Privileges.READ_METADATA,
            Privileges.READ_VOLUME,
            Privileges.WRITE_VOLUME));
    privileges.put(
        SecurableType.REGISTERED_MODEL,
        Set.of(
            Privileges.ALL_PRIVILEGES,
            Privileges.APPLY_TAG,
            Privileges.CREATE_MODEL_VERSION,
            Privileges.EXECUTE,
            Privileges.MANAGE,
            Privileges.READ_METADATA));
    privileges.put(
        SecurableType.EXTERNAL_LOCATION,
        Set.of(
            Privileges.ALL_PRIVILEGES,
            Privileges.APPLY_TAG,
            Privileges.BROWSE,
            Privileges.CREATE_EXTERNAL_TABLE,
            Privileges.CREATE_EXTERNAL_VOLUME,
            Privileges.CREATE_MANAGED_STORAGE,
            Privileges.EXTERNAL_USE_LOCATION,
            Privileges.MANAGE,
            Privileges.READ_FILES,
            Privileges.READ_METADATA,
            Privileges.WRITE_FILES));
    privileges.put(
        SecurableType.CREDENTIAL,
        Set.of(
            Privileges.ALL_PRIVILEGES,
            Privileges.APPLY_TAG,
            Privileges.CREATE_EXTERNAL_LOCATION,
            Privileges.CREATE_EXTERNAL_TABLE,
            Privileges.MANAGE,
            Privileges.READ_FILES,
            Privileges.READ_METADATA,
            Privileges.WRITE_FILES));
    return Map.copyOf(privileges);
  }

  /** Returns whether the privilege may be assigned to the securable type. */
  public static boolean isAssignable(SecurableType securableType, Privileges privilege) {
    if (securableType == SecurableType.TABLE) {
      return TABLE_COMMON_PRIVILEGES.contains(privilege)
          || privilege == Privileges.MODIFY
          || privilege == Privileges.REFRESH;
    }
    return PRIVILEGES_BY_SECURABLE.getOrDefault(securableType, Set.of()).contains(privilege);
  }

  /** Returns whether the privilege can be effective on the securable type. */
  public static boolean isApplicable(SecurableType securableType, Privileges privilege) {
    return isAssignable(securableType, privilege)
        || (privilege == Privileges.BROWSE
            && Set.of(
                    SecurableType.SCHEMA,
                    SecurableType.TABLE,
                    SecurableType.FUNCTION,
                    SecurableType.VOLUME,
                    SecurableType.REGISTERED_MODEL)
                .contains(securableType));
  }

  /** Returns all privileges that may be assigned directly to the securable type. */
  public static Set<Privileges> assignablePrivileges(SecurableType securableType) {
    if (securableType == SecurableType.TABLE) {
      Set<Privileges> privileges = new HashSet<>(TABLE_COMMON_PRIVILEGES);
      privileges.add(Privileges.MODIFY);
      privileges.add(Privileges.REFRESH);
      return Set.copyOf(privileges);
    }
    return PRIVILEGES_BY_SECURABLE.getOrDefault(securableType, Set.of());
  }

  /** Returns whether the privilege may be assigned to this specific table subtype. */
  public static boolean isAssignable(TableType tableType, Privileges privilege) {
    if (TABLE_COMMON_PRIVILEGES.contains(privilege)) {
      return true;
    }
    return switch (tableType) {
      case MANAGED, EXTERNAL, STREAMING_TABLE -> privilege == Privileges.MODIFY;
      case MATERIALIZED_VIEW -> privilege == Privileges.REFRESH;
      default -> false;
    };
  }

  /** Returns whether the privilege can be effective on this specific table subtype. */
  public static boolean isApplicable(TableType tableType, Privileges privilege) {
    return privilege == Privileges.BROWSE || isAssignable(tableType, privilege);
  }

  /** Returns all privileges that may be assigned directly to this table subtype. */
  public static Set<Privileges> assignablePrivileges(TableType tableType) {
    Set<Privileges> privileges = new HashSet<>(TABLE_COMMON_PRIVILEGES);
    switch (tableType) {
      case MANAGED, EXTERNAL, STREAMING_TABLE -> privileges.add(Privileges.MODIFY);
      case MATERIALIZED_VIEW -> privileges.add(Privileges.REFRESH);
      default -> {
        // Views and other non-data table subtypes only use the common table privileges.
      }
    }
    return Set.copyOf(privileges);
  }

  /** Returns whether an ALL PRIVILEGES grant satisfies a check for the requested privilege. */
  public static boolean isCoveredByAllPrivileges(Privileges requestedPrivilege) {
    return switch (requestedPrivilege) {
      case OWNER,
          ALL_PRIVILEGES,
          MANAGE,
          READ_METADATA,
          EXTERNAL_USE_SCHEMA,
          EXTERNAL_USE_LOCATION -> false;
      default -> true;
    };
  }

  /** Returns whether a MANAGE grant satisfies a check for the requested privilege. */
  public static boolean isCoveredByManage(Privileges requestedPrivilege) {
    return requestedPrivilege == Privileges.MANAGE
        || requestedPrivilege == Privileges.READ_METADATA;
  }
}
