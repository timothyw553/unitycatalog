package io.unitycatalog.server.service;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Post;
import io.unitycatalog.server.auth.UnityCatalogAuthorizer;
import io.unitycatalog.server.auth.annotation.AuthorizeExpression;
import io.unitycatalog.server.auth.annotation.AuthorizeKey;
import io.unitycatalog.server.auth.annotation.ResponseAuthorizeFilter;
import io.unitycatalog.server.auth.annotation.AuthorizeResourceKey;
import io.unitycatalog.server.auth.annotation.AuthorizeResourceKeys;
import io.unitycatalog.server.exception.GlobalExceptionHandler;
import io.unitycatalog.server.model.CreateModelVersion;
import io.unitycatalog.server.model.CreateRegisteredModel;
import io.unitycatalog.server.model.FinalizeModelVersion;
import io.unitycatalog.server.model.ListRegisteredModelsResponse;
import io.unitycatalog.server.model.ModelVersionInfo;
import io.unitycatalog.server.model.RegisteredModelInfo;
import io.unitycatalog.server.model.SchemaInfo;
import io.unitycatalog.server.model.SecurableType;
import io.unitycatalog.server.model.UpdateModelVersion;
import io.unitycatalog.server.model.UpdateRegisteredModel;
import io.unitycatalog.server.persist.CatalogRepository;
import io.unitycatalog.server.persist.MetastoreRepository;
import io.unitycatalog.server.persist.ModelRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.SchemaRepository;
import io.unitycatalog.server.utils.ServerProperties;

import java.util.Optional;

import lombok.SneakyThrows;

import static io.unitycatalog.server.model.SecurableType.CATALOG;
import static io.unitycatalog.server.model.SecurableType.METASTORE;
import static io.unitycatalog.server.model.SecurableType.REGISTERED_MODEL;
import static io.unitycatalog.server.model.SecurableType.SCHEMA;

@ExceptionHandler(GlobalExceptionHandler.class)
public class ModelService extends AuthorizedService {

  private static final String CREATE_OR_FINALIZE_MODEL_VERSION_AUTH_EXPRESSION =
      """
      #authorizeAny(#principal, #registered_model, OWNER, CREATE_MODEL_VERSION) &&
          #authorizeAny(#principal, #schema, OWNER, USE_SCHEMA) &&
          #authorizeAny(#principal, #catalog, OWNER, USE_CATALOG)
      """;

  private static final String READ_MODEL_VERSION_AUTH_EXPRESSION =
      """
      #authorizeAny(#principal, #metastore, OWNER, READ_METADATA) ||
      #authorizeAny(#principal, #catalog, OWNER, MANAGE, READ_METADATA) ||
      (#authorize(#principal, #catalog, USE_CATALOG) &&
          #authorizeAny(#principal, #schema, OWNER, MANAGE, READ_METADATA)) ||
      (#authorize(#principal, #catalog, USE_CATALOG) &&
          #authorize(#principal, #schema, USE_SCHEMA) &&
          #authorizeAny(#principal, #registered_model, OWNER, EXECUTE, MANAGE, READ_METADATA)) ||
      (#include_browse == 'true' &&
          #authorize(#principal, #registered_model, BROWSE))
      """;

  private final ModelRepository modelRepository;
  private final SchemaRepository schemaRepository;
  private final CatalogRepository catalogRepository;
  private final MetastoreRepository metastoreRepository;

  @SneakyThrows
  public ModelService(
      UnityCatalogAuthorizer authorizer,
      Repositories repositories,
      ServerProperties serverProperties) {
    super(authorizer, repositories, serverProperties);
    this.catalogRepository = repositories.getCatalogRepository();
    this.schemaRepository = repositories.getSchemaRepository();
    this.modelRepository = repositories.getModelRepository();
    this.metastoreRepository = repositories.getMetastoreRepository();
  }

  @Post("")
  @AuthorizeExpression("""
      (#authorizeAny(#principal, #catalog, OWNER, USE_CATALOG) &&
          #authorize(#principal, #schema, OWNER)) ||
      (#authorizeAny(#principal, #catalog, OWNER, USE_CATALOG) &&
          #authorizeAll(#principal, #schema, USE_SCHEMA, CREATE_MODEL))
      """)
  public HttpResponse createRegisteredModel(
      @AuthorizeResourceKeys({
        @AuthorizeResourceKey(value = SCHEMA, key = "schema_name"),
        @AuthorizeResourceKey(value = CATALOG, key = "catalog_name")
      })
      CreateRegisteredModel createRegisteredModel) {
    assert createRegisteredModel != null;
    RegisteredModelInfo createRegisteredModelResponse =
        modelRepository.createRegisteredModel(createRegisteredModel);

    String catalogName = createRegisteredModelResponse.getCatalogName();
    String schemaName = createRegisteredModelResponse.getSchemaName();
    SchemaInfo schemaInfo = schemaRepository.getSchema(catalogName + "." + schemaName);
    String modelId = createRegisteredModelResponse.getId();
    initializeHierarchicalAuthorization(modelId, schemaInfo.getSchemaId());

    return HttpResponse.ofJson(createRegisteredModelResponse);
  }

  private static final String LIST_AND_GET_AUTH_EXPRESSION = """
      #authorizeAny(#principal, #metastore, OWNER, READ_METADATA) ||
      #authorizeAny(#principal, #catalog, OWNER, MANAGE, READ_METADATA) ||
      (#authorize(#principal, #catalog, USE_CATALOG) &&
          #authorizeAny(#principal, #schema, OWNER, MANAGE, READ_METADATA)) ||
      (#authorize(#principal, #catalog, USE_CATALOG) &&
          #authorize(#principal, #schema, USE_SCHEMA) &&
          #authorizeAny(#principal, #registered_model, OWNER, EXECUTE, MANAGE, READ_METADATA))
      """;

  @Get("")
  @AuthorizeExpression(LIST_AND_GET_AUTH_EXPRESSION)
  @ResponseAuthorizeFilter
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse listRegisteredModels(
      @Param("catalog_name") Optional<String> catalogName,
      @Param("schema_name") Optional<String> schemaName,
      @Param("max_results") Optional<Integer> maxResults,
      @Param("page_token") Optional<String> pageToken,
      @Param("include_browse")
      @AuthorizeKey(key = "include_browse")
      Optional<Boolean> includeBrowse) {
    ListRegisteredModelsResponse listRegisteredModelsResponse =
        modelRepository.listRegisteredModels(catalogName, schemaName, maxResults, pageToken);
    applyResponseFilter(
        SecurableType.REGISTERED_MODEL, listRegisteredModelsResponse.getRegisteredModels());
    return HttpResponse.ofJson(listRegisteredModelsResponse);
  }

  @Get("/{full_name}")
  @AuthorizeExpression(LIST_AND_GET_AUTH_EXPRESSION)
  @ResponseAuthorizeFilter
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse getRegisteredModel(
      @Param("full_name") @AuthorizeResourceKey(REGISTERED_MODEL) String fullNameArg,
      @Param("include_browse")
      @AuthorizeKey(key = "include_browse")
      Optional<Boolean> includeBrowse) {
    assert fullNameArg != null;
    RegisteredModelInfo registeredModelInfo = modelRepository.getRegisteredModel(fullNameArg);
    return HttpResponse.ofJson(
        applyResponseFilter(SecurableType.REGISTERED_MODEL, registeredModelInfo));
  }

  @Patch("/{full_name}")
  @AuthorizeExpression("""
      #authorizeAny(#principal, #catalog, OWNER, MANAGE) ||
      (#authorize(#principal, #catalog, USE_CATALOG) &&
          #authorizeAny(#principal, #schema, OWNER, MANAGE)) ||
      (#authorize(#principal, #catalog, USE_CATALOG) &&
          #authorize(#principal, #schema, USE_SCHEMA) &&
          #authorizeAny(#principal, #registered_model, OWNER, MANAGE))
      """)
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse updateRegisteredModel(
      @Param("full_name") @AuthorizeResourceKey(REGISTERED_MODEL) String fullName,
      UpdateRegisteredModel updateRegisteredModel) {
    assert updateRegisteredModel != null;
    RegisteredModelInfo updateRegisteredModelResponse =
        modelRepository.updateRegisteredModel(fullName, updateRegisteredModel);
    return HttpResponse.ofJson(updateRegisteredModelResponse);
  }

  @Delete("/{full_name}")
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
  public HttpResponse deleteRegisteredModel(
      @Param("full_name") @AuthorizeResourceKey(REGISTERED_MODEL) String fullName,
      @Param("force") Optional<Boolean> force) {
    RegisteredModelInfo registeredModelInfo = modelRepository.getRegisteredModel(fullName);
    modelRepository.deleteRegisteredModel(fullName, force.orElse(false));

    SchemaInfo schemaInfo =
        schemaRepository.getSchema(
            registeredModelInfo.getCatalogName() + "." + registeredModelInfo.getSchemaName());
    removeHierarchicalAuthorizations(registeredModelInfo.getId(), schemaInfo.getSchemaId());

    return HttpResponse.of(HttpStatus.OK);
  }

  @Post("/versions")
  @AuthorizeExpression(CREATE_OR_FINALIZE_MODEL_VERSION_AUTH_EXPRESSION)
  public HttpResponse createModelVersion(
      @AuthorizeResourceKeys({
        @AuthorizeResourceKey(value = CATALOG, key = "catalog_name"),
        @AuthorizeResourceKey(value = SCHEMA, key = "schema_name"),
        @AuthorizeResourceKey(value = REGISTERED_MODEL, key = "model_name")
      })
      CreateModelVersion createModelVersion) {
    assert createModelVersion != null;
    assert createModelVersion.getModelName() != null;
    assert createModelVersion.getCatalogName() != null;
    assert createModelVersion.getSchemaName() != null;
    assert createModelVersion.getSource() != null;
    ModelVersionInfo createModelVersionResponse =
        modelRepository.createModelVersion(createModelVersion);
    return HttpResponse.ofJson(createModelVersionResponse);
  }

  @Get("/{full_name}/versions")
  @AuthorizeExpression(READ_MODEL_VERSION_AUTH_EXPRESSION)
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse listModelVersions(
      @Param("full_name") @AuthorizeResourceKey(REGISTERED_MODEL) String fullName,
      @Param("max_results") Optional<Integer> maxResults,
      @Param("page_token") Optional<String> pageToken,
      @Param("include_browse")
          @AuthorizeKey(key = "include_browse") Optional<Boolean> includeBrowse) {
    return HttpResponse.ofJson(modelRepository.listModelVersions(fullName, maxResults, pageToken));
  }

  @Get("/{full_name}/versions/{version}")
  @AuthorizeExpression(READ_MODEL_VERSION_AUTH_EXPRESSION)
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse getModelVersion(
      @Param("full_name") @AuthorizeResourceKey(REGISTERED_MODEL) String fullName,
      @Param("version") Long version,
      @Param("include_browse")
          @AuthorizeKey(key = "include_browse") Optional<Boolean> includeBrowse) {
    assert fullName != null && version != null;
    ModelVersionInfo modelVersionInfo = modelRepository.getModelVersion(fullName, version);
    return HttpResponse.ofJson(modelVersionInfo);
  }

  @Patch("/{full_name}/versions/{version}")
  @AuthorizeExpression("""
      #authorizeAny(#principal, #catalog, OWNER, MANAGE) ||
      (#authorize(#principal, #catalog, USE_CATALOG) &&
          #authorizeAny(#principal, #schema, OWNER, MANAGE)) ||
      (#authorize(#principal, #catalog, USE_CATALOG) &&
          #authorize(#principal, #schema, USE_SCHEMA) &&
          #authorizeAny(#principal, #registered_model, OWNER, MANAGE))
      """)
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse updateModelVersion(
      @Param("full_name") @AuthorizeResourceKey(REGISTERED_MODEL) String fullName,
      @Param("version") Long version,
      UpdateModelVersion updateModelVersion) {
    assert updateModelVersion != null;
    ModelVersionInfo updateModelVersionResponse =
        modelRepository.updateModelVersion(fullName, version, updateModelVersion);
    return HttpResponse.ofJson(updateModelVersionResponse);
  }

  @Delete("/{full_name}/versions/{version}")
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
  public HttpResponse deleteModelVersion(
      @Param("full_name") @AuthorizeResourceKey(REGISTERED_MODEL) String fullName,
      @Param("version") Long version) {
    modelRepository.deleteModelVersion(fullName, version);
    return HttpResponse.of(HttpStatus.OK);
  }

  @Patch("/{full_name}/versions/{version}/finalize")
  @AuthorizeExpression(CREATE_OR_FINALIZE_MODEL_VERSION_AUTH_EXPRESSION)
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse finalizeModelVersion(
      @Param("full_name") @AuthorizeResourceKey(REGISTERED_MODEL) String fullName,
      @Param("version") Long version,
      FinalizeModelVersion finalizeModelVersion) {
    assert finalizeModelVersion != null;
    ModelVersionInfo finalizeModelVersionResponse =
        modelRepository.finalizeModelVersion(fullName, version);
    return HttpResponse.ofJson(finalizeModelVersionResponse);
  }

}
