package io.unitycatalog.server.service;

import static io.unitycatalog.server.model.SecurableType.CREDENTIAL;
import static io.unitycatalog.server.model.SecurableType.EXTERNAL_LOCATION;
import static io.unitycatalog.server.model.SecurableType.METASTORE;

import com.linecorp.armeria.common.HttpResponse;
import io.unitycatalog.server.model.SecurableType;
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
import io.unitycatalog.server.exception.GlobalExceptionHandler;
import io.unitycatalog.server.model.CreateExternalLocation;
import io.unitycatalog.server.model.ExternalLocationInfo;
import io.unitycatalog.server.model.ListExternalLocationsResponse;
import io.unitycatalog.server.model.UpdateExternalLocation;
import io.unitycatalog.server.persist.ExternalLocationRepository;
import io.unitycatalog.server.persist.MetastoreRepository;
import io.unitycatalog.server.persist.Repositories;
import io.unitycatalog.server.persist.dao.ExternalLocationDAO;
import io.unitycatalog.server.utils.ServerProperties;
import java.util.Optional;
import lombok.SneakyThrows;

@ExceptionHandler(GlobalExceptionHandler.class)
public class ExternalLocationService extends AuthorizedService {
  private final ExternalLocationRepository externalLocationRepository;
  private final MetastoreRepository metastoreRepository;

  @SneakyThrows
  public ExternalLocationService(
      UnityCatalogAuthorizer authorizer,
      Repositories repositories,
      ServerProperties serverProperties) {
    super(authorizer, repositories, serverProperties);
    this.externalLocationRepository = repositories.getExternalLocationRepository();
    this.metastoreRepository = repositories.getMetastoreRepository();
  }

  @Post("")
  @AuthorizeExpression("""
    #authorize(#principal, #metastore, OWNER) ||
    (#authorize(#principal, #metastore, CREATE_EXTERNAL_LOCATION) &&
     #authorizeAny(#principal, #credential, OWNER, CREATE_EXTERNAL_LOCATION))
    """)
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse createExternalLocation(
      @AuthorizeResourceKey(value = CREDENTIAL, key = "credential_name")
      CreateExternalLocation createExternalLocation) {
    ExternalLocationInfo externalLocationInfo =
        externalLocationRepository.addExternalLocation(createExternalLocation);
    initializeBasicAuthorization(externalLocationInfo.getId());
    return HttpResponse.ofJson(externalLocationInfo);
  }

  private static final String LIST_AND_GET_AUTH_EXPRESSION = """
    #authorize(#principal, #metastore, OWNER) ||
    #authorize(#principal, #metastore, READ_METADATA) ||
    #authorizeAny(#principal, #external_location, OWNER, READ_FILES, WRITE_FILES,
      CREATE_EXTERNAL_TABLE, CREATE_EXTERNAL_VOLUME, CREATE_MANAGED_STORAGE, EXTERNAL_USE_LOCATION,
      APPLY_TAG, READ_METADATA)
    """;

  @Get("")
  @AuthorizeExpression(LIST_AND_GET_AUTH_EXPRESSION)
  @ResponseAuthorizeFilter
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse listExternalLocations(
      @Param("max_results") Optional<Integer> maxResults,
      @Param("page_token") Optional<String> pageToken,
      @Param("include_browse")
      @AuthorizeKey(key = "include_browse")
      Optional<Boolean> includeBrowse) {
    ListExternalLocationsResponse locations =
        externalLocationRepository.listExternalLocations(maxResults, pageToken);
    applyResponseFilter(SecurableType.EXTERNAL_LOCATION, locations.getExternalLocations());
    return HttpResponse.ofJson(locations);
  }

  @Get("/{name}")
  @AuthorizeExpression(LIST_AND_GET_AUTH_EXPRESSION)
  @ResponseAuthorizeFilter
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse getExternalLocation(
      @Param("name") @AuthorizeResourceKey(EXTERNAL_LOCATION) String name,
      @Param("include_browse")
      @AuthorizeKey(key = "include_browse")
      Optional<Boolean> includeBrowse) {
    ExternalLocationInfo externalLocationInfo =
        externalLocationRepository.getExternalLocation(name);
    return HttpResponse.ofJson(
        applyResponseFilter(SecurableType.EXTERNAL_LOCATION, externalLocationInfo));
  }

  @Patch("/{name}")
  @AuthorizeExpression("""
    #authorize(#principal, #metastore, OWNER) ||
    (#authorizeAny(#principal, #external_location, OWNER, MANAGE) &&
     (#credential == null ||
      #authorizeAny(#principal, #credential, OWNER, CREATE_EXTERNAL_LOCATION)))
    """)
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse updateExternalLocation(
      @Param("name") @AuthorizeResourceKey(EXTERNAL_LOCATION) String name,
      @AuthorizeResourceKey(value = CREDENTIAL, key = "credential_name")
      UpdateExternalLocation updateRequest) {
    return HttpResponse.ofJson(
        externalLocationRepository.updateExternalLocation(name, updateRequest));
  }

  @Delete("/{name}")
  @AuthorizeExpression("""
    #authorize(#principal, #metastore, OWNER) ||
    #authorizeAny(#principal, #external_location, OWNER, MANAGE)
    """)
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse deleteExternalLocation(
      @Param("name") @AuthorizeResourceKey(EXTERNAL_LOCATION) String name,
      @Param("force") Optional<Boolean> force) {
    ExternalLocationDAO externalLocationDAO =
        externalLocationRepository.deleteExternalLocation(name, force.orElse(false));
    removeAuthorizations(externalLocationDAO.getId().toString());
    return HttpResponse.of(HttpStatus.OK);
  }

}
