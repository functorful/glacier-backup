package com.functorful.glacierbackup.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.functorful.glacierbackup.application.port.ProgressReporter;
import com.functorful.glacierbackup.application.testing.FakeRemoteStorage;
import com.functorful.glacierbackup.application.testing.RecordingProgressReporter;
import com.functorful.glacierbackup.domain.RelativePath;
import com.functorful.glacierbackup.domain.RestoreState;
import com.functorful.glacierbackup.domain.StorageClass;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadUseCaseTest {

    private static final String REMOTE_PREFIX = "media";
    private static final String PREFIX_A = "prefixA";
    private static final String PREFIX_B = "prefixB";
    private static final byte[] CONTENT = "glacier-backup".getBytes();
    private static final byte[] CONTENT_A = "content-a".getBytes();
    private static final byte[] CONTENT_B = "content-b".getBytes();
    private static final RelativePath READY_FILE = RelativePath.of("ready.bin");
    private static final RelativePath NOT_READY_FILE = RelativePath.of("not-ready.bin");
    private static final RelativePath STANDARD_FILE = RelativePath.of("standard.bin");
    private static final RelativePath FILE_A = RelativePath.of("a.bin");
    private static final RelativePath FILE_B = RelativePath.of("b.bin");
    private static final RelativePath FILE_C = RelativePath.of("c.bin");
    private static final RelativePath NOTES_FILE = RelativePath.of("notes.txt");
    private static final Instant READY_UNTIL = Instant.parse("2026-08-30T00:00:00Z");
    private static final String RESTORE_STATUS_HINT = "restore-status";

    @Test
    void downloadsReadyArchivedObject(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        Path sourceDir = Files.createDirectory(tempDir.resolve("source"));
        Path targetDir = Files.createDirectory(tempDir.resolve("target"));
        String key = seedRemoteObject(remoteStorage, sourceDir, REMOTE_PREFIX, READY_FILE, StorageClass.GLACIER, CONTENT);
        remoteStorage.seedRestoreState(key, RestoreState.readyUntil(READY_UNTIL));
        DownloadUseCase useCase = new DownloadUseCase(remoteStorage, ProgressReporter.noOp());

        DownloadResult result = useCase.run(List.of(key), targetDir, false);

        assertThat(result.downloadedKeys()).containsExactly(key);
        assertThat(result.hasFailures()).as("downloading a ready object must not report failures").isFalse();
        Path downloadedFile = targetDir.resolve(key);
        assertThat(downloadedFile).exists();
        assertThat(Files.readAllBytes(downloadedFile)).isEqualTo(CONTENT);
    }

    @Test
    void reportsFailureForNotReadyArchivedObjectWithoutDownloading(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        Path sourceDir = Files.createDirectory(tempDir.resolve("source"));
        Path targetDir = Files.createDirectory(tempDir.resolve("target"));
        String key = seedRemoteObject(remoteStorage, sourceDir, REMOTE_PREFIX, NOT_READY_FILE, StorageClass.GLACIER, CONTENT);
        DownloadUseCase useCase = new DownloadUseCase(remoteStorage, ProgressReporter.noOp());

        DownloadResult result = useCase.run(List.of(key), targetDir, false);

        assertThat(result.downloadedKeys()).as("a not-ready archived object must not be downloaded").isEmpty();
        assertThat(result.failures()).extracting(FailedItem::key).containsExactly(key);
        assertThat(result.failures().get(0).message()).contains(key).contains(RESTORE_STATUS_HINT);
        assertThat(targetDir.resolve(key)).doesNotExist();
    }

    @Test
    void reportsFailureWhenStatFailsForExactKey(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        Path sourceDir = Files.createDirectory(tempDir.resolve("source"));
        Path targetDir = Files.createDirectory(tempDir.resolve("target"));
        String key = seedRemoteObject(remoteStorage, sourceDir, REMOTE_PREFIX, STANDARD_FILE, StorageClass.STANDARD, CONTENT);
        remoteStorage.failOnKey(key);
        DownloadUseCase useCase = new DownloadUseCase(remoteStorage, ProgressReporter.noOp());

        DownloadResult result = useCase.run(List.of(key), targetDir, false);

        assertThat(result.downloadedKeys()).as("a failing existence check must not download the object").isEmpty();
        assertThat(result.failures()).extracting(FailedItem::key).containsExactly(key);
        assertThat(targetDir.resolve(key)).doesNotExist();
    }

    @Test
    void downloadsStandardObjectWithoutRestoreStateCall(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        Path sourceDir = Files.createDirectory(tempDir.resolve("source"));
        Path targetDir = Files.createDirectory(tempDir.resolve("target"));
        String key = seedRemoteObject(remoteStorage, sourceDir, REMOTE_PREFIX, STANDARD_FILE, StorageClass.STANDARD, CONTENT);
        DownloadUseCase useCase = new DownloadUseCase(remoteStorage, ProgressReporter.noOp());

        DownloadResult result = useCase.run(List.of(key), targetDir, false);

        assertThat(result.downloadedKeys()).containsExactly(key);
        assertThat(remoteStorage.restoreStateCallCount())
                .as("a STANDARD object must be downloaded without checking restore state")
                .isZero();
    }

    @Test
    void downloadsAllObjectsUnderPrefixWhenRecursive(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        Path sourceDir = Files.createDirectory(tempDir.resolve("source"));
        Path targetDir = Files.createDirectory(tempDir.resolve("target"));
        String keyA = seedRemoteObject(remoteStorage, sourceDir, REMOTE_PREFIX, FILE_A, StorageClass.STANDARD, CONTENT);
        String keyB = seedRemoteObject(remoteStorage, sourceDir, REMOTE_PREFIX, FILE_B, StorageClass.STANDARD, CONTENT);
        DownloadUseCase useCase = new DownloadUseCase(remoteStorage, ProgressReporter.noOp());

        DownloadResult result = useCase.run(List.of(REMOTE_PREFIX), targetDir, true);

        assertThat(result.downloadedKeys()).containsExactlyInAnyOrder(keyA, keyB);
        assertThat(targetDir.resolve(keyA)).as("recursive downloads must preserve the full key, same as non-recursive").exists();
        assertThat(targetDir.resolve(keyB)).exists();
    }

    @Test
    void avoidsCollisionsBetweenObjectsWithSameBasenameUnderDifferentPrefixes(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        Path sourceDir = Files.createDirectory(tempDir.resolve("source"));
        Path targetDir = Files.createDirectory(tempDir.resolve("target"));
        String keyA = seedRemoteObject(remoteStorage, sourceDir, PREFIX_A, NOTES_FILE, StorageClass.STANDARD, CONTENT_A);
        String keyB = seedRemoteObject(remoteStorage, sourceDir, PREFIX_B, NOTES_FILE, StorageClass.STANDARD, CONTENT_B);
        DownloadUseCase useCase = new DownloadUseCase(remoteStorage, ProgressReporter.noOp());

        DownloadResult result = useCase.run(List.of(PREFIX_A, PREFIX_B), targetDir, true);

        assertThat(result.downloadedKeys()).containsExactlyInAnyOrder(keyA, keyB);
        assertThat(Files.readAllBytes(targetDir.resolve(keyA)))
                .as("same basename under a different prefix must not overwrite the other download")
                .isEqualTo(CONTENT_A);
        assertThat(Files.readAllBytes(targetDir.resolve(keyB))).isEqualTo(CONTENT_B);
    }

    @Test
    void isolatesRestoreStateFailureToItsOwnKeyWhenRecursive(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        Path sourceDir = Files.createDirectory(tempDir.resolve("source"));
        Path targetDir = Files.createDirectory(tempDir.resolve("target"));
        String keyA = seedRemoteObject(remoteStorage, sourceDir, REMOTE_PREFIX, FILE_A, StorageClass.GLACIER, CONTENT);
        remoteStorage.seedRestoreState(keyA, RestoreState.readyUntil(READY_UNTIL));
        String keyB = seedRemoteObject(remoteStorage, sourceDir, REMOTE_PREFIX, FILE_B, StorageClass.GLACIER, CONTENT);
        remoteStorage.seedRestoreState(keyB, RestoreState.readyUntil(READY_UNTIL));
        remoteStorage.failOnKey(keyB);
        String keyC = seedRemoteObject(remoteStorage, sourceDir, REMOTE_PREFIX, FILE_C, StorageClass.GLACIER, CONTENT);
        remoteStorage.seedRestoreState(keyC, RestoreState.readyUntil(READY_UNTIL));
        DownloadUseCase useCase = new DownloadUseCase(remoteStorage, ProgressReporter.noOp());

        DownloadResult result = useCase.run(List.of(REMOTE_PREFIX), targetDir, true);

        assertThat(result.downloadedKeys())
                .as("a restoreState failure on one key must not block the others in the same prefix")
                .containsExactlyInAnyOrder(keyA, keyC);
        assertThat(result.failures()).extracting(FailedItem::key).containsExactly(keyB);
    }

    @Test
    void reportsProgressForCompletedAndFailedDownloads(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        Path sourceDir = Files.createDirectory(tempDir.resolve("source"));
        Path targetDir = Files.createDirectory(tempDir.resolve("target"));
        String readyKey = seedRemoteObject(remoteStorage, sourceDir, REMOTE_PREFIX, READY_FILE, StorageClass.GLACIER, CONTENT);
        remoteStorage.seedRestoreState(readyKey, RestoreState.readyUntil(READY_UNTIL));
        String notReadyKey = seedRemoteObject(remoteStorage, sourceDir, REMOTE_PREFIX, NOT_READY_FILE, StorageClass.GLACIER, CONTENT);
        RecordingProgressReporter progressReporter = new RecordingProgressReporter();
        DownloadUseCase useCase = new DownloadUseCase(remoteStorage, progressReporter);

        useCase.run(List.of(readyKey, notReadyKey), targetDir, false);

        assertThat(progressReporter.completedKeys()).as("a ready object must report progress completion").containsExactly(readyKey);
        assertThat(progressReporter.failedKeys())
                .as("a not-ready object must not even start progress reporting, since no download is attempted")
                .isEmpty();
    }

    private static String seedRemoteObject(
            FakeRemoteStorage remoteStorage, Path sourceDir, String prefix, RelativePath path, StorageClass storageClass, byte[] content)
            throws IOException {
        Path sourceFile = sourceDir.resolve(prefix + "-" + path.value());
        Files.write(sourceFile, content);
        String key = RemoteKeys.keyFor(prefix, path);
        remoteStorage.put(sourceFile, key, storageClass);
        return key;
    }
}
