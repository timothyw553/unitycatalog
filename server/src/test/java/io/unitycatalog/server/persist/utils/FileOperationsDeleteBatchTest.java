package io.unitycatalog.server.persist.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.persist.utils.FileOperations.DeleteBatchResult;
import io.unitycatalog.server.service.credential.CredentialContext;
import io.unitycatalog.server.utils.NormalizedURL;
import io.unitycatalog.server.utils.ServerProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.DelegateFileIO;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.FileInfo;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileOperationsDeleteBatchTest {
  @TempDir Path temporaryDirectory;

  @Test
  void deleteBatchVendsFreshReadWriteCredentials() {
    RecordingFileIO delegate = new RecordingFileIO(List.of("gs://bucket/table/part.parquet"));
    RecordingFileOperations fileOperations = new RecordingFileOperations(delegate);
    NormalizedURL table = NormalizedURL.from("gs://bucket/table");

    assertThat(fileOperations.deleteBatch(table, 1)).isEqualTo(DeleteBatchResult.MORE_WORK);
    assertThat(fileOperations.deleteBatch(table, 1)).isEqualTo(DeleteBatchResult.MORE_WORK);

    assertThat(fileOperations.privilegeRequests).hasSize(2);
    assertThat(fileOperations.privilegeRequests)
        .allSatisfy(
            privileges ->
                assertThat(privileges)
                    .containsExactlyInAnyOrder(
                        CredentialContext.Privilege.SELECT, CredentialContext.Privilege.UPDATE));
  }

  @Test
  void objectListingUsesADirectoryBoundary() {
    RecordingFileIO delegate =
        new RecordingFileIO(
            List.of(
                "gs://bucket/table-1/part.parquet",
                "gs://bucket/table-10/must-not-be-deleted.parquet"));
    RecordingFileOperations fileOperations = new RecordingFileOperations(delegate);

    assertThat(fileOperations.deleteBatch(NormalizedURL.from("gs://bucket/table-1"), 10))
        .isEqualTo(DeleteBatchResult.MORE_WORK);

    assertThat(delegate.listedPrefixes).containsExactly("gs://bucket/table-1/");
    assertThat(delegate.deletedFiles).containsExactly("gs://bucket/table-1/part.parquet");
  }

  @Test
  void azureFinalizesTheDirectoryAfterFilesAreGone() {
    RecordingFileIO delegate = new RecordingFileIO(List.of());
    RecordingFileOperations fileOperations = new RecordingFileOperations(delegate);
    String table = "abfss://container@account.dfs.core.windows.net/tables/id";

    assertThat(fileOperations.deleteBatch(NormalizedURL.from(table), 10))
        .isEqualTo(DeleteBatchResult.COMPLETE);

    assertThat(delegate.deletedPrefixes).containsExactly(table);
    assertThat(delegate.deletedMarkers).isEmpty();
  }

  @Test
  void objectStoreFinalizationOnlyDeletesExactMarkers() {
    RecordingFileIO delegate = new RecordingFileIO(List.of());
    RecordingFileOperations fileOperations = new RecordingFileOperations(delegate);
    String table = "s3://bucket/table-1";

    assertThat(fileOperations.deleteBatch(NormalizedURL.from(table), 10))
        .isEqualTo(DeleteBatchResult.COMPLETE);

    assertThat(delegate.deletedPrefixes).isEmpty();
    assertThat(delegate.deletedMarkers).containsExactly(table + "/", table);
  }

  @Test
  void deeplyNestedEmptyDirectoriesMakeProgressInBoundedBatches() throws Exception {
    Path root = temporaryDirectory.resolve("table");
    Path leaf = root;
    for (int i = 0; i < 20; i++) {
      leaf = leaf.resolve("level-" + i);
    }
    Files.createDirectories(leaf);
    FileOperations fileOperations =
        new FileOperations(null, new ServerProperties(new Properties()));

    DeleteBatchResult result = DeleteBatchResult.MORE_WORK;
    for (int i = 0; i < 11 && result == DeleteBatchResult.MORE_WORK; i++) {
      result = fileOperations.deleteBatch(NormalizedURL.from(root.toUri()), 2);
    }

    assertThat(result).isEqualTo(DeleteBatchResult.COMPLETE);
    assertThat(root).doesNotExist();
  }

  @Test
  void storageRootsAreRejectedBeforeOpeningFileIO() {
    RecordingFileOperations fileOperations = new RecordingFileOperations(new RecordingFileIO());

    assertThatThrownBy(() -> fileOperations.deleteBatch(NormalizedURL.from("gs://bucket"), 1))
        .isInstanceOf(BaseException.class);
    assertThatThrownBy(() -> fileOperations.deleteBatch(NormalizedURL.from("file:///"), 1))
        .isInstanceOf(BaseException.class);
    assertThat(fileOperations.privilegeRequests).isEmpty();
  }

  private static final class RecordingFileOperations extends FileOperations {
    private final RecordingFileIO delegate;
    private final List<Set<CredentialContext.Privilege>> privilegeRequests = new ArrayList<>();

    private RecordingFileOperations(RecordingFileIO delegate) {
      super(null, new ServerProperties(new Properties()));
      this.delegate = delegate;
    }

    @Override
    FileIO getFileIO(NormalizedURL path, Set<CredentialContext.Privilege> requestedPrivileges) {
      privilegeRequests.add(Set.copyOf(requestedPrivileges));
      return delegate;
    }
  }

  private static final class RecordingFileIO implements DelegateFileIO {
    private final List<String> objects;
    private final List<String> listedPrefixes = new ArrayList<>();
    private final List<String> deletedFiles = new ArrayList<>();
    private final List<String> deletedMarkers = new ArrayList<>();
    private final List<String> deletedPrefixes = new ArrayList<>();

    private RecordingFileIO(String... objects) {
      this(List.of(objects));
    }

    private RecordingFileIO(List<String> objects) {
      this.objects = objects;
    }

    @Override
    public CloseableIterable<FileInfo> listPrefix(String prefix) {
      listedPrefixes.add(prefix);
      return CloseableIterable.withNoopClose(
          objects.stream()
              .filter(location -> location.startsWith(prefix))
              .map(location -> new FileInfo(location, 1, 1))
              .toList());
    }

    @Override
    public void deleteFiles(Iterable<String> paths) {
      paths.forEach(deletedFiles::add);
    }

    @Override
    public void deleteFile(String path) {
      deletedMarkers.add(path);
    }

    @Override
    public void deletePrefix(String prefix) {
      deletedPrefixes.add(prefix);
    }

    @Override
    public InputFile newInputFile(String path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public OutputFile newOutputFile(String path) {
      throw new UnsupportedOperationException();
    }
  }
}
