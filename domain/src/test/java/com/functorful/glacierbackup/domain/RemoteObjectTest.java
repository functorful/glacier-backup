package com.functorful.glacierbackup.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RemoteObjectTest {

    private static final String OBJECT_PATH = "backups/archive.tar";
    private static final long OBJECT_SIZE_BYTES = 1024L;
    private static final Instant LAST_MODIFIED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant RESTORE_READY_UNTIL = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void deepArchiveIsDownloadableOnlyWhenRestoreReady() {
        RemoteObject frozen = remoteObject(StorageClass.DEEP_ARCHIVE, RestoreState.notStarted());
        RemoteObject thawed = remoteObject(StorageClass.DEEP_ARCHIVE, RestoreState.readyUntil(RESTORE_READY_UNTIL));
        RemoteObject standard = remoteObject(StorageClass.STANDARD, RestoreState.notStarted());

        assertThat(frozen.downloadable()).as("deep-archive object without restore must not be downloadable").isFalse();
        assertThat(thawed.downloadable()).as("restored deep-archive object must be downloadable").isTrue();
        assertThat(standard.downloadable()).as("standard object is always downloadable").isTrue();
    }

    @Test
    void glacierInProgressRestoreIsNotDownloadable() {
        RemoteObject restoring = remoteObject(StorageClass.GLACIER, RestoreState.inProgress());

        assertThat(restoring.downloadable())
                .as("glacier object with an in-progress restore must not be downloadable")
                .isFalse();
    }

    @Test
    void glacierIrDoesNotRequireRestoreAndIsAlwaysDownloadable() {
        RemoteObject glacierIr = remoteObject(StorageClass.GLACIER_IR, RestoreState.notStarted());

        assertThat(glacierIr.downloadable())
                .as("glacier instant-retrieval object does not require a restore")
                .isTrue();
    }

    private static RemoteObject remoteObject(StorageClass storageClass, RestoreState restoreState) {
        return new RemoteObject(
                RelativePath.of(OBJECT_PATH), OBJECT_SIZE_BYTES, LAST_MODIFIED_AT, storageClass, restoreState);
    }
}
