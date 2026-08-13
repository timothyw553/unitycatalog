package io.unitycatalog.server.persist.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import io.unitycatalog.server.model.Privilege;
import java.util.List;
import org.junit.jupiter.api.Test;

public class PrivilegesTest {

  @Test
  public void testPrivilegesIsSupersetOfPrivilege() {
    // Verify that all generated privileges exist in the persist model
    for (Privilege generatedPrivilege : Privilege.values()) {
      Privileges persistPrivilege = Privileges.fromPrivilege(generatedPrivilege);
      if (persistPrivilege == null) {
        fail(
            String.format(
                "Generated privilege '%s' is not mappable to Privileges enum", generatedPrivilege));
      }
      assertThat(persistPrivilege.name()).isEqualTo(generatedPrivilege.name());
      assertThat(persistPrivilege.getValue()).isEqualTo(generatedPrivilege.getValue());
    }
  }

  @Test
  public void testNewPrivilegesRoundTrip() {
    List.of(
            Privileges.ALL_PRIVILEGES,
            Privileges.APPLY_TAG,
            Privileges.BROWSE,
            Privileges.CREATE_MATERIALIZED_VIEW,
            Privileges.CREATE_MODEL_VERSION,
            Privileges.EXTERNAL_USE_LOCATION,
            Privileges.EXTERNAL_USE_SCHEMA,
            Privileges.MANAGE,
            Privileges.READ_METADATA,
            Privileges.REFRESH,
            Privileges.WRITE_VOLUME)
        .forEach(
            persistPrivilege -> {
              Privilege generatedPrivilege = Privileges.toPrivilege(persistPrivilege);
              assertThat(generatedPrivilege).isNotNull();
              assertThat(Privileges.fromPrivilege(generatedPrivilege)).isEqualTo(persistPrivilege);
            });
  }
}
