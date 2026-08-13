package io.unitycatalog.server.auth;

import io.unitycatalog.server.persist.model.Privileges;
import java.util.UUID;

/** Resolves the privileges that are valid for a persisted securable. */
interface ResourcePrivilegeResolver {
  boolean isAssignable(UUID resourceId, Privileges privilege);

  boolean isApplicable(UUID resourceId, Privileges privilege);
}
