package io.unitycatalog.server.service;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Post;
import io.unitycatalog.server.auth.annotation.AuthorizeExpression;
import io.unitycatalog.server.auth.annotation.AuthorizeResourceKey;
import io.unitycatalog.server.auth.annotation.AuthorizeKey;
import io.unitycatalog.server.exception.GlobalExceptionHandler;
import io.unitycatalog.server.model.GenerateTemporaryPathCredential;
import io.unitycatalog.server.model.PathOperation;
import io.unitycatalog.server.service.credential.CredentialContext;
import io.unitycatalog.server.service.credential.StorageCredentialVendor;
import io.unitycatalog.server.utils.NormalizedURL;

import java.util.Collections;
import java.util.Set;

import static io.unitycatalog.server.model.SecurableType.EXTERNAL_LOCATION;
import static io.unitycatalog.server.model.SecurableType.METASTORE;
import static io.unitycatalog.server.service.credential.CredentialContext.Privilege.SELECT;
import static io.unitycatalog.server.service.credential.CredentialContext.Privilege.UPDATE;

@ExceptionHandler(GlobalExceptionHandler.class)
public class TemporaryPathCredentialsService {
  private final StorageCredentialVendor storageCredentialVendor;

  public TemporaryPathCredentialsService(StorageCredentialVendor storageCredentialVendor) {
    this.storageCredentialVendor = storageCredentialVendor;
  }

  private Set<CredentialContext.Privilege> pathOperationToPrivileges(PathOperation pathOperation) {
    return switch (pathOperation) {
      case PATH_READ -> Set.of(SELECT);
      case PATH_READ_WRITE, PATH_CREATE_TABLE -> Set.of(SELECT, UPDATE);
      case UNKNOWN_PATH_OPERATION -> Collections.emptySet();
    };
  }

  // The authorization will determine which securable to authorize against in this order:
  // 1. if user is owner of metastore, always allow
  // 2. if the path is a parent of any data securable or external location, fail with error.
  // 3. if the path is a part of more than one data securable or more than one external location,
  //  fail with error.
  // 4. if the path is found to be part of one data securable:
  //   4a. if the path is found to belong to a table, user needs and only needs to have access to
  //    the table.
  //   4b. ditto for volume and registered_model
  // 5. Otherwise, the path must belong to one external location and user must have access to
  //  it.
  // This function will set the most specific table, volume, or model resource when one owns the
  // path. It also keeps the covering external location so the explicit external-use permission can
  // be checked independently.

  // Once it decided which securable to authorize against:
  // a. Every non-admin path request requires EXTERNAL_USE_LOCATION on the covering location.
  // b. For external location, it additionally requires READ_FILES for reads, and +WRITE_FILES for
  //  writes and reads.
  // c. For table, it requires SELECT for reads, and +MODIFY for writes and reads, plus
  //  EXTERNAL_USE_SCHEMA.
  // d. For volume, it requires READ_VOLUME for reads, and +WRITE_VOLUME for writes and reads, plus
  //  EXTERNAL_USE_SCHEMA.
  // e. For model, it requires EXECUTE for reads, and OWNER for writes and reads.
  // f. Additionally for all data securables, it requires USE_CATALOG and USE_SCHEMA (or OWNER)
  //  of the parent catalog and schema.
  @Post("")
  @AuthorizeExpression("""
      #operation == 'PATH_READ' ? (
        #authorize(#principal, #metastore, OWNER) ||
        (#table != null &&
         #external_location != null &&
         #authorize(#principal, #external_location, EXTERNAL_USE_LOCATION) &&
         #authorizeAny(#principal, #catalog, OWNER, USE_CATALOG) &&
         #authorizeAny(#principal, #schema, OWNER, USE_SCHEMA) &&
         #authorize(#principal, #schema, EXTERNAL_USE_SCHEMA) &&
         #authorizeAny(#principal, #table, OWNER, SELECT)) ||
        (#volume != null &&
         #external_location != null &&
         #authorize(#principal, #external_location, EXTERNAL_USE_LOCATION) &&
         #authorizeAny(#principal, #catalog, OWNER, USE_CATALOG) &&
         #authorizeAny(#principal, #schema, OWNER, USE_SCHEMA) &&
         #authorize(#principal, #schema, EXTERNAL_USE_SCHEMA) &&
         #authorizeAny(#principal, #volume, OWNER, READ_VOLUME)) ||
        (#registered_model != null &&
         #external_location != null &&
         #authorize(#principal, #external_location, EXTERNAL_USE_LOCATION) &&
         #authorizeAny(#principal, #catalog, OWNER, USE_CATALOG) &&
         #authorizeAny(#principal, #schema, OWNER, USE_SCHEMA) &&
         #authorizeAny(#principal, #registered_model, OWNER, EXECUTE)) ||
        (#table == null &&
         #volume == null &&
         #registered_model == null &&
         #external_location != null &&
         #authorize(#principal, #external_location, EXTERNAL_USE_LOCATION) &&
         #authorizeAny(#principal, #external_location, OWNER, READ_FILES))
      )
      : #operation == 'PATH_READ_WRITE' ? (
        #authorize(#principal, #metastore, OWNER) ||
        (#table != null &&
         #external_location != null &&
         #authorize(#principal, #external_location, EXTERNAL_USE_LOCATION) &&
         #authorizeAny(#principal, #catalog, OWNER, USE_CATALOG) &&
         #authorizeAny(#principal, #schema, OWNER, USE_SCHEMA) &&
         #authorize(#principal, #schema, EXTERNAL_USE_SCHEMA) &&
         (#authorize(#principal, #table, OWNER) ||
          #authorizeAll(#principal, #table, SELECT, MODIFY))) ||
        (#volume != null &&
         #external_location != null &&
         #authorize(#principal, #external_location, EXTERNAL_USE_LOCATION) &&
         #authorizeAny(#principal, #catalog, OWNER, USE_CATALOG) &&
         #authorizeAny(#principal, #schema, OWNER, USE_SCHEMA) &&
         #authorize(#principal, #schema, EXTERNAL_USE_SCHEMA) &&
         (#authorize(#principal, #volume, OWNER) ||
          #authorizeAll(#principal, #volume, READ_VOLUME, WRITE_VOLUME))) ||
        (#registered_model != null &&
         #external_location != null &&
         #authorize(#principal, #external_location, EXTERNAL_USE_LOCATION) &&
         #authorizeAny(#principal, #catalog, OWNER, USE_CATALOG) &&
         #authorizeAny(#principal, #schema, OWNER, USE_SCHEMA) &&
         #authorize(#principal, #registered_model, OWNER)) ||
        (#table == null &&
         #volume == null &&
         #registered_model == null &&
         #external_location != null &&
         #authorize(#principal, #external_location, EXTERNAL_USE_LOCATION) &&
         (#authorize(#principal, #external_location, OWNER) ||
          #authorizeAll(#principal, #external_location, READ_FILES, WRITE_FILES)))
      )
      : #operation == 'PATH_CREATE_TABLE' ? (
        #no_overlap_with_data_securable &&
        (#authorize(#principal, #metastore, OWNER) ||
         (#external_location != null &&
          #authorize(#principal, #external_location, EXTERNAL_USE_LOCATION) &&
          #authorizeAny(#principal, #external_location, OWNER, CREATE_EXTERNAL_TABLE)))
      )
      : #deny
      """)
  @AuthorizeResourceKey(METASTORE)
  public HttpResponse generateTemporaryPathCredential(
      @AuthorizeResourceKey(value = EXTERNAL_LOCATION, key = "url")
      @AuthorizeKey(key = "operation")
      GenerateTemporaryPathCredential generateTemporaryPathCredential) {
    return HttpResponse.ofJson(
        storageCredentialVendor.vendCredential(
            NormalizedURL.from(generateTemporaryPathCredential.getUrl()),
            pathOperationToPrivileges(generateTemporaryPathCredential.getOperation())));
  }
}
