package io.unitycatalog.server.service;

import static io.unitycatalog.server.model.SecurableType.CATALOG;
import static io.unitycatalog.server.model.SecurableType.CREDENTIAL;
import static io.unitycatalog.server.model.SecurableType.EXTERNAL_LOCATION;
import static io.unitycatalog.server.model.SecurableType.FUNCTION;
import static io.unitycatalog.server.model.SecurableType.METASTORE;
import static io.unitycatalog.server.model.SecurableType.REGISTERED_MODEL;
import static io.unitycatalog.server.model.SecurableType.SCHEMA;
import static io.unitycatalog.server.model.SecurableType.TABLE;
import static io.unitycatalog.server.model.SecurableType.VOLUME;

import io.unitycatalog.control.model.User;
import io.unitycatalog.server.auth.AuthorizeExpressions;
import io.unitycatalog.server.auth.PrivilegePolicy;
import io.unitycatalog.server.auth.UnityCatalogAuthorizer;
import io.unitycatalog.server.auth.annotation.AuthorizeExpression;
import io.unitycatalog.server.auth.annotation.AuthorizeResourceKey;
import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.exception.GlobalExceptionHandler;
import io.unitycatalog.server.model.PermissionsChange;
import io.unitycatalog.server.model.PermissionsList;
import io.unitycatalog.server.model.Privilege;
import io.unitycatalog.server.model.PrivilegeAssignment;
import io.unitycatalog.server.model.SecurableType;
import io.unitycatalog.server.model.UpdatePermissions;
import io.unitycatalog.server.persist.CatalogRepository;
import io.unitycatalog.server.persist.CredentialRepository;
import io.unitycatalog.server.persist.ExternalLocationRepository;
import io.unitycatalog.server.persist.FunctionRepository;
import io.unitycatalog.server.persist.MetastoreRepository;
import io.unitycatalog.server.persist.ModelRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.SchemaRepository;
import io.unitycatalog.server.persist.TableRepository;
import io.unitycatalog.server.persist.UserRepository;
import io.unitycatalog.server.persist.VolumeRepository;
import io.unitycatalog.server.persist.model.Privileges;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Patch;

@ExceptionHandler(GlobalExceptionHandler.class)
public class PermissionService {

  private final UnityCatalogAuthorizer authorizer;
  private final MetastoreRepository metastoreRepository;
  private final UserRepository userRepository;
  private final CatalogRepository catalogRepository;
  private final SchemaRepository schemaRepository;
  private final TableRepository tableRepository;
  private final FunctionRepository functionRepository;
  private final VolumeRepository volumeRepository;
  private final ModelRepository modelRepository;
  private final ExternalLocationRepository externalLocationRepository;
  private final CredentialRepository credentialRepository;

  public PermissionService(UnityCatalogAuthorizer authorizer, Repositories repositories) {
    this.authorizer = authorizer;
    this.metastoreRepository = repositories.getMetastoreRepository();
    this.userRepository = repositories.getUserRepository();
    this.catalogRepository = repositories.getCatalogRepository();
    this.schemaRepository = repositories.getSchemaRepository();
    this.tableRepository = repositories.getTableRepository();
    this.functionRepository = repositories.getFunctionRepository();
    this.volumeRepository = repositories.getVolumeRepository();
    this.modelRepository = repositories.getModelRepository();
    this.externalLocationRepository = repositories.getExternalLocationRepository();
    this.credentialRepository = repositories.getCredentialRepository();
  }

  // TODO: Refactor these endpoints to use a common method with dynamic resource id lookup
  @Get("/metastore/{name}")
  @AuthorizeExpression(AuthorizeExpressions.GET_RESOURCE_AUTHORIZATION)
  public HttpResponse getMetastoreAuthorization(
      @Param("name") String name) {
    return getAuthorization(METASTORE, name);
  }

  @Get("/catalog/{name}")
  @AuthorizeExpression(AuthorizeExpressions.GET_RESOURCE_AUTHORIZATION)
  public HttpResponse getCatalogAuthorization(
      @Param("name") String name) {
    return getAuthorization(CATALOG, name);
  }

  @Get("/schema/{name}")
  @AuthorizeExpression(AuthorizeExpressions.GET_RESOURCE_AUTHORIZATION)
  public HttpResponse getSchemaAuthorization(
      @Param("name") String name) {
    return getAuthorization(SCHEMA, name);
  }

  @Get("/table/{name}")
  @AuthorizeExpression(AuthorizeExpressions.GET_RESOURCE_AUTHORIZATION)
  public HttpResponse getTableAuthorization(
      @Param("name") String name) {
    return getAuthorization(TABLE, name);
  }

  @Get("/function/{name}")
  @AuthorizeExpression(AuthorizeExpressions.GET_RESOURCE_AUTHORIZATION)
  public HttpResponse getFunctionAuthorization(
      @Param("name") String name) {
    return getAuthorization(FUNCTION, name);
  }

  @Get("/volume/{name}")
  @AuthorizeExpression(AuthorizeExpressions.GET_RESOURCE_AUTHORIZATION)
  public HttpResponse getVolumeAuthorization(
      @Param("name") String name) {
    return getAuthorization(VOLUME, name);
  }

  @Get("/registered_model/{name}")
  @AuthorizeExpression(AuthorizeExpressions.GET_RESOURCE_AUTHORIZATION)
  public HttpResponse getRegisteredModelAuthorization(
      @Param("name") String name) {
    return getAuthorization(REGISTERED_MODEL, name);
  }

  @Get("/external_location/{name}")
  @AuthorizeExpression(AuthorizeExpressions.GET_RESOURCE_AUTHORIZATION)
  public HttpResponse getExternalLocationAuthorization(
      @Param("name") String name) {
    return getAuthorization(EXTERNAL_LOCATION, name);
  }

  @Get("/credential/{name}")
  @AuthorizeExpression(AuthorizeExpressions.GET_RESOURCE_AUTHORIZATION)
  public HttpResponse getCredentialAuthorization(
      @Param("name") String name) {
    return getAuthorization(CREDENTIAL, name);
  }

  private HttpResponse getAuthorization(
      SecurableType securableType, String name) {
    UUID resourceId = getResourceId(securableType, name);
    UUID principalId = userRepository.findPrincipalId();

    Map<UUID, List<Privileges>> authorizations =
        canReadAllPermissions(securableType, principalId, resourceId)
            ? authorizer.listAuthorizations(resourceId)
            : Map.of(principalId, authorizer.listAuthorizations(principalId, resourceId));

    List<PrivilegeAssignment> privilegeAssignments =
        authorizations.entrySet().stream()
            .map(
                entry -> {
                  List<Privilege> privileges = toApiPrivileges(entry.getValue());
                  return new PrivilegeAssignment()
                      .principal(userRepository.getUser(entry.getKey().toString()).getEmail())
                      .privileges(privileges);
                })
            .filter(assignment -> !assignment.getPrivileges().isEmpty())
            .collect(Collectors.toList());

    return HttpResponse.ofJson(new PermissionsList().privilegeAssignments(privilegeAssignments));
  }

  /**
   * Returns whether a principal may inspect every grant on a resource.
   *
   * <p>The authorizer deliberately supports inherited privileges, but permission reads also need to
   * know which ancestor supplied an administrative privilege. A catalog-level administrative
   * grant needs no {@code USE} privilege. A schema-level grant needs {@code USE_CATALOG}, and a
   * leaf-level grant needs both {@code USE_CATALOG} and {@code USE_SCHEMA}.
   */
  private boolean canReadAllPermissions(
      SecurableType securableType, UUID principalId, UUID resourceId) {
    UUID metastoreId = metastoreRepository.getMetastoreId();
    if (hasDirectReadAdministrativePrivilege(principalId, metastoreId)) {
      return true;
    }

    return switch (securableType) {
      case METASTORE -> false;
      case CATALOG, EXTERNAL_LOCATION, CREDENTIAL ->
          hasDirectReadAdministrativePrivilege(principalId, resourceId);
      case SCHEMA -> {
        UUID catalogId = authorizer.getHierarchyParent(resourceId);
        yield catalogId != null
            && (hasDirectReadAdministrativePrivilege(principalId, catalogId)
                || (authorizer.authorize(
                        principalId, catalogId, Privileges.USE_CATALOG)
                    && hasDirectReadAdministrativePrivilege(principalId, resourceId)));
      }
      case TABLE, FUNCTION, VOLUME, REGISTERED_MODEL -> {
        UUID schemaId = authorizer.getHierarchyParent(resourceId);
        UUID catalogId = schemaId == null ? null : authorizer.getHierarchyParent(schemaId);
        yield catalogId != null
            && (hasDirectReadAdministrativePrivilege(principalId, catalogId)
                || (authorizer.authorize(
                        principalId, catalogId, Privileges.USE_CATALOG)
                    && (hasDirectReadAdministrativePrivilege(principalId, schemaId)
                        || (authorizer.authorize(
                                principalId, schemaId, Privileges.USE_SCHEMA)
                            && hasDirectReadAdministrativePrivilege(
                                principalId, resourceId)))));
      }
      default -> false;
    };
  }

  private boolean hasDirectReadAdministrativePrivilege(UUID principalId, UUID resourceId) {
    List<Privileges> privileges = authorizer.listAuthorizations(principalId, resourceId);
    return privileges.contains(Privileges.OWNER)
        || privileges.contains(Privileges.MANAGE)
        || privileges.contains(Privileges.READ_METADATA);
  }

  // TODO: Refactor these endpoints to use a common method with dynamic resource id lookup
  @Patch("/metastore/{name}")
  @AuthorizeExpression("#authorize(#principal, #metastore, OWNER)")
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse updateMetastoreAuthorization(
      @Param("name") String name, UpdatePermissions request) {
    return updateAuthorization(METASTORE, name, request);
  }

  @Patch("/catalog/{name}")
  @AuthorizeExpression("""
      #authorize(#principal, #metastore, OWNER) ||
      #authorizeAny(#principal, #catalog, OWNER, MANAGE)
      """)
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse updateCatalogAuthorization(
      @Param("name") @AuthorizeResourceKey(CATALOG) String name, UpdatePermissions request) {
    return updateAuthorization(CATALOG, name, request);
  }

  @Patch("/schema/{name}")
  @AuthorizeExpression("""
      #authorize(#principal, #metastore, OWNER) ||
      #authorizeAny(#principal, #catalog, OWNER, MANAGE) ||
      (#authorizeAny(#principal, #schema, OWNER, MANAGE) &&
          #authorize(#principal, #catalog, USE_CATALOG))
      """)
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse updateSchemaAuthorization(
      @Param("name") @AuthorizeResourceKey(SCHEMA) String name, UpdatePermissions request) {
    return updateAuthorization(SCHEMA, name, request);
  }

  @Patch("/table/{name}")
  @AuthorizeExpression("""
      #authorize(#principal, #metastore, OWNER) ||
      #authorizeAny(#principal, #catalog, OWNER, MANAGE) ||
      (#authorize(#principal, #catalog, USE_CATALOG) &&
          #authorizeAny(#principal, #schema, OWNER, MANAGE)) ||
      (#authorize(#principal, #catalog, USE_CATALOG) &&
          #authorize(#principal, #schema, USE_SCHEMA) &&
          #authorizeAny(#principal, #table, OWNER, MANAGE))
      """)
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse updateTableAuthorization(
      @Param("name") @AuthorizeResourceKey(TABLE) String name, UpdatePermissions request) {
    return updateAuthorization(TABLE, name, request);
  }

  @Patch("/function/{name}")
  @AuthorizeExpression("""
      #authorize(#principal, #metastore, OWNER) ||
      #authorizeAny(#principal, #catalog, OWNER, MANAGE) ||
      (#authorize(#principal, #catalog, USE_CATALOG) &&
          #authorizeAny(#principal, #schema, OWNER, MANAGE)) ||
      (#authorize(#principal, #catalog, USE_CATALOG) &&
          #authorize(#principal, #schema, USE_SCHEMA) &&
          #authorizeAny(#principal, #function, OWNER, MANAGE))
      """)
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse updateFunctionAuthorization(
      @Param("name") @AuthorizeResourceKey(FUNCTION) String name, UpdatePermissions request) {
    return updateAuthorization(FUNCTION, name, request);
  }

  @Patch("/volume/{name}")
  @AuthorizeExpression("""
      #authorize(#principal, #metastore, OWNER) ||
      #authorizeAny(#principal, #catalog, OWNER, MANAGE) ||
      (#authorize(#principal, #catalog, USE_CATALOG) &&
          #authorizeAny(#principal, #schema, OWNER, MANAGE)) ||
      (#authorize(#principal, #catalog, USE_CATALOG) &&
          #authorize(#principal, #schema, USE_SCHEMA) &&
          #authorizeAny(#principal, #volume, OWNER, MANAGE))
      """)
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse updateVolumeAuthorization(
      @Param("name") @AuthorizeResourceKey(VOLUME) String name, UpdatePermissions request) {
    return updateAuthorization(VOLUME, name, request);
  }

  @Patch("/registered_model/{name}")
  @AuthorizeExpression("""
      #authorize(#principal, #metastore, OWNER) ||
      #authorizeAny(#principal, #catalog, OWNER, MANAGE) ||
      (#authorize(#principal, #catalog, USE_CATALOG) &&
          #authorizeAny(#principal, #schema, OWNER, MANAGE)) ||
      (#authorize(#principal, #catalog, USE_CATALOG) &&
          #authorize(#principal, #schema, USE_SCHEMA) &&
          #authorizeAny(#principal, #registered_model, OWNER, MANAGE))
      """)
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse updateRegisteredModelAuthorization(
      @Param("name") @AuthorizeResourceKey(REGISTERED_MODEL) String name,
      UpdatePermissions request) {
    return updateAuthorization(REGISTERED_MODEL, name, request);
  }

  @Patch("/external_location/{name}")
  @AuthorizeExpression("""
      #authorize(#principal, #metastore, OWNER) ||
      #authorizeAny(#principal, #external_location, OWNER, MANAGE)
      """)
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse updateExternalLocationAuthorization(
      @Param("name") @AuthorizeResourceKey(EXTERNAL_LOCATION) String name,
      UpdatePermissions request) {
    return updateAuthorization(EXTERNAL_LOCATION, name, request);
  }

  @Patch("/credential/{name}")
  @AuthorizeExpression("""
      #authorize(#principal, #metastore, OWNER) ||
      #authorizeAny(#principal, #credential, OWNER, MANAGE)
      """)
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse updateCredentialAuthorization(
      @Param("name") @AuthorizeResourceKey(CREDENTIAL) String name, UpdatePermissions request) {
    return updateAuthorization(CREDENTIAL, name, request);
  }

  private HttpResponse updateAuthorization(
      SecurableType securableType, String name, UpdatePermissions request) {
    UUID resourceId = getResourceId(securableType, name);
    List<PermissionsChange> changes = request.getChanges();
    validateAddedPrivileges(securableType, name, changes);
    validateNoDuplicateAddRemove(changes);
    authorizeSpecialPrivilegeChanges(securableType, resourceId, changes);
    Map<String, List<PermissionsChange>> changesByPrincipal =
        changes.stream().collect(Collectors.groupingBy(PermissionsChange::getPrincipal));
    Map<String, UUID> principalIdsByName =
        changesByPrincipal.keySet().stream()
            .collect(Collectors.toMap(principal -> principal, this::resolvePrincipalId));
    Set<UUID> principalIds = new HashSet<>(principalIdsByName.values());
    changesByPrincipal.forEach(
        (principal, principalChanges) -> {
          UUID principalId = principalIdsByName.get(principal);
          Set<Privileges> addedPrivileges =
              principalChanges.stream()
                  .flatMap(change -> change.getAdd().stream())
                  .map(Privileges::fromPrivilege)
                  .filter(Objects::nonNull)
                  .collect(Collectors.toSet());
          Set<Privileges> removedPrivileges =
              expandRemovedPrivileges(
                  securableType,
                  name,
                  principalChanges.stream()
                      .flatMap(change -> change.getRemove().stream())
                      .toList());

          // Explicit additions win over privileges introduced by expanding an ALL PRIVILEGES
          // removal. Direct add/remove contradictions were rejected above.
          removedPrivileges.removeAll(addedPrivileges);
          removedPrivileges.forEach(
              privilege -> revokeAuthorization(principalId, resourceId, privilege));
          addedPrivileges.forEach(
              privilege -> grantAuthorization(principalId, resourceId, privilege));
        });

    Map<UUID, List<Privileges>> authorizations = authorizer.listAuthorizations(resourceId);
    List<PrivilegeAssignment> privilegeAssignments =
        authorizations.entrySet().stream()
            .filter(entry -> principalIds.contains(entry.getKey()))
            .map(
                entry -> {
                  List<Privilege> privileges = toApiPrivileges(entry.getValue());
                  return new PrivilegeAssignment()
                      .principal(userRepository.getUser(entry.getKey().toString()).getEmail())
                      .privileges(privileges);
                })
            .filter(assignment -> !assignment.getPrivileges().isEmpty())
            .collect(Collectors.toList());

    return HttpResponse.ofJson(new PermissionsList().privilegeAssignments(privilegeAssignments));
  }

  private UUID resolvePrincipalId(String principal) {
    User user = userRepository.getUserByEmail(principal);
    return UUID.fromString(Objects.requireNonNull(user.getId()));
  }

  private boolean grantAuthorization(
      UUID principalId, UUID resourceId, Privileges privilege) {
    return authorizer.grantAuthorization(principalId, resourceId, privilege);
  }

  /**
   * EXTERNAL_USE_SCHEMA is deliberately harder to delegate than ordinary schema privileges. A
   * schema owner or schema MANAGE grant can administer that schema, but only an administrator of
   * the parent catalog may grant or revoke external clients' access to it.
   */
  private void authorizeSpecialPrivilegeChanges(
      SecurableType securableType, UUID resourceId, List<PermissionsChange> changes) {
    boolean changesExternalUseSchema =
        securableType == SCHEMA
            && changes.stream()
                .anyMatch(
                    change ->
                        change.getAdd().contains(Privilege.EXTERNAL_USE_SCHEMA)
                            || change.getRemove().contains(Privilege.EXTERNAL_USE_SCHEMA));
    if (!changesExternalUseSchema) {
      return;
    }

    UUID principalId = userRepository.findPrincipalId();
    UUID catalogId = authorizer.getHierarchyParent(resourceId);
    boolean allowed =
        authorizer.authorize(
                principalId, metastoreRepository.getMetastoreId(), Privileges.OWNER)
            || (catalogId != null
                && authorizer.authorizeAny(
                    principalId, catalogId, Privileges.OWNER, Privileges.MANAGE));
    if (!allowed) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "Access denied.");
    }
  }

  private boolean revokeAuthorization(
      UUID principalId, UUID resourceId, Privileges privilege) {
    return authorizer.revokeAuthorization(principalId, resourceId, privilege);
  }

  private List<Privilege> toApiPrivileges(List<Privileges> storedPrivileges) {
    return storedPrivileges.stream()
        .<Privilege>map(Privileges::toPrivilege)
        // Privileges is a superset of the public API enum (for example OWNER).
        .filter(Objects::nonNull)
        .toList();
  }

  private Set<Privileges> expandRemovedPrivileges(
      SecurableType securableType, String name, List<Privilege> requestedPrivileges) {
    Set<Privileges> removedPrivileges =
        requestedPrivileges.stream()
            .map(Privileges::fromPrivilege)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    if (removedPrivileges.contains(Privileges.ALL_PRIVILEGES)) {
      assignablePrivileges(securableType, name).stream()
          .filter(PrivilegePolicy::isCoveredByAllPrivileges)
          .forEach(removedPrivileges::add);
    }
    return removedPrivileges;
  }

  private void validateAddedPrivileges(
      SecurableType securableType, String name, List<PermissionsChange> changes) {
    changes.forEach(
        change ->
            change
                .getAdd()
                .forEach(privilege -> validatePrivilege(securableType, name, privilege)));
  }

  private void validateNoDuplicateAddRemove(List<PermissionsChange> changes) {
    changes.stream()
        .collect(Collectors.groupingBy(PermissionsChange::getPrincipal))
        .forEach(
            (principal, principalChanges) -> {
              Set<Privilege> addedPrivileges =
                  principalChanges.stream()
                      .flatMap(change -> change.getAdd().stream())
                      .collect(Collectors.toSet());
              Set<Privilege> removedPrivileges =
                  principalChanges.stream()
                      .flatMap(change -> change.getRemove().stream())
                      .collect(Collectors.toSet());
              addedPrivileges.retainAll(removedPrivileges);
              if (!addedPrivileges.isEmpty()) {
                throw new BaseException(
                    ErrorCode.INVALID_ARGUMENT,
                    String.format(
                        "Duplicate privileges to add and remove for principal '%s'.", principal));
              }
            });
  }

  private void validatePrivilege(
      SecurableType securableType, String name, Privilege privilege) {
    Privileges persistPrivilege = Privileges.fromPrivilege(privilege);
    boolean isAssignable =
        persistPrivilege != null
            && assignablePrivileges(securableType, name).contains(persistPrivilege);
    if (!isAssignable) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT,
          String.format(
              "Privilege '%s' cannot be assigned to securable type '%s'.",
              privilege.getValue(), securableType.getValue()));
    }
  }

  private Set<Privileges> assignablePrivileges(SecurableType securableType, String name) {
    return securableType == TABLE
        ? PrivilegePolicy.assignablePrivileges(tableRepository.getTable(name).getTableType())
        : PrivilegePolicy.assignablePrivileges(securableType);
  }

  private UUID getResourceId(SecurableType securableType, String name) {

    String resourceId = switch (securableType) {
      case METASTORE -> metastoreRepository.getMetastoreId().toString();
      case CATALOG -> catalogRepository.getCatalog(name).getId();
      case SCHEMA -> schemaRepository.getSchema(name).getSchemaId();
      case TABLE -> tableRepository.getTable(name).getTableId();
      case FUNCTION -> functionRepository.getFunction(name).getFunctionId();
      case VOLUME -> volumeRepository.getVolume(name).getVolumeId();
      case REGISTERED_MODEL -> modelRepository.getRegisteredModel(name).getId();
      case EXTERNAL_LOCATION -> externalLocationRepository.getExternalLocation(name).getId();
      case CREDENTIAL -> credentialRepository.getCredential(name).getId();
      default -> throw new BaseException(ErrorCode.FAILED_PRECONDITION, "Unknown resource type");
    };

    return UUID.fromString(Objects.requireNonNull(resourceId));
  }
}
