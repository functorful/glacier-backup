package com.functorful.glacierbackup.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.functorful.glacierbackup.application.testing.FakeRemoteStorage;
import com.functorful.glacierbackup.domain.RelativePath;
import com.functorful.glacierbackup.domain.StorageClass;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeleteUseCaseTest {

    private static final String REMOTE_PREFIX = "media";
    private static final String EMPTY_PREFIX = "empty";
    private static final String LISTING_FAILURE_PREFIX = "broken";
    private static final byte[] CONTENT = "glacier-backup".getBytes();
    private static final RelativePath FILE_A = RelativePath.of("a.bin");
    private static final RelativePath FILE_B = RelativePath.of("b.bin");
    private static final RelativePath FILE_C = RelativePath.of("c.bin");
    private static final String MISSING_KEY = "media/missing.bin";

    @Test
    void deletesExactKey(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        String key = seedRemoteObject(remoteStorage, tempDir, REMOTE_PREFIX, FILE_A);
        DeleteUseCase useCase = new DeleteUseCase(remoteStorage);

        DeleteResult result = useCase.run(List.of(key), false);

        assertThat(result.deletedKeys()).containsExactly(key);
        assertThat(result.hasFailures()).as("deleting an existing key must not report failures").isFalse();
        assertThat(remoteStorage.storedObjects()).as("deleted key must be removed from remote storage").doesNotContainKey(key);
    }

    @Test
    void reportsFailureWhenExactKeyIsAbsent() {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        DeleteUseCase useCase = new DeleteUseCase(remoteStorage);

        DeleteResult result = useCase.run(List.of(MISSING_KEY), false);

        assertThat(result.deletedKeys()).as("a missing key must not be deleted").isEmpty();
        assertThat(result.failures()).extracting(FailedItem::key).containsExactly(MISSING_KEY);
        assertThat(result.hasFailures()).as("a missing key must be reported as a failure").isTrue();
    }

    @Test
    void reportsFailureWhenStatFailsForExactKey(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        String key = seedRemoteObject(remoteStorage, tempDir, REMOTE_PREFIX, FILE_A);
        remoteStorage.failOnKey(key);
        DeleteUseCase useCase = new DeleteUseCase(remoteStorage);

        DeleteResult result = useCase.run(List.of(key), false);

        assertThat(result.deletedKeys()).as("a failing existence check must not delete the key").isEmpty();
        assertThat(result.failures()).extracting(FailedItem::key).containsExactly(key);
        assertThat(remoteStorage.storedObjects()).as("the object must remain untouched").containsKey(key);
    }

    @Test
    void deletesEveryKeyUnderPrefixWhenRecursive(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        String keyA = seedRemoteObject(remoteStorage, tempDir, REMOTE_PREFIX, FILE_A);
        String keyB = seedRemoteObject(remoteStorage, tempDir, REMOTE_PREFIX, FILE_B);
        String keyC = seedRemoteObject(remoteStorage, tempDir, REMOTE_PREFIX, FILE_C);
        DeleteUseCase useCase = new DeleteUseCase(remoteStorage);

        DeleteResult result = useCase.run(List.of(REMOTE_PREFIX), true);

        assertThat(result.deletedKeys()).containsExactlyInAnyOrder(keyA, keyB, keyC);
        assertThat(result.hasFailures()).as("recursive delete of a non-empty prefix must not report failures").isFalse();
        assertThat(remoteStorage.storedObjects()).as("all objects under the prefix must be removed").isEmpty();
    }

    @Test
    void reportsFailureWhenRecursivePrefixIsEmpty() {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        DeleteUseCase useCase = new DeleteUseCase(remoteStorage);

        DeleteResult result = useCase.run(List.of(EMPTY_PREFIX), true);

        assertThat(result.deletedKeys()).as("an empty prefix must not delete anything").isEmpty();
        assertThat(result.failures()).extracting(FailedItem::key).containsExactly(EMPTY_PREFIX);
        assertThat(result.hasFailures()).as("an empty prefix must be reported as a failure").isTrue();
    }

    @Test
    void isolatesListFailureToItsOwnPrefixWhenRecursive(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        String keyA = seedRemoteObject(remoteStorage, tempDir, REMOTE_PREFIX, FILE_A);
        remoteStorage.failOnListPrefix(LISTING_FAILURE_PREFIX);
        DeleteUseCase useCase = new DeleteUseCase(remoteStorage);

        DeleteResult result = useCase.run(List.of(LISTING_FAILURE_PREFIX, REMOTE_PREFIX), true);

        assertThat(result.failures()).extracting(FailedItem::key).containsExactly(LISTING_FAILURE_PREFIX);
        assertThat(result.deletedKeys())
                .as("a listing failure on one prefix must not block deletion under other prefixes in the same batch")
                .containsExactly(keyA);
    }

    private static String seedRemoteObject(FakeRemoteStorage remoteStorage, Path tempDir, String prefix, RelativePath path)
            throws IOException {
        Path sourceFile = tempDir.resolve("seed-" + path.value());
        Files.write(sourceFile, CONTENT);
        String key = RemoteKeys.keyFor(prefix, path);
        remoteStorage.put(sourceFile, key, StorageClass.STANDARD);
        Files.delete(sourceFile);
        return key;
    }
}
