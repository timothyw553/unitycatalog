package io.unitycatalog.server.cleanup;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.apache.iceberg.io.DelegateFileIO;
import org.apache.iceberg.io.FileInfo;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.io.SupportsPrefixOperations;

/** Deletes one bound prefix in batches and observes interruption between storage requests. */
public final class InterruptiblePrefixOperations implements SupportsPrefixOperations {
  private static final int MAX_BATCH_SIZE = 1000;

  private final DelegateFileIO delegate;
  private final String cleanupPrefix;

  public InterruptiblePrefixOperations(DelegateFileIO delegate, String cleanupPrefix) {
    if (!cleanupPrefix.endsWith("/")) {
      throw new IllegalArgumentException("Cleanup prefix must end with '/'");
    }
    this.delegate = delegate;
    this.cleanupPrefix = cleanupPrefix;
  }

  @Override
  public InputFile newInputFile(String path) {
    throw unsupportedOperation();
  }

  @Override
  public OutputFile newOutputFile(String path) {
    throw unsupportedOperation();
  }

  @Override
  public void deleteFile(String path) {
    throw unsupportedOperation();
  }

  /**
   * Returns a lazy listing for the exact cleanup prefix.
   *
   * @throws IllegalArgumentException if {@code prefix} is not the bound cleanup prefix
   */
  @Override
  public Iterable<FileInfo> listPrefix(String prefix) {
    checkPrefix(prefix);
    return delegate.listPrefix(prefix);
  }

  /**
   * Deletes files under the exact cleanup prefix in bounded batches.
   *
   * @throws IllegalArgumentException if {@code prefix} is not the bound cleanup prefix
   * @throws CancellationException if the current thread is interrupted
   */
  @Override
  public void deletePrefix(String prefix) {
    checkPrefix(prefix);
    checkInterrupted();
    Iterator<FileInfo> listing = delegate.listPrefix(prefix).iterator();
    while (true) {
      checkInterrupted();
      List<String> batch = new ArrayList<>(MAX_BATCH_SIZE);
      while (batch.size() < MAX_BATCH_SIZE && listing.hasNext()) {
        batch.add(listing.next().location());
      }
      checkInterrupted();
      if (batch.isEmpty()) {
        return;
      }
      delegate.deleteFiles(batch);
    }
  }

  @Override
  public Map<String, String> properties() {
    return delegate.properties();
  }

  @Override
  public void initialize(Map<String, String> properties) {
    throw unsupportedOperation();
  }

  @Override
  public void close() {
    delegate.close();
  }

  /**
   * Rejects any prefix other than the bound cleanup prefix. Exact matching prevents an object-store
   * prefix such as {@code .../id/} from reaching a sibling such as {@code .../id2/}.
   */
  private void checkPrefix(String prefix) {
    if (!cleanupPrefix.equals(prefix)) {
      throw new IllegalArgumentException("Prefix does not match cleanup location");
    }
  }

  private static UnsupportedOperationException unsupportedOperation() {
    return new UnsupportedOperationException("Cleanup supports only bound prefix operations");
  }

  private static void checkInterrupted() {
    if (Thread.interrupted()) {
      throw new CancellationException("Prefix deletion interrupted");
    }
  }
}
