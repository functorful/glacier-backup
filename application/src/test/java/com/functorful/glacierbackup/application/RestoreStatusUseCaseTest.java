package com.functorful.glacierbackup.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.functorful.glacierbackup.application.testing.FakeRemoteStorage;
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

class RestoreStatusUseCaseTest {

    private static final String REMOTE_PREFIX = "media";
    private static final byte[] CONTENT = "glacier-backup".getBytes();
    private static final RelativePath STANDARD_FILE = RelativePath.of("standard.bin");
    private static final RelativePath GLACIER_FILE = RelativePath.of("glacier.bin");
    private static final RelativePath DEEP_ARCHIVE_FILE = RelativePath.of("deep-archive.bin");
    private static final RelativePath GLACIER_FILE_A = RelativePath.of("glacier-a.bin");
    private static final RelativePath GLACIER_FILE_B = RelativePath.of("glacier-b.bin");
    private static final RelativePath GLACIER_FILE_C = RelativePath.of("glacier-c.bin");

    @Test
    void listsOnlyArchivedObjectsWithTheirRestoreState(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        seedRemoteObject(remoteStorage, tempDir, STANDARD_FILE, StorageClass.STANDARD);
        String glacierKey = seedRemoteObject(remoteStorage, tempDir, GLACIER_FILE, StorageClass.GLACIER);
        remoteStorage.seedRestoreState(glacierKey, RestoreState.inProgress());
        String deepArchiveKey = seedRemoteObject(remoteStorage, tempDir, DEEP_ARCHIVE_FILE, StorageClass.DEEP_ARCHIVE);
        Instant readyUntil = Instant.parse("2026-08-30T00:00:00Z");
        remoteStorage.seedRestoreState(deepArchiveKey, RestoreState.readyUntil(readyUntil));
        RestoreStatusUseCase useCase = new RestoreStatusUseCase(remoteStorage);

        List<RestoreStatusLine> lines = useCase.run(REMOTE_PREFIX);

        assertThat(lines).extracting(RestoreStatusLine::key).as("STANDARD objects do not require restore").containsExactlyInAnyOrder(glacierKey, deepArchiveKey);
        assertThat(lines)
                .filteredOn(line -> line.key().equals(glacierKey))
                .extracting(RestoreStatusLine::state)
                .containsExactly(RestoreState.inProgress());
        assertThat(lines)
                .filteredOn(line -> line.key().equals(deepArchiveKey))
                .extracting(RestoreStatusLine::state)
                .containsExactly(RestoreState.readyUntil(readyUntil));
    }

    @Test
    void skipsObjectWhoseRestoreStateFailsAndReturnsPartialListing(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        String keyA = seedRemoteObject(remoteStorage, tempDir, GLACIER_FILE_A, StorageClass.GLACIER);
        String keyB = seedRemoteObject(remoteStorage, tempDir, GLACIER_FILE_B, StorageClass.GLACIER);
        String keyC = seedRemoteObject(remoteStorage, tempDir, GLACIER_FILE_C, StorageClass.GLACIER);
        remoteStorage.failOnKey(keyB);
        RestoreStatusUseCase useCase = new RestoreStatusUseCase(remoteStorage);

        List<RestoreStatusLine> lines = useCase.run(REMOTE_PREFIX);

        assertThat(lines)
                .extracting(RestoreStatusLine::key)
                .as("a restoreState failure on one object must not prevent listing the others")
                .containsExactlyInAnyOrder(keyA, keyC);
    }

    private static String seedRemoteObject(FakeRemoteStorage remoteStorage, Path tempDir, RelativePath path, StorageClass storageClass)
            throws IOException {
        Path sourceFile = tempDir.resolve("seed-" + path.value());
        Files.write(sourceFile, CONTENT);
        String key = RemoteKeys.keyFor(REMOTE_PREFIX, path);
        remoteStorage.put(sourceFile, key, storageClass);
        Files.delete(sourceFile);
        return key;
    }
}
