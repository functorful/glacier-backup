package com.functorful.glacierbackup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.functorful.glacierbackup.application.port.LocalScanResult;
import com.functorful.glacierbackup.application.port.LocalVolume;
import com.functorful.glacierbackup.application.port.ProgressReporter;
import com.functorful.glacierbackup.application.port.RemoteStorage;
import com.functorful.glacierbackup.application.port.RemoteStorageException;
import com.functorful.glacierbackup.application.testing.FakeLocalVolume;
import com.functorful.glacierbackup.application.testing.FakeRemoteStorage;
import com.functorful.glacierbackup.application.testing.RecordingProgressReporter;
import com.functorful.glacierbackup.domain.LocalFile;
import com.functorful.glacierbackup.domain.LocalSnapshot;
import com.functorful.glacierbackup.domain.PathFilter;
import com.functorful.glacierbackup.domain.RelativePath;
import com.functorful.glacierbackup.domain.RemoteObject;
import com.functorful.glacierbackup.domain.RemoteSnapshot;
import com.functorful.glacierbackup.domain.RestoreState;
import com.functorful.glacierbackup.domain.RestoreTier;
import com.functorful.glacierbackup.domain.StorageClass;
import com.functorful.glacierbackup.domain.SyncPolicy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncUseCaseTest {

    private static final String REMOTE_PREFIX = "media";
    private static final byte[] CONTENT = "glacier-backup".getBytes();
    private static final byte[] WRONG_CONTENT = "wrong-file-should-not-be-uploaded".getBytes();
    private static final RelativePath FILE_A = RelativePath.of("a-fails.bin");
    private static final RelativePath FILE_B = RelativePath.of("b-succeeds.bin");
    private static final RelativePath SANITIZED_PATH = RelativePath.of("Caf�.mov");
    private static final String OVERRIDE_SOURCE_FILE_NAME = "override-source.bin";
    private static final long OVERLAP_TIMEOUT_SECONDS = 5;
    private static final String SCAN_FAILURE_MESSAGE = "simulated scan failure";
    private static final int VANISHED_DIRECTORY_COUNT = 2;
    private static final int NO_VANISHED_DIRECTORIES = 0;

    @Test
    void uploadsPlannedFilesWithCorrectKeysAndStorageClass(@TempDir Path tempDir) throws IOException {
        writeLocalFile(tempDir, FILE_A, CONTENT);
        LocalSnapshot local = LocalSnapshot.of(List.of(new LocalFile(FILE_A, CONTENT.length, Instant.now())));
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        SyncPolicy policy = new SyncPolicy(false, StorageClass.GLACIER, PathFilter.acceptAll());
        SyncUseCase useCase = new SyncUseCase(FakeLocalVolume.withSnapshot(local), remoteStorage, ProgressReporter.noOp());

        SyncResult result = useCase.run(tempDir, REMOTE_PREFIX, policy, false);

        String expectedKey = RemoteKeys.keyFor(REMOTE_PREFIX, FILE_A);
        assertThat(result.uploadedKeys()).containsExactly(expectedKey);
        assertThat(remoteStorage.storedObjects().get(expectedKey).storageClass()).isEqualTo(StorageClass.GLACIER);
    }

    @Test
    void dryRunLeavesRemoteStorageUntouched(@TempDir Path tempDir) throws IOException {
        writeLocalFile(tempDir, FILE_A, CONTENT);
        LocalSnapshot local = LocalSnapshot.of(List.of(new LocalFile(FILE_A, CONTENT.length, Instant.now())));
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        SyncUseCase useCase = new SyncUseCase(FakeLocalVolume.withSnapshot(local), remoteStorage, ProgressReporter.noOp());

        SyncResult result = useCase.run(tempDir, REMOTE_PREFIX, SyncPolicy.defaults(), true);

        assertThat(result.dryRun()).as("dry run flag must reflect the requested mode").isTrue();
        assertThat(result.plan().uploads()).hasSize(1);
        assertThat(result.uploadedKeys()).as("dry run must not upload any files").isEmpty();
        assertThat(remoteStorage.storedObjects()).as("dry run must not mutate remote storage").isEmpty();
    }

    @Test
    void deletesOrphansOnlyWithDeleteRemotePolicy(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        seedRemoteObject(remoteStorage, tempDir, FILE_A, CONTENT);
        FakeLocalVolume localVolume = FakeLocalVolume.withSnapshot(LocalSnapshot.of(List.of()));
        SyncUseCase useCase = new SyncUseCase(localVolume, remoteStorage, ProgressReporter.noOp());

        SyncResult additiveResult = useCase.run(tempDir, REMOTE_PREFIX, SyncPolicy.defaults(), false);
        assertThat(additiveResult.deletedKeys()).as("additive policy must not delete remote orphans").isEmpty();

        SyncPolicy mirroringPolicy = new SyncPolicy(true, StorageClass.DEEP_ARCHIVE, PathFilter.acceptAll());
        SyncResult mirroringResult = useCase.run(tempDir, REMOTE_PREFIX, mirroringPolicy, false);
        String expectedKey = RemoteKeys.keyFor(REMOTE_PREFIX, FILE_A);
        assertThat(mirroringResult.deletedKeys()).containsExactly(expectedKey);
    }

    @Test
    void refusesToPlanDeletesButStillUploadsWhenScanSkippedVanishedDirectories(@TempDir Path tempDir) throws IOException {
        writeLocalFile(tempDir, FILE_B, CONTENT);
        LocalSnapshot local = LocalSnapshot.of(List.of(new LocalFile(FILE_B, CONTENT.length, Instant.now())));
        FakeLocalVolume localVolume = FakeLocalVolume.withSnapshotAndVanishedDirectories(local, VANISHED_DIRECTORY_COUNT);
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        seedRemoteObject(remoteStorage, tempDir, FILE_A, CONTENT);
        SyncPolicy mirroringPolicy = new SyncPolicy(true, StorageClass.DEEP_ARCHIVE, PathFilter.acceptAll());
        RecordingProgressReporter progressReporter = new RecordingProgressReporter();
        SyncUseCase useCase = new SyncUseCase(localVolume, remoteStorage, progressReporter);

        SyncResult result = useCase.run(tempDir, REMOTE_PREFIX, mirroringPolicy, false);

        assertThat(result.uploadedKeys())
                .as("uploads must still proceed even though deletes are refused")
                .containsExactly(RemoteKeys.keyFor(REMOTE_PREFIX, FILE_B));
        assertThat(result.deletedKeys()).as("no deletes may be executed from an incomplete scan").isEmpty();
        assertThat(result.hasFailures()).as("a refused delete plan must be reported as a failure").isTrue();
        assertThat(result.failures())
                .extracting(FailedItem::message)
                .containsExactly(SyncUseCase.REFUSING_DELETES_MESSAGE.formatted(VANISHED_DIRECTORY_COUNT));
        assertThat(result.failures())
                .extracting(FailedItem::key)
                .as("the pseudo-key must never be mistaken for a real remote key")
                .containsExactly(SyncUseCase.REFUSING_DELETES_FAILURE_KEY);
        assertThat(progressReporter.errorMessages())
                .as("the refusal must be logged as an ERROR, not merely a warning")
                .containsExactly(SyncUseCase.REFUSING_DELETES_MESSAGE.formatted(VANISHED_DIRECTORY_COUNT));
    }

    @Test
    void plansDeletesNormallyWhenScanHasNoVanishedDirectories(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        seedRemoteObject(remoteStorage, tempDir, FILE_A, CONTENT);
        FakeLocalVolume localVolume =
                FakeLocalVolume.withSnapshotAndVanishedDirectories(LocalSnapshot.of(List.of()), NO_VANISHED_DIRECTORIES);
        SyncPolicy mirroringPolicy = new SyncPolicy(true, StorageClass.DEEP_ARCHIVE, PathFilter.acceptAll());
        RecordingProgressReporter progressReporter = new RecordingProgressReporter();
        SyncUseCase useCase = new SyncUseCase(localVolume, remoteStorage, progressReporter);

        SyncResult result = useCase.run(tempDir, REMOTE_PREFIX, mirroringPolicy, false);

        assertThat(result.deletedKeys()).containsExactly(RemoteKeys.keyFor(REMOTE_PREFIX, FILE_A));
        assertThat(result.hasFailures()).as("a complete scan must not produce a refusal failure").isFalse();
        assertThat(progressReporter.errorMessages()).as("a complete scan must not log a refusal").isEmpty();
    }

    @Test
    void doesNotRefuseWhenVanishedDirectoriesButDeleteRemoteNotRequested(@TempDir Path tempDir) throws IOException {
        writeLocalFile(tempDir, FILE_B, CONTENT);
        LocalSnapshot local = LocalSnapshot.of(List.of(new LocalFile(FILE_B, CONTENT.length, Instant.now())));
        FakeLocalVolume localVolume = FakeLocalVolume.withSnapshotAndVanishedDirectories(local, VANISHED_DIRECTORY_COUNT);
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        seedRemoteObject(remoteStorage, tempDir, FILE_A, CONTENT);
        RecordingProgressReporter progressReporter = new RecordingProgressReporter();
        SyncUseCase useCase = new SyncUseCase(localVolume, remoteStorage, progressReporter);

        SyncResult result = useCase.run(tempDir, REMOTE_PREFIX, SyncPolicy.defaults(), false);

        assertThat(result.uploadedKeys()).containsExactly(RemoteKeys.keyFor(REMOTE_PREFIX, FILE_B));
        assertThat(result.deletedKeys()).as("additive policy must never delete regardless of scan completeness").isEmpty();
        assertThat(result.hasFailures()).as("no delete was requested, so nothing needs to be refused").isFalse();
        assertThat(progressReporter.errorMessages()).isEmpty();
    }

    @Test
    void collectsFailureAndContinuesProcessingRemainingItems(@TempDir Path tempDir) throws IOException {
        writeLocalFile(tempDir, FILE_A, CONTENT);
        writeLocalFile(tempDir, FILE_B, CONTENT);
        LocalSnapshot local = LocalSnapshot.of(List.of(
                new LocalFile(FILE_A, CONTENT.length, Instant.now()), new LocalFile(FILE_B, CONTENT.length, Instant.now())));
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        String failingKey = RemoteKeys.keyFor(REMOTE_PREFIX, FILE_A);
        remoteStorage.failOnKey(failingKey);
        SyncUseCase useCase = new SyncUseCase(FakeLocalVolume.withSnapshot(local), remoteStorage, ProgressReporter.noOp());

        SyncResult result = useCase.run(tempDir, REMOTE_PREFIX, SyncPolicy.defaults(), false);

        String succeedingKey = RemoteKeys.keyFor(REMOTE_PREFIX, FILE_B);
        assertThat(result.failures()).extracting(FailedItem::key).containsExactly(failingKey);
        assertThat(result.uploadedKeys()).containsExactly(succeedingKey);
        assertThat(result.hasFailures()).as("failed sync must report failures").isTrue();
    }

    @Test
    void recordsVanishedSourceFailureAndContinuesRemainingUploads(@TempDir Path tempDir) throws IOException {
        writeLocalFile(tempDir, FILE_A, CONTENT);
        writeLocalFile(tempDir, FILE_B, CONTENT);
        LocalSnapshot local = LocalSnapshot.of(List.of(
                new LocalFile(FILE_A, CONTENT.length, Instant.now()), new LocalFile(FILE_B, CONTENT.length, Instant.now())));
        FakeRemoteStorage backingStorage = new FakeRemoteStorage();
        String failingKey = RemoteKeys.keyFor(REMOTE_PREFIX, FILE_A);
        RemoteStorage vanishedSourceStorage = new UnsupportedRemoteStorage() {
            @Override
            public RemoteSnapshot list(String prefix) {
                return backingStorage.list(prefix);
            }

            @Override
            public void put(Path sourceFile, String key, StorageClass storageClass) {
                if (key.equals(failingKey)) {
                    throw new UncheckedIOException(new NoSuchFileException(sourceFile.toString()));
                }
                backingStorage.put(sourceFile, key, storageClass);
            }
        };
        SyncUseCase useCase = new SyncUseCase(FakeLocalVolume.withSnapshot(local), vanishedSourceStorage, ProgressReporter.noOp());

        SyncResult result = useCase.run(tempDir, REMOTE_PREFIX, SyncPolicy.defaults(), false);

        assertThat(result.failures())
                .as("an exception that is not a RemoteStorageException must still be recorded as a per-file failure")
                .extracting(FailedItem::key)
                .containsExactly(failingKey);
        assertThat(result.uploadedKeys()).containsExactly(RemoteKeys.keyFor(REMOTE_PREFIX, FILE_B));
    }

    @Test
    void reportsProgressForSuccessAndFailure(@TempDir Path tempDir) throws IOException {
        writeLocalFile(tempDir, FILE_A, CONTENT);
        writeLocalFile(tempDir, FILE_B, CONTENT);
        LocalSnapshot local = LocalSnapshot.of(List.of(
                new LocalFile(FILE_A, CONTENT.length, Instant.now()), new LocalFile(FILE_B, CONTENT.length, Instant.now())));
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        String failingKey = RemoteKeys.keyFor(REMOTE_PREFIX, FILE_A);
        String succeedingKey = RemoteKeys.keyFor(REMOTE_PREFIX, FILE_B);
        remoteStorage.failOnKey(failingKey);
        RecordingProgressReporter progressReporter = new RecordingProgressReporter();
        SyncUseCase useCase = new SyncUseCase(FakeLocalVolume.withSnapshot(local), remoteStorage, progressReporter);

        useCase.run(tempDir, REMOTE_PREFIX, SyncPolicy.defaults(), false);

        assertThat(progressReporter.startedKeys()).containsExactly(failingKey, succeedingKey);
        assertThat(progressReporter.completedKeys()).containsExactly(succeedingKey);
        assertThat(progressReporter.failedKeys()).containsExactly(failingKey);
    }

    @Test
    void reportsScanListAndPlanPhasesBeforeTransfers(@TempDir Path tempDir) throws IOException {
        writeLocalFile(tempDir, FILE_A, CONTENT);
        LocalSnapshot local = LocalSnapshot.of(List.of(new LocalFile(FILE_A, CONTENT.length, Instant.now())));
        RecordingProgressReporter progressReporter = new RecordingProgressReporter();
        SyncUseCase useCase = new SyncUseCase(FakeLocalVolume.withSnapshot(local), new FakeRemoteStorage(), progressReporter);

        useCase.run(tempDir, REMOTE_PREFIX, SyncPolicy.defaults(), false);

        assertThat(progressReporter.startedPhases())
                .containsExactly("scanning local root " + tempDir, "listing remote prefix '" + REMOTE_PREFIX + "'", "planning sync");
        assertThat(progressReporter.completedPhases()).containsExactly("local scan:1", "remote listing:0", "sync plan:1");
    }

    @Test
    void uploadsFromOverrideSourceInsteadOfNaivePathResolutionWhenScanRecordsOne(@TempDir Path tempDir) throws IOException {
        writeLocalFile(tempDir, SANITIZED_PATH, WRONG_CONTENT);
        Path overrideSource = tempDir.resolve(OVERRIDE_SOURCE_FILE_NAME);
        Files.write(overrideSource, CONTENT);
        LocalSnapshot local = LocalSnapshot.of(List.of(new LocalFile(SANITIZED_PATH, CONTENT.length, Instant.now())));
        FakeLocalVolume localVolume = FakeLocalVolume.withSnapshotAndOverrides(local, Map.of(SANITIZED_PATH, overrideSource));
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        SyncUseCase useCase = new SyncUseCase(localVolume, remoteStorage, ProgressReporter.noOp());

        SyncResult result = useCase.run(tempDir, REMOTE_PREFIX, SyncPolicy.defaults(), false);

        String expectedKey = RemoteKeys.keyFor(REMOTE_PREFIX, SANITIZED_PATH);
        assertThat(result.uploadedKeys()).containsExactly(expectedKey);
        assertThat(remoteStorage.storedObjects().get(expectedKey).bytes())
                .as("upload must read from the override source, not from localRoot.resolve(path.value())")
                .isEqualTo(CONTENT);
    }

    @Test
    void remoteListingStartsBeforeLocalScanFinishes(@TempDir Path tempDir) {
        CountDownLatch listStarted = new CountDownLatch(1);
        FakeRemoteStorage backingStorage = new FakeRemoteStorage();
        RemoteStorage listSignallingStorage = new UnsupportedRemoteStorage() {
            @Override
            public RemoteSnapshot list(String prefix) {
                listStarted.countDown();
                return backingStorage.list(prefix);
            }
        };
        AtomicBoolean listingStartedDuringScan = new AtomicBoolean(false);
        LocalVolume listAwaitingVolume = (root, filter) -> {
            try {
                listingStartedDuringScan.set(listStarted.await(OVERLAP_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new LocalScanResult(LocalSnapshot.of(List.of()), Map.of(), 0);
        };
        SyncUseCase useCase = new SyncUseCase(listAwaitingVolume, listSignallingStorage, ProgressReporter.noOp());

        useCase.run(tempDir, REMOTE_PREFIX, SyncPolicy.defaults(), true);

        assertThat(listingStartedDuringScan)
                .as("the remote listing must start while the local scan is still running")
                .isTrue();
    }

    @Test
    void remoteListingFailureSurfacesAsTheListingsOwnException(@TempDir Path tempDir) {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        remoteStorage.failOnListPrefix(REMOTE_PREFIX);
        SyncUseCase useCase = new SyncUseCase(
                FakeLocalVolume.withSnapshot(LocalSnapshot.of(List.of())), remoteStorage, ProgressReporter.noOp());

        assertThatThrownBy(() -> useCase.run(tempDir, REMOTE_PREFIX, SyncPolicy.defaults(), true))
                .isInstanceOf(RemoteStorageException.class)
                .hasMessageContaining(REMOTE_PREFIX);
    }

    @Test
    void scanFailurePropagatesWithoutWaitingForABlockedListing(@TempDir Path tempDir) {
        CountDownLatch neverReleased = new CountDownLatch(1);
        RemoteStorage blockedStorage = new UnsupportedRemoteStorage() {
            @Override
            public RemoteSnapshot list(String prefix) {
                try {
                    neverReleased.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return RemoteSnapshot.of(List.of());
            }
        };
        RuntimeException scanFailure = new RuntimeException(SCAN_FAILURE_MESSAGE);
        LocalVolume failingVolume = (root, filter) -> {
            throw scanFailure;
        };
        SyncUseCase useCase = new SyncUseCase(failingVolume, blockedStorage, ProgressReporter.noOp());

        assertThatThrownBy(() -> useCase.run(tempDir, REMOTE_PREFIX, SyncPolicy.defaults(), true))
                .isSameAs(scanFailure);
    }

    private static void writeLocalFile(Path root, RelativePath path, byte[] content) throws IOException {
        Files.write(root.resolve(path.value()), content);
    }

    private static void seedRemoteObject(FakeRemoteStorage remoteStorage, Path tempDir, RelativePath path, byte[] content) throws IOException {
        Path sourceFile = tempDir.resolve("seed-" + path.value());
        Files.write(sourceFile, content);
        remoteStorage.put(sourceFile, RemoteKeys.keyFor(REMOTE_PREFIX, path), StorageClass.DEEP_ARCHIVE);
        Files.delete(sourceFile);
    }

    /** Base for anonymous test doubles that only need to override {@link #list(String)}. */
    private static class UnsupportedRemoteStorage implements RemoteStorage {

        private static final String UNSUPPORTED_MESSAGE = "not expected to be called in this test";

        @Override
        public RemoteSnapshot list(String prefix) {
            throw new UnsupportedOperationException(UNSUPPORTED_MESSAGE);
        }

        @Override
        public Optional<RemoteObject> stat(String key) {
            throw new UnsupportedOperationException(UNSUPPORTED_MESSAGE);
        }

        @Override
        public void put(Path sourceFile, String key, StorageClass storageClass) {
            throw new UnsupportedOperationException(UNSUPPORTED_MESSAGE);
        }

        @Override
        public void delete(String key) {
            throw new UnsupportedOperationException(UNSUPPORTED_MESSAGE);
        }

        @Override
        public void initiateRestore(String key, RestoreTier tier, int days) {
            throw new UnsupportedOperationException(UNSUPPORTED_MESSAGE);
        }

        @Override
        public RestoreState restoreState(String key) {
            throw new UnsupportedOperationException(UNSUPPORTED_MESSAGE);
        }

        @Override
        public void download(String key, Path target) {
            throw new UnsupportedOperationException(UNSUPPORTED_MESSAGE);
        }
    }
}
