package io.unitycatalog.server.persist;

import io.unitycatalog.server.model.TableType;
import io.unitycatalog.server.persist.dao.DependencyDAO;
import io.unitycatalog.server.persist.dao.StagingTableDAO;
import io.unitycatalog.server.persist.dao.TableInfoDAO;
import io.unitycatalog.server.persist.utils.RepositoryUtils;
import io.unitycatalog.server.utils.Constants;
import org.hibernate.Session;

/** Removes every database row whose lifetime is owned by a table. */
final class TableMetadataPurger {
  private TableMetadataPurger() {}

  static void purge(Repositories repositories, Session session, TableInfoDAO table) {
    if (TableType.MANAGED.getValue().equals(table.getType())) {
      repositories.getDeltaCommitRepository().permanentlyDeleteTableCommits(session, table.getId());

      // Finalized staging rows share the table UUID. They must not become a credential fallback
      // after the table row is gone.
      StagingTableDAO stagingTable = session.get(StagingTableDAO.class, table.getId());
      if (stagingTable != null) {
        session.remove(stagingTable);
      }
    }
    if (RepositoryUtils.isViewLike(table.getType())) {
      repositories
          .getDependencyRepository()
          .deleteDependencies(session, table.getId(), DependencyDAO.DependentType.TABLE);
    }
    PropertyRepository.findProperties(session, table.getId(), Constants.TABLE)
        .forEach(session::remove);
    session.remove(table);
  }
}
