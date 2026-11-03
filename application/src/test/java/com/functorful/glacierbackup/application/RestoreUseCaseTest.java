package com.functorful.glacierbackup.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.functorful.glacierbackup.application.testing.FakeRemoteStorage;
import com.functorful.glacierbackup.domain.RelativePath;
import com.functorful.glacierbackup.domain.RestoreTier;
import com.functorful.glacierbackup.domain.StorageClass;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestoreUseCaseTest {

    private static final String REMOTE_PREFIX = "media";
    private static final String EMPTY_PREFIX = "empty";
    private static final byte[] CONTENT = "glacier-backup".getBytes();
    private static final RelativePath FILE_A = RelativePath.of("a.bin");
    private static final RelativePath FILE_B = RelativePath.of("b.bin");
    private static final String MISSING_KEY = "media/missing.bin";
    private static final int RESTORE_DAYS = 5;

    @Test
    void initiatesRestoreForGlacierObject(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        String key = seedRemoteObject(remoteStorage, tempDir, REMOTE_PREFIX, FILE_A, StorageClass.GLACIER);
        RestoreUseCase useCase = new RestoreUseCase(remoteStorage);

        RestoreResult result = useCase.run(List.of(key), RestoreTier.STANDARD, RESTORE_DAYS, false);

        assertThat(result.requestedKeys()).containsExactly(key);
        assertThat(result.hasFailures()).as("initiating restore for a GLACIER object must not report failures").isFalse();
        assertThat(remoteStorage.restoreCalls())
                .containsExactly(new FakeRemoteStorage.RestoreCall(key, RestoreTier.STANDARD, RESTORE_DAYS));
    }

    @Test
    void reportsFailureForExpeditedTierOnDeepArchiveWithoutInitiating(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        String key = seedRemoteObject(remoteStorage, tempDir, REMOTE_PREFIX, FILE_A, StorageClass.DEEP_ARCHIVE);
        RestoreUseCase useCase = new RestoreUseCase(remoteStorage);

        RestoreResult result = useCase.run(List.of(key), RestoreTier.EXPEDITED, RESTORE_DAYS, false);

        assertThat(result.requestedKeys()).as("an unsupported tier must not initiate a restore").isEmpty();
        assertThat(result.failures()).extracting(FailedItem::key).containsExactly(key);
        assertThat(result.hasFailures()).isTrue();
        assertThat(remoteStorage.restoreCalls()).as("no restore call must reach the remote storage").isEmpty();
    }

    @Test
    void reportsFailureForStandardStorageObjectWithoutInitiating(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        String key = seedRemoteObject(remoteStorage, tempDir, REMOTE_PREFIX, FILE_A, StorageClass.STANDARD);
        RestoreUseCase useCase = new RestoreUseCase(remoteStorage);

        RestoreResult result = useCase.run(List.of(key), RestoreTier.STANDARD, RESTORE_DAYS, false);

        assertThat(result.requestedKeys()).as("a STANDARD object does not require restore, so none must be initiated").isEmpty();
        assertThat(result.failures()).extracting(FailedItem::key).containsExactly(key);
        assertThat(remoteStorage.restoreCalls()).isEmpty();
    }

    @Test
    void reportsFailureWhenStatFailsForExactKey(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        String key = seedRemoteObject(remoteStorage, tempDir, REMOTE_PREFIX, FILE_A, StorageClass.GLACIER);
        remoteStorage.failOnKey(key);
        RestoreUseCase useCase = new RestoreUseCase(remoteStorage);

        RestoreResult result = useCase.run(List.of(key), RestoreTier.STANDARD, RESTORE_DAYS, false);

        assertThat(result.requestedKeys()).as("a failing existence check must not initiate a restore").isEmpty();
        assertThat(result.failures()).extracting(FailedItem::key).containsExactly(key);
        assertThat(remoteStorage.restoreCalls()).isEmpty();
    }

    @Test
    void reportsFailureForMissingExactKey() {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        RestoreUseCase useCase = new RestoreUseCase(remoteStorage);

        RestoreResult result = useCase.run(List.of(MISSING_KEY), RestoreTier.STANDARD, RESTORE_DAYS, false);

        assertThat(result.requestedKeys()).isEmpty();
        assertThat(result.failures()).extracting(FailedItem::key).containsExactly(MISSING_KEY);
        assertThat(result.hasFailures()).isTrue();
    }

    @Test
    void restoresEveryKeyUnderPrefixWhenRecursive(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        String keyA = seedRemoteObject(remoteStorage, tempDir, REMOTE_PREFIX, FILE_A, StorageClass.GLACIER);
        String keyB = seedRemoteObject(remoteStorage, tempDir, REMOTE_PREFIX, FILE_B, StorageClass.GLACIER);
        RestoreUseCase useCase = new RestoreUseCase(remoteStorage);

        RestoreResult result = useCase.run(List.of(REMOTE_PREFIX), RestoreTier.BULK, RESTORE_DAYS, true);

        assertThat(result.requestedKeys()).containsExactlyInAnyOrder(keyA, keyB);
        assertThat(result.hasFailures()).as("recursive restore of a fully eligible prefix must not report failures").isFalse();
    }

    @Test
    void reportsFailureForEmptyPrefixWhenRecursive() {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        RestoreUseCase useCase = new RestoreUseCase(remoteStorage);

        RestoreResult result = useCase.run(List.of(EMPTY_PREFIX), RestoreTier.BULK, RESTORE_DAYS, true);

        assertThat(result.requestedKeys()).isEmpty();
        assertThat(result.failures()).extracting(FailedItem::key).containsExactly(EMPTY_PREFIX);
    }

    private static String seedRemoteObject(
            FakeRemoteStorage remoteStorage, Path tempDir, String prefix, RelativePath path, StorageClass storageClass)
            throws IOException {
        Path sourceFile = tempDir.resolve("seed-" + path.value());
        Files.write(sourceFile, CONTENT);
        String key = RemoteKeys.keyFor(prefix, path);
        remoteStorage.put(sourceFile, key, storageClass);
        Files.delete(sourceFile);
        return key;
    }
}
