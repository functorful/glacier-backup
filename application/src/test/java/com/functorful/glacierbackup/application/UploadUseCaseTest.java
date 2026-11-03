package com.functorful.glacierbackup.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.functorful.glacierbackup.application.port.ProgressReporter;
import com.functorful.glacierbackup.application.testing.FakeRemoteStorage;
import com.functorful.glacierbackup.application.testing.RecordingProgressReporter;
import com.functorful.glacierbackup.domain.RelativePath;
import com.functorful.glacierbackup.domain.StorageClass;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploadUseCaseTest {

    private static final String REMOTE_PREFIX = "media";
    private static final byte[] CONTENT = "glacier-backup".getBytes();
    private static final RelativePath EXISTING_FILE_NAME = RelativePath.of("existing.bin");
    private static final RelativePath MISSING_FILE_NAME = RelativePath.of("missing.bin");

    @Test
    void uploadsExistingFilesUnderComputedKey(@TempDir Path tempDir) throws IOException {
        Path file = writeLocalFile(tempDir, EXISTING_FILE_NAME, CONTENT);
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        UploadUseCase useCase = new UploadUseCase(remoteStorage, ProgressReporter.noOp());

        UploadResult result = useCase.run(List.of(file), REMOTE_PREFIX, StorageClass.GLACIER);

        String expectedKey = RemoteKeys.keyFor(REMOTE_PREFIX, EXISTING_FILE_NAME);
        assertThat(result.uploadedKeys()).containsExactly(expectedKey);
        assertThat(result.hasFailures()).as("upload of an existing file must not report failures").isFalse();
        assertThat(remoteStorage.storedObjects().get(expectedKey).storageClass()).isEqualTo(StorageClass.GLACIER);
    }

    @Test
    void reportsFailureForMissingLocalFileWithoutThrowing(@TempDir Path tempDir) {
        Path missingFile = tempDir.resolve(MISSING_FILE_NAME.value());
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        UploadUseCase useCase = new UploadUseCase(remoteStorage, ProgressReporter.noOp());

        UploadResult result = useCase.run(List.of(missingFile), REMOTE_PREFIX, StorageClass.STANDARD);

        String expectedKey = RemoteKeys.keyFor(REMOTE_PREFIX, MISSING_FILE_NAME);
        assertThat(result.uploadedKeys()).as("a missing local file must not be uploaded").isEmpty();
        assertThat(result.failures()).extracting(FailedItem::key).containsExactly(expectedKey);
        assertThat(result.hasFailures()).as("a missing local file must be reported as a failure").isTrue();
    }

    @Test
    void reportsProgressForSuccessAndMissingFile(@TempDir Path tempDir) throws IOException {
        Path existingFile = writeLocalFile(tempDir, EXISTING_FILE_NAME, CONTENT);
        Path missingFile = tempDir.resolve(MISSING_FILE_NAME.value());
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        RecordingProgressReporter progressReporter = new RecordingProgressReporter();
        UploadUseCase useCase = new UploadUseCase(remoteStorage, progressReporter);

        useCase.run(List.of(missingFile, existingFile), REMOTE_PREFIX, StorageClass.STANDARD);

        String missingKey = RemoteKeys.keyFor(REMOTE_PREFIX, MISSING_FILE_NAME);
        String existingKey = RemoteKeys.keyFor(REMOTE_PREFIX, EXISTING_FILE_NAME);
        assertThat(progressReporter.failedKeys()).as("a missing local file must be reported as failed").containsExactly(missingKey);
        assertThat(progressReporter.completedKeys()).as("an existing local file must be reported as completed").containsExactly(existingKey);
    }

    private static Path writeLocalFile(Path root, RelativePath path, byte[] content) throws IOException {
        Path file = root.resolve(path.value());
        Files.write(file, content);
        return file;
    }
}
