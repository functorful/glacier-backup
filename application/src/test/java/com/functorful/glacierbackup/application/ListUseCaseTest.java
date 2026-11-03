package com.functorful.glacierbackup.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.functorful.glacierbackup.application.testing.FakeRemoteStorage;
import com.functorful.glacierbackup.domain.RelativePath;
import com.functorful.glacierbackup.domain.RestoreState;
import com.functorful.glacierbackup.domain.RestoreTier;
import com.functorful.glacierbackup.domain.StorageClass;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ListUseCaseTest {

    private static final String REMOTE_PREFIX = "media";
    private static final byte[] CONTENT = "glacier-backup".getBytes();
    private static final RelativePath STANDARD_FILE = RelativePath.of("standard.bin");
    private static final RelativePath ARCHIVED_FILE = RelativePath.of("archived.bin");
    private static final int RESTORE_DAYS = 3;

    @Test
    void listsPlainWithoutFetchingRestoreState(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        seedRemoteObject(remoteStorage, tempDir, STANDARD_FILE, StorageClass.STANDARD);
        seedRemoteObject(remoteStorage, tempDir, ARCHIVED_FILE, StorageClass.GLACIER);
        ListUseCase useCase = new ListUseCase(remoteStorage);

        List<ListingEntry> entries = useCase.run(REMOTE_PREFIX, false);

        assertThat(entries).hasSize(2);
        assertThat(entries).allSatisfy(entry -> assertThat(entry.restoreState()).isEmpty());
        assertThat(remoteStorage.restoreStateCallCount())
                .as("plain listing must not fetch restore state")
                .isZero();
    }

    @Test
    void listsWithRestoreStateOnlyForObjectsRequiringRestore(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        seedRemoteObject(remoteStorage, tempDir, STANDARD_FILE, StorageClass.STANDARD);
        String archivedKey = seedRemoteObject(remoteStorage, tempDir, ARCHIVED_FILE, StorageClass.GLACIER);
        remoteStorage.initiateRestore(archivedKey, RestoreTier.STANDARD, RESTORE_DAYS);
        ListUseCase useCase = new ListUseCase(remoteStorage);

        List<ListingEntry> entries = useCase.run(REMOTE_PREFIX, true);

        String standardKey = RemoteKeys.keyFor(REMOTE_PREFIX, STANDARD_FILE);
        ListingEntry standardEntry = entryFor(entries, standardKey);
        ListingEntry archivedEntry = entryFor(entries, archivedKey);
        assertThat(standardEntry.restoreState()).as("STANDARD objects must not have a restore state fetched").isEmpty();
        assertThat(archivedEntry.restoreState()).contains(RestoreState.inProgress());
    }

    private static ListingEntry entryFor(List<ListingEntry> entries, String key) {
        return entries.stream()
                .filter(entry -> entry.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No listing entry found for key: " + key));
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
