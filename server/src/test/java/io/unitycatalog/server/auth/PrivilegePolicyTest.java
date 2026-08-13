package io.unitycatalog.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.unitycatalog.server.model.SecurableType;
import io.unitycatalog.server.model.TableType;
import io.unitycatalog.server.persist.model.Privileges;
import org.junit.jupiter.api.Test;

class PrivilegePolicyTest {

  @Test
  void validatesPrivilegesAgainstSecurableType() {
    assertThat(PrivilegePolicy.isAssignable(SecurableType.METASTORE, Privileges.CREATE_CATALOG))
        .isTrue();
    assertThat(PrivilegePolicy.isAssignable(SecurableType.METASTORE, Privileges.SELECT)).isFalse();

    assertThat(PrivilegePolicy.isAssignable(SecurableType.CATALOG, Privileges.SELECT)).isTrue();
    assertThat(PrivilegePolicy.isAssignable(SecurableType.SCHEMA, Privileges.BROWSE)).isFalse();

    assertThat(PrivilegePolicy.isAssignable(SecurableType.VOLUME, Privileges.WRITE_VOLUME))
        .isTrue();
    assertThat(PrivilegePolicy.isAssignable(SecurableType.VOLUME, Privileges.WRITE_FILES))
        .isFalse();

    assertThat(
            PrivilegePolicy.isAssignable(
                SecurableType.EXTERNAL_LOCATION, Privileges.EXTERNAL_USE_LOCATION))
        .isTrue();
    assertThat(
            PrivilegePolicy.isAssignable(
                SecurableType.CREDENTIAL, Privileges.CREATE_EXTERNAL_VOLUME))
        .isFalse();

    assertThat(PrivilegePolicy.isApplicable(SecurableType.SCHEMA, Privileges.BROWSE)).isTrue();
    assertThat(PrivilegePolicy.isApplicable(SecurableType.CREDENTIAL, Privileges.BROWSE)).isFalse();
  }

  @Test
  void validatesTablePrivilegesAgainstTableSubtype() {
    assertThat(PrivilegePolicy.assignablePrivileges(SecurableType.TABLE))
        .contains(Privileges.MODIFY, Privileges.REFRESH)
        .doesNotContain(Privileges.CREATE_TABLE);

    assertThat(PrivilegePolicy.isAssignable(TableType.MANAGED, Privileges.MODIFY)).isTrue();
    assertThat(PrivilegePolicy.isAssignable(TableType.MANAGED, Privileges.REFRESH)).isFalse();
    assertThat(PrivilegePolicy.isAssignable(TableType.STREAMING_TABLE, Privileges.MODIFY)).isTrue();

    assertThat(PrivilegePolicy.isAssignable(TableType.VIEW, Privileges.MODIFY)).isFalse();
    assertThat(PrivilegePolicy.isAssignable(TableType.VIEW, Privileges.SELECT)).isTrue();

    assertThat(PrivilegePolicy.isAssignable(TableType.MATERIALIZED_VIEW, Privileges.REFRESH))
        .isTrue();
    assertThat(PrivilegePolicy.isAssignable(TableType.MATERIALIZED_VIEW, Privileges.MODIFY))
        .isFalse();
    assertThat(PrivilegePolicy.isApplicable(TableType.MATERIALIZED_VIEW, Privileges.MODIFY))
        .isFalse();
    assertThat(PrivilegePolicy.isApplicable(TableType.MANAGED, Privileges.REFRESH)).isFalse();
    assertThat(PrivilegePolicy.isApplicable(TableType.MANAGED, Privileges.BROWSE)).isTrue();

    assertThat(PrivilegePolicy.assignablePrivileges(TableType.EXTERNAL))
        .contains(Privileges.SELECT, Privileges.MODIFY)
        .doesNotContain(Privileges.REFRESH);
    assertThat(PrivilegePolicy.assignablePrivileges(TableType.MATERIALIZED_VIEW))
        .contains(Privileges.SELECT, Privileges.REFRESH)
        .doesNotContain(Privileges.MODIFY);
  }

  @Test
  void allPrivilegesDoesNotGrantAdministrativeOrExplicitExternalUsePrivileges() {
    assertThat(PrivilegePolicy.isCoveredByAllPrivileges(Privileges.SELECT)).isTrue();
    assertThat(PrivilegePolicy.isCoveredByAllPrivileges(Privileges.WRITE_VOLUME)).isTrue();
    assertThat(PrivilegePolicy.isCoveredByAllPrivileges(Privileges.CREATE_MODEL_VERSION)).isTrue();

    assertThat(PrivilegePolicy.isCoveredByAllPrivileges(Privileges.MANAGE)).isFalse();
    assertThat(PrivilegePolicy.isCoveredByAllPrivileges(Privileges.READ_METADATA)).isFalse();
    assertThat(PrivilegePolicy.isCoveredByAllPrivileges(Privileges.EXTERNAL_USE_SCHEMA)).isFalse();
    assertThat(PrivilegePolicy.isCoveredByAllPrivileges(Privileges.EXTERNAL_USE_LOCATION))
        .isFalse();
  }

  @Test
  void manageGrantsMetadataButNotDataAccess() {
    assertThat(PrivilegePolicy.isCoveredByManage(Privileges.MANAGE)).isTrue();
    assertThat(PrivilegePolicy.isCoveredByManage(Privileges.READ_METADATA)).isTrue();
    assertThat(PrivilegePolicy.isCoveredByManage(Privileges.SELECT)).isFalse();
    assertThat(PrivilegePolicy.isCoveredByManage(Privileges.EXECUTE)).isFalse();
    assertThat(PrivilegePolicy.isCoveredByManage(Privileges.WRITE_VOLUME)).isFalse();
  }
}
