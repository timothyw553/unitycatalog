package io.unitycatalog.server.auth;

import io.unitycatalog.server.model.SecurableType;
import io.unitycatalog.server.model.TableType;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.dao.CatalogInfoDAO;
import io.unitycatalog.server.persist.dao.CredentialDAO;
import io.unitycatalog.server.persist.dao.ExternalLocationDAO;
import io.unitycatalog.server.persist.dao.FunctionInfoDAO;
import io.unitycatalog.server.persist.dao.RegisteredModelInfoDAO;
import io.unitycatalog.server.persist.dao.SchemaInfoDAO;
import io.unitycatalog.server.persist.dao.StagingTableDAO;
import io.unitycatalog.server.persist.dao.TableInfoDAO;
import io.unitycatalog.server.persist.dao.VolumeInfoDAO;
import io.unitycatalog.server.persist.model.Privileges;
import io.unitycatalog.server.persist.utils.TransactionManager;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

/** Looks up a resource's securable kind before applying the privilege policy. */
final class PersistedResourcePrivilegeResolver implements ResourcePrivilegeResolver {
  private final SessionFactory sessionFactory;
  private final UUID metastoreId;

  PersistedResourcePrivilegeResolver(Repositories repositories, UUID metastoreId) {
    this(repositories.getSessionFactory(), metastoreId);
  }

  PersistedResourcePrivilegeResolver(SessionFactory sessionFactory, UUID metastoreId) {
    this.sessionFactory = sessionFactory;
    this.metastoreId = metastoreId;
  }

  @Override
  public boolean isAssignable(UUID resourceId, Privileges privilege) {
    ResolvedSecurable resource = resolve(resourceId);
    return resource != null
        && (resource.tableType() == null
            ? PrivilegePolicy.isAssignable(resource.securableType(), privilege)
            : PrivilegePolicy.isAssignable(resource.tableType(), privilege));
  }

  @Override
  public boolean isApplicable(UUID resourceId, Privileges privilege) {
    ResolvedSecurable resource = resolve(resourceId);
    return resource != null
        && (resource.tableType() == null
            ? PrivilegePolicy.isApplicable(resource.securableType(), privilege)
            : PrivilegePolicy.isApplicable(resource.tableType(), privilege));
  }

  private ResolvedSecurable resolve(UUID resourceId) {
    if (resourceId.equals(metastoreId)) {
      return new ResolvedSecurable(SecurableType.METASTORE, null);
    }
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> resolve(session, resourceId),
        "Failed to resolve securable type",
        /* readOnly = */ true);
  }

  private ResolvedSecurable resolve(Session session, UUID resourceId) {
    TableInfoDAO table = session.get(TableInfoDAO.class, resourceId);
    if (table != null) {
      return new ResolvedSecurable(SecurableType.TABLE, TableType.fromValue(table.getType()));
    }
    if (session.get(StagingTableDAO.class, resourceId) != null) {
      return new ResolvedSecurable(SecurableType.TABLE, TableType.MANAGED);
    }
    if (session.get(CatalogInfoDAO.class, resourceId) != null) {
      return new ResolvedSecurable(SecurableType.CATALOG, null);
    }
    if (session.get(SchemaInfoDAO.class, resourceId) != null) {
      return new ResolvedSecurable(SecurableType.SCHEMA, null);
    }
    if (session.get(FunctionInfoDAO.class, resourceId) != null) {
      return new ResolvedSecurable(SecurableType.FUNCTION, null);
    }
    if (session.get(VolumeInfoDAO.class, resourceId) != null) {
      return new ResolvedSecurable(SecurableType.VOLUME, null);
    }
    if (session.get(RegisteredModelInfoDAO.class, resourceId) != null) {
      return new ResolvedSecurable(SecurableType.REGISTERED_MODEL, null);
    }
    if (session.get(ExternalLocationDAO.class, resourceId) != null) {
      return new ResolvedSecurable(SecurableType.EXTERNAL_LOCATION, null);
    }
    if (session.get(CredentialDAO.class, resourceId) != null) {
      return new ResolvedSecurable(SecurableType.CREDENTIAL, null);
    }
    return null;
  }

  private record ResolvedSecurable(SecurableType securableType, TableType tableType) {}
}
