package io.unitycatalog.server.auth.decorator;

import static io.unitycatalog.server.model.SecurableType.EXTERNAL_LOCATION;
import static io.unitycatalog.server.model.SecurableType.FUNCTION;
import static io.unitycatalog.server.model.SecurableType.METASTORE;
import static io.unitycatalog.server.model.SecurableType.TABLE;
import static io.unitycatalog.server.persist.model.Privileges.BROWSE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.model.DependencyList;
import io.unitycatalog.server.model.ExternalLocationInfo;
import io.unitycatalog.server.model.FunctionInfo;
import io.unitycatalog.server.model.SecurableType;
import io.unitycatalog.server.model.TableInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResultFilterTest {
  private static final String EXPRESSION = "normal authorization";

  private UUID principalId;
  private UnityAccessEvaluator evaluator;
  private KeyMapper keyMapper;

  @BeforeEach
  void setUp() {
    principalId = UUID.randomUUID();
    evaluator = mock(UnityAccessEvaluator.class);
    keyMapper = mock(KeyMapper.class);
  }

  @Test
  void filtersItemsIntoFullBrowseOnlyAndHiddenStates() {
    UUID fullId = UUID.randomUUID();
    UUID browseId = UUID.randomUUID();
    UUID hiddenId = UUID.randomUUID();
    Map<String, Object> requestValues = Map.of("include_browse", "true");
    ResultFilter filter = newFilter(Map.of(METASTORE, UUID.randomUUID()), requestValues);

    when(evaluator.evaluate(eq(principalId), eq(EXPRESSION), anyMap(), eq(requestValues)))
        .thenAnswer(invocation -> fullId.equals(resourceId(invocation.getArgument(2), TABLE)));
    when(evaluator.authorize(principalId, browseId, BROWSE)).thenReturn(true);

    TableInfo full = table(fullId, "full");
    TableInfo browseOnly = table(browseId, "browse");
    TableInfo hidden = table(hiddenId, "hidden");
    List<TableInfo> tables = new ArrayList<>(List.of(full, browseOnly, hidden));

    filter.filter(TABLE, tables);

    assertThat(tables).containsExactly(full, browseOnly);
    assertThat(full.getBrowseOnly()).isFalse();
    assertThat(full.getStorageLocation()).isEqualTo("s3://bucket/full");
    assertThat(full.getProperties()).containsEntry("key", "value");
    assertThat(full.getViewDefinition()).isEqualTo("SELECT secret");
    assertThat(full.getViewDependencies()).isNotNull();
    assertThat(browseOnly.getBrowseOnly()).isTrue();
    assertThat(browseOnly.getStorageLocation()).isNull();
    assertThat(browseOnly.getProperties()).isNull();
    assertThat(browseOnly.getViewDefinition()).isNull();
    assertThat(browseOnly.getViewDependencies()).isNull();
    verify(evaluator, never()).authorize(principalId, fullId, BROWSE);
    assertThat(filter.wasCalled()).isTrue();
  }

  @Test
  void doesNotTryBrowseUnlessItIsExplicitlyEnabled() {
    UUID tableId = UUID.randomUUID();
    Map<String, Object> requestValues = Map.of();
    ResultFilter filter = newFilter(Map.of(METASTORE, UUID.randomUUID()), requestValues);
    when(evaluator.evaluate(eq(principalId), eq(EXPRESSION), anyMap(), eq(requestValues)))
        .thenReturn(false);
    when(evaluator.authorize(principalId, tableId, BROWSE)).thenReturn(true);
    List<TableInfo> tables = new ArrayList<>(List.of(table(tableId, "hidden")));

    filter.filter(TABLE, tables);

    assertThat(tables).isEmpty();
    verify(evaluator, never()).authorize(any(), any(), any());
  }

  @Test
  void singleObjectFilteringProjectsSensitiveFunctionFields() {
    UUID functionId = UUID.randomUUID();
    Map<String, Object> requestValues = Map.of("include_browse", true);
    ResultFilter filter = newFilter(Map.of(FUNCTION, functionId), requestValues);
    when(evaluator.evaluate(eq(principalId), eq(EXPRESSION), anyMap(), eq(requestValues)))
        .thenReturn(false);
    when(evaluator.authorize(principalId, functionId, BROWSE)).thenReturn(true);
    FunctionInfo function =
        new FunctionInfo()
            .functionId(functionId.toString())
            .name("example")
            .routineDefinition("return secret");

    FunctionInfo result = filter.filterSingle(FUNCTION, function);

    assertThat(result).isSameAs(function);
    assertThat(result.getBrowseOnly()).isTrue();
    assertThat(result.getRoutineDefinition()).isNull();
    assertThat(filter.wasCalled()).isTrue();
  }

  @Test
  void browseOnlyExternalLocationsHideCredentialDetails() {
    UUID locationId = UUID.randomUUID();
    Map<String, Object> requestValues = Map.of("include_browse", "true");
    ResultFilter filter = newFilter(Map.of(METASTORE, UUID.randomUUID()), requestValues);
    when(evaluator.evaluate(eq(principalId), eq(EXPRESSION), anyMap(), eq(requestValues)))
        .thenReturn(false);
    when(evaluator.authorize(principalId, locationId, BROWSE)).thenReturn(true);
    ExternalLocationInfo location =
        new ExternalLocationInfo()
            .id(locationId.toString())
            .name("example")
            .credentialName("credential")
            .credentialId(UUID.randomUUID().toString());
    List<ExternalLocationInfo> locations = new ArrayList<>(List.of(location));

    filter.filter(EXTERNAL_LOCATION, locations);

    assertThat(locations).containsExactly(location);
    assertThat(location.getBrowseOnly()).isTrue();
    assertThat(location.getCredentialName()).isNull();
    assertThat(location.getCredentialId()).isNull();
  }

  @Test
  void singleObjectFilteringDeniesHiddenItems() {
    UUID tableId = UUID.randomUUID();
    Map<String, Object> requestValues = Map.of("include_browse", "true");
    ResultFilter filter = newFilter(Map.of(TABLE, tableId), requestValues);
    when(evaluator.evaluate(eq(principalId), eq(EXPRESSION), anyMap(), eq(requestValues)))
        .thenReturn(false);

    assertThatThrownBy(() -> filter.filterSingle(TABLE, table(tableId, "hidden")))
        .isInstanceOf(BaseException.class)
        .extracting(error -> ((BaseException) error).getErrorCode())
        .isEqualTo(ErrorCode.PERMISSION_DENIED);
    assertThat(filter.wasCalled()).isTrue();
  }

  @Test
  void singleObjectFilteringRejectsAResourceIdMismatch() {
    UUID requestedId = UUID.randomUUID();
    ResultFilter filter = newFilter(Map.of(TABLE, requestedId), Map.of("include_browse", "true"));

    assertThatThrownBy(
            () -> filter.filterSingle(TABLE, table(UUID.randomUUID(), "different-resource")))
        .isInstanceOf(BaseException.class)
        .extracting(error -> ((BaseException) error).getErrorCode())
        .isEqualTo(ErrorCode.PERMISSION_DENIED);
    verify(evaluator, never()).evaluate(any(), any(), anyMap(), anyMap());
  }

  private ResultFilter newFilter(
      Map<SecurableType, UUID> resourceIds, Map<String, Object> requestValues) {
    return new ResultFilter(
        evaluator, principalId, EXPRESSION, resourceIds, requestValues, keyMapper);
  }

  private static TableInfo table(UUID id, String name) {
    return new TableInfo()
        .tableId(id.toString())
        .name(name)
        .storageLocation("s3://bucket/full")
        .properties(Map.of("key", "value"))
        .viewDefinition("SELECT secret")
        .viewDependencies(new DependencyList());
  }

  private static UUID resourceId(Map<SecurableType, UUID> resourceIds, SecurableType type) {
    return resourceIds.get(type);
  }
}
