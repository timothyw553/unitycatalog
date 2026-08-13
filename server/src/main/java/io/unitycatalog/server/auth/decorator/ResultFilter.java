package io.unitycatalog.server.auth.decorator;

import io.unitycatalog.server.auth.annotation.ResponseAuthorizeFilter;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.model.CatalogInfo;
import io.unitycatalog.server.model.CredentialInfo;
import io.unitycatalog.server.model.ExternalLocationInfo;
import io.unitycatalog.server.model.FunctionInfo;
import io.unitycatalog.server.model.RegisteredModelInfo;
import io.unitycatalog.server.model.SchemaInfo;
import io.unitycatalog.server.model.SecurableType;
import io.unitycatalog.server.model.TableInfo;
import io.unitycatalog.server.model.VolumeInfo;
import io.unitycatalog.server.persist.model.Privileges;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import io.unitycatalog.server.service.AuthorizedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.unitycatalog.server.model.SecurableType.CATALOG;
import static io.unitycatalog.server.model.SecurableType.REGISTERED_MODEL;
import static io.unitycatalog.server.model.SecurableType.SCHEMA;

/**
 * Filters response objects based on user authorization permissions.
 *
 * <p>This class is used in the authorization decorator layer to filter service responses. When a
 * service method is annotated with {@code @ResponseAuthorizeFilter}, the {@link
 * UnityAccessDecorator} creates a ResultFilter instance and stores it in the request context. The
 * service method then retrieves this filter and applies it to a list or single response object.
 *
 * <p><b>How It Works:</b>
 *
 * <ol>
 *   <li>UnityAccessDecorator intercepts HTTP requests and extracts authorization parameters
 *       (principal ID, expression, resource IDs) from method annotations
 *   <li>It creates a ResultFilter with these parameters and stores it in the request context
 *   <li>The service method retrieves the filter via {@code applyResponseFilter()} and applies it to
 *       the response
 *   <li>The filter returns full objects, projects browse-only metadata, and removes hidden items
 * </ol>
 *
 * <p><b>Resource ID Resolution:</b><br>
 * For each item in the list, the filter resolves resource IDs of items but expect the catalog and
 * schema IDs to be provided in the request already. Special handling exists for REGISTERED_MODEL
 * because models can be listed without specifying catalog/schema filters. In this case, the filter
 * extracts the catalog and schema names from each model and resolves them to IDs.
 *
 * <p><b>Security:</b><br>
 * The {@code wasCalled()} method allows UnityAccessDecorator to verify that the filter was actually
 * used. If a method is annotated with {@code @ResponseAuthorizeFilter} but doesn't call the filter,
 * a security exception is thrown to prevent data leakage.
 *
 * @see ResponseAuthorizeFilter
 * @see UnityAccessDecorator
 * @see AuthorizedService#applyResponseFilter
 */
public class ResultFilter {
  private static final Logger LOGGER = LoggerFactory.getLogger(ResultFilter.class);
  private static final String INCLUDE_BROWSE = "include_browse";
  private static final String ACCESS_DENIED = "Access denied.";

  private enum AccessLevel {
    FULL,
    BROWSE_ONLY,
    HIDDEN
  }

  private final UUID principalId;
  private final String expression;
  private final UnityAccessEvaluator evaluator;
  private final Map<SecurableType, UUID> resourceIds;
  private final Map<String, Object> nonResourceValues;
  private final KeyMapper keyMapper;
  private final AtomicBoolean called;

  public ResultFilter(
      UnityAccessEvaluator evaluator,
      UUID principalId,
      String expression,
      Map<SecurableType, UUID> resourceIds,
      Map<String, Object> nonResourceValues,
      KeyMapper keyMapper) {
    this.principalId = principalId;
    this.expression = expression;
    this.evaluator = evaluator;
    this.resourceIds = resourceIds;
    this.nonResourceValues = nonResourceValues;
    this.keyMapper = keyMapper;
    this.called = new AtomicBoolean(false);
  }

  /** Filters a list in place, retaining full objects and projected browse-only objects. */
  public <T> void filter(SecurableType securableType, List<T> items) {
    called.set(true);

    if (items == null || items.isEmpty()) {
      return;
    }

    LOGGER.debug("Filtering {} items with authorization expression", items.size());
    if (resourceIds.containsKey(securableType)) {
      // This simply indicates a bug in code.
      throw new RuntimeException("Securable type " + securableType + " is already resolved");
    }

    items.removeIf(item -> accessLevel(securableType, item) == AccessLevel.HIDDEN);

    LOGGER.debug("After filtering: {} items remain", items.size());
  }

  /**
   * Applies the same authorization and browse projection as list filtering to one object.
   *
   * <p>This supports GET operations, whose resource ID is normally already resolved by the request
   * decorator. A mismatch between that ID and the returned object's ID fails closed.
   */
  public <T> T filterSingle(SecurableType securableType, T item) {
    called.set(true);
    if (item == null || accessLevel(securableType, item) == AccessLevel.HIDDEN) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, ACCESS_DENIED);
    }
    return item;
  }

  private AccessLevel accessLevel(SecurableType securableType, Object item) {
    try {
      Map<SecurableType, UUID> resourceIdsForItem =
          resolveResourceIdsForItem(securableType, item, resourceIds);
      if (evaluator.evaluate(principalId, expression, resourceIdsForItem, nonResourceValues)) {
        setBrowseOnly(securableType, item, false);
        return AccessLevel.FULL;
      }

      UUID resourceId = resourceIdsForItem.get(securableType);
      if (includeBrowse()
          && supportsBrowse(securableType)
          && evaluator.authorize(principalId, resourceId, Privileges.BROWSE)) {
        setBrowseOnly(securableType, item, true);
        return AccessLevel.BROWSE_ONLY;
      }

      LOGGER.debug("Item filtered out: {}", item.getClass().getSimpleName());
      return AccessLevel.HIDDEN;
    } catch (Exception e) {
      LOGGER.warn("Error evaluating authorization for item, filtering out: {}", e.getMessage());
      return AccessLevel.HIDDEN;
    }
  }

  private boolean includeBrowse() {
    Object value = nonResourceValues.get(INCLUDE_BROWSE);
    return value instanceof Boolean
        ? (Boolean) value
        : value instanceof String && Boolean.parseBoolean((String) value);
  }

  private boolean supportsBrowse(SecurableType securableType) {
    return switch (securableType) {
      case CATALOG, SCHEMA, TABLE, FUNCTION, VOLUME, REGISTERED_MODEL, EXTERNAL_LOCATION -> true;
      default -> false;
    };
  }

  private void setBrowseOnly(SecurableType securableType, Object item, boolean browseOnly) {
    switch (securableType) {
      case CATALOG -> ((CatalogInfo) item).setBrowseOnly(browseOnly);
      case SCHEMA -> ((SchemaInfo) item).setBrowseOnly(browseOnly);
      case TABLE -> {
        TableInfo table = (TableInfo) item;
        table.setBrowseOnly(browseOnly);
        if (browseOnly) {
          table.setStorageLocation(null);
          table.setProperties(null);
          table.setViewDefinition(null);
          table.setViewDependencies(null);
        }
      }
      case FUNCTION -> {
        FunctionInfo function = (FunctionInfo) item;
        function.setBrowseOnly(browseOnly);
        if (browseOnly) {
          function.setRoutineDefinition(null);
        }
      }
      case VOLUME -> ((VolumeInfo) item).setBrowseOnly(browseOnly);
      case REGISTERED_MODEL -> ((RegisteredModelInfo) item).setBrowseOnly(browseOnly);
      case EXTERNAL_LOCATION -> {
        ExternalLocationInfo externalLocation = (ExternalLocationInfo) item;
        externalLocation.setBrowseOnly(browseOnly);
        if (browseOnly) {
          externalLocation.setCredentialName(null);
          externalLocation.setCredentialId(null);
        }
      }
      default -> {
        // Other securables do not expose browse-only metadata.
      }
    }
  }

  private Map<SecurableType, UUID> resolveResourceIdsForItem(
      SecurableType securableType, Object item, Map<SecurableType, UUID> preResolvedIds) {
    // First, resolve the item's own resource ID
    UUID itemId = resolveResourceId(securableType, item);
    Map<SecurableType, UUID> combined = new HashMap<>(preResolvedIds);
    UUID preResolvedItemId = preResolvedIds.get(securableType);
    if (preResolvedItemId != null && !preResolvedItemId.equals(itemId)) {
      throw new IllegalArgumentException("Returned resource does not match the requested resource");
    }
    combined.put(securableType, itemId);
    // For REGISTERED_MODEL, resolve catalog and schema if both are not present.
    if (securableType == REGISTERED_MODEL
        && !preResolvedIds.containsKey(CATALOG)
        && !preResolvedIds.containsKey(SCHEMA)) {
      RegisteredModelInfo model = (RegisteredModelInfo) item;
      combined.putAll(
          keyMapper.mapResourceKeys(
              Map.of(CATALOG, model.getCatalogName(), SCHEMA, model.getSchemaName())));
    }
    // For everything else, the catalog and schema IDs are already included in preResolvedIds
    // if needed.
    return combined;
  }

  private UUID resolveResourceId(SecurableType securableType, Object item) {
    String id = switch (securableType) {
      case TABLE -> ((TableInfo)item).getTableId();
      case VOLUME -> ((VolumeInfo)item).getVolumeId();
      case FUNCTION -> ((FunctionInfo)item).getFunctionId();
      case REGISTERED_MODEL -> ((RegisteredModelInfo)item).getId();
      case CATALOG -> ((CatalogInfo)item).getId();
      case SCHEMA -> ((SchemaInfo)item).getSchemaId();
      case CREDENTIAL -> ((CredentialInfo)item).getId();
      case EXTERNAL_LOCATION -> ((ExternalLocationInfo)item).getId();
      default -> throw new RuntimeException("Unsupported securable type: " + securableType);
    };
    return UUID.fromString(id);
  }

  /**
   * Returns whether the filter has been called.
   *
   * <p>This method is used by {@link UnityAccessDecorator} to verify that methods annotated with
   * {@code @ResponseAuthorizeFilter} actually call the filter on successful responses. This is a
   * security enforcement mechanism to prevent data leakage when developers forget to filter
   * results.
   *
   * @return true if {@link #filter} or {@link #filterSingle} has been called at least once, false
   *     otherwise
   */
  public boolean wasCalled() {
    return called.get();
  }
}
