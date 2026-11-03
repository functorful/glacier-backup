package com.functorful.glacierbackup.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SyncPlannerTest {

    private static final int NO_VANISHED_DIRECTORIES = 0;
    private static final int SOME_VANISHED_DIRECTORIES = 2;

    private static final Instant OLDER = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant NEWER = Instant.parse("2026-06-01T00:00:00Z");
    private static final long SIZE = 100L;
    private static final long OTHER_SIZE = 200L;
    private static final RelativePath FILE_A = RelativePath.of("a.bin");
    private static final RelativePath FILE_B = RelativePath.of("b.bin");
    private static final RelativePath FILE_M = RelativePath.of("m.bin");
    private static final RelativePath FILE_Z = RelativePath.of("z.bin");
    private static final RelativePath ORPHAN_PATH = RelativePath.of("o.bin");
    private static final RelativePath INCLUDED_PATH = RelativePath.of("included.bin");
    private static final RelativePath EXCLUDED_LOCAL_PATH = RelativePath.of("excluded.txt");
    private static final RelativePath EXCLUDED_ORPHAN_PATH = RelativePath.of("orphan.txt");
    private static final String INCLUDE_BIN_GLOB = "*.bin";

    @Test
    void uploadsFileMissingRemotely() {
        SyncPlan plan = SyncPlanner.plan(
                localWith(file(FILE_A, SIZE, OLDER)), RemoteSnapshot.of(List.of()), SyncPolicy.defaults(), NO_VANISHED_DIRECTORIES);

        assertThat(plan.uploads()).extracting(u -> u.file().path()).containsExactly(FILE_A);
        assertThat(plan.uploads().getFirst().reason()).isEqualTo(SyncPlanner.REASON_NEW);
    }

    @Test
    void uploadsWhenSizeDiffers() {
        SyncPlan plan = SyncPlanner.plan(
                localWith(file(FILE_A, SIZE, OLDER)),
                remoteWith(object(FILE_A, OTHER_SIZE, OLDER, StorageClass.DEEP_ARCHIVE)),
                SyncPolicy.defaults(),
                NO_VANISHED_DIRECTORIES);

        assertThat(plan.uploads()).extracting(u -> u.file().path()).containsExactly(FILE_A);
        assertThat(plan.uploads().getFirst().reason()).isEqualTo(SyncPlanner.REASON_SIZE_DIFFERS);
    }

    @Test
    void uploadsWhenLocalIsNewer() {
        SyncPlan plan = SyncPlanner.plan(
                localWith(file(FILE_A, SIZE, NEWER)),
                remoteWith(object(FILE_A, SIZE, OLDER, StorageClass.DEEP_ARCHIVE)),
                SyncPolicy.defaults(),
                NO_VANISHED_DIRECTORIES);

        assertThat(plan.uploads()).extracting(u -> u.file().path()).containsExactly(FILE_A);
        assertThat(plan.uploads().getFirst().reason()).isEqualTo(SyncPlanner.REASON_LOCAL_NEWER);
    }

    @Test
    void skipsWhenSizeEqualAndLocalNotNewer() {
        SyncPlan plan = SyncPlanner.plan(
                localWith(file(FILE_A, SIZE, OLDER)),
                remoteWith(object(FILE_A, SIZE, NEWER, StorageClass.DEEP_ARCHIVE)),
                SyncPolicy.defaults(),
                NO_VANISHED_DIRECTORIES);

        assertThat(plan.skips()).extracting(PlannedAction.Skip::path).containsExactly(FILE_A);
        assertThat(plan.skips().getFirst().reason()).isEqualTo(SyncPlanner.REASON_UP_TO_DATE);
    }

    @Test
    void deletesRemoteOrphanOnlyWhenPolicySaysSo() {
        RemoteSnapshot remote = remoteWith(object(FILE_A, SIZE, OLDER, StorageClass.DEEP_ARCHIVE));
        SyncPlan additive = SyncPlanner.plan(LocalSnapshot.of(List.of()), remote, SyncPolicy.defaults(), NO_VANISHED_DIRECTORIES);
        SyncPlan mirroring = SyncPlanner.plan(
                LocalSnapshot.of(List.of()),
                remote,
                new SyncPolicy(true, StorageClass.DEEP_ARCHIVE, PathFilter.acceptAll()),
                NO_VANISHED_DIRECTORIES);

        assertThat(additive.deletes()).as("additive sync must never delete").isEmpty();
        assertThat(mirroring.deletes()).extracting(PlannedAction.DeleteRemote::path).containsExactly(FILE_A);
        assertThat(mirroring.deletes().getFirst().reason()).isEqualTo(SyncPlanner.REASON_REMOTE_ORPHAN);
    }

    @Test
    void filterExcludesLocalFilesAndRemoteOrphans() {
        PathFilter binOnly = PathFilter.of(List.of(INCLUDE_BIN_GLOB), List.of());
        SyncPolicy policy = new SyncPolicy(true, StorageClass.DEEP_ARCHIVE, binOnly);
        LocalSnapshot local = localWith(file(INCLUDED_PATH, SIZE, OLDER), file(EXCLUDED_LOCAL_PATH, SIZE, OLDER));
        RemoteSnapshot remote = remoteWith(object(EXCLUDED_ORPHAN_PATH, SIZE, OLDER, StorageClass.DEEP_ARCHIVE));

        SyncPlan plan = SyncPlanner.plan(local, remote, policy, NO_VANISHED_DIRECTORIES);

        assertThat(plan.uploads()).extracting(u -> u.file().path()).containsExactly(INCLUDED_PATH);
        assertThat(plan.deletes()).as("excluded remote orphan must not be deleted despite deleteRemote=true").isEmpty();
        assertThat(plan.actions()).as("excluded local file must be absent from the plan entirely").hasSize(1);
    }

    @Test
    void uploadsCarryPolicyStorageClass() {
        SyncPolicy policy = new SyncPolicy(false, StorageClass.GLACIER, PathFilter.acceptAll());

        SyncPlan plan = SyncPlanner.plan(localWith(file(FILE_A, SIZE, OLDER)), RemoteSnapshot.of(List.of()), policy, NO_VANISHED_DIRECTORIES);

        assertThat(plan.uploads().getFirst().storageClass()).isEqualTo(StorageClass.GLACIER);
    }

    @Test
    void summaryCountsAndBytes() {
        LocalSnapshot local = localWith(file(FILE_A, SIZE, OLDER), file(FILE_B, OTHER_SIZE, OLDER), file(FILE_M, SIZE, OLDER));
        RemoteSnapshot remote = remoteWith(object(FILE_M, SIZE, NEWER, StorageClass.DEEP_ARCHIVE));

        SyncPlan plan = SyncPlanner.plan(local, remote, SyncPolicy.defaults(), NO_VANISHED_DIRECTORIES);

        assertThat(plan.summary()).isEqualTo(new SyncSummary(2, 0, 1, SIZE + OTHER_SIZE));
    }

    @Test
    void emptySnapshotsYieldEmptyPlan() {
        SyncPlan plan = SyncPlanner.plan(LocalSnapshot.of(List.of()), RemoteSnapshot.of(List.of()), SyncPolicy.defaults(), NO_VANISHED_DIRECTORIES);

        assertThat(plan.actions()).as("no local files or remote objects must yield no actions").isEmpty();
        assertThat(plan.summary()).isEqualTo(new SyncSummary(0, 0, 0, 0L));
    }

    @Test
    void ordersActionsAsUploadsThenDeletesThenSkips() {
        LocalSnapshot local = localWith(file(FILE_Z, SIZE, OLDER), file(FILE_A, SIZE, OLDER), file(FILE_M, SIZE, OLDER));
        RemoteSnapshot remote = remoteWith(object(FILE_M, SIZE, NEWER, StorageClass.DEEP_ARCHIVE), object(ORPHAN_PATH, SIZE, OLDER, StorageClass.DEEP_ARCHIVE));
        SyncPolicy policy = new SyncPolicy(true, StorageClass.DEEP_ARCHIVE, PathFilter.acceptAll());

        SyncPlan plan = SyncPlanner.plan(local, remote, policy, NO_VANISHED_DIRECTORIES);

        assertThat(plan.actions())
                .as("actions must be ordered as uploads (path order), then deletes (path order), then skips")
                .containsExactly(
                        new PlannedAction.Upload(file(FILE_A, SIZE, OLDER), policy.storageClass(), SyncPlanner.REASON_NEW),
                        new PlannedAction.Upload(file(FILE_Z, SIZE, OLDER), policy.storageClass(), SyncPlanner.REASON_NEW),
                        new PlannedAction.DeleteRemote(ORPHAN_PATH, SyncPlanner.REASON_REMOTE_ORPHAN),
                        new PlannedAction.Skip(FILE_M, SyncPlanner.REASON_UP_TO_DATE));
    }

    @Test
    void refusesToPlanDeletesWhenPolicyRequestsDeleteRemoteAndScanIsIncomplete() {
        RemoteSnapshot remote = remoteWith(object(FILE_A, SIZE, OLDER, StorageClass.DEEP_ARCHIVE));
        SyncPolicy policy = new SyncPolicy(true, StorageClass.DEEP_ARCHIVE, PathFilter.acceptAll());

        assertThatThrownBy(() -> SyncPlanner.plan(LocalSnapshot.of(List.of()), remote, policy, SOME_VANISHED_DIRECTORIES))
                .as("an incomplete scan must never be allowed to plan remote deletes")
                .isInstanceOf(IncompleteScanForDeleteException.class)
                .hasMessageContaining(String.valueOf(SOME_VANISHED_DIRECTORIES));
    }

    @Test
    void plansDeletesNormallyWhenScanIsComplete() {
        RemoteSnapshot remote = remoteWith(object(FILE_A, SIZE, OLDER, StorageClass.DEEP_ARCHIVE));
        SyncPolicy policy = new SyncPolicy(true, StorageClass.DEEP_ARCHIVE, PathFilter.acceptAll());

        SyncPlan plan = SyncPlanner.plan(LocalSnapshot.of(List.of()), remote, policy, NO_VANISHED_DIRECTORIES);

        assertThat(plan.deletes()).extracting(PlannedAction.DeleteRemote::path).containsExactly(FILE_A);
    }

    @Test
    void plansNoDeletesAndDoesNotThrowWhenDeleteRemoteDisabledRegardlessOfScanCompleteness() {
        RemoteSnapshot remote = remoteWith(object(FILE_A, SIZE, OLDER, StorageClass.DEEP_ARCHIVE));

        SyncPlan plan = SyncPlanner.plan(LocalSnapshot.of(List.of()), remote, SyncPolicy.defaults(), SOME_VANISHED_DIRECTORIES);

        assertThat(plan.deletes())
                .as("additive policy on an incomplete scan must still plan zero deletes, without throwing")
                .isEmpty();
    }

    private static LocalFile file(RelativePath path, long sizeBytes, Instant modifiedAt) {
        return new LocalFile(path, sizeBytes, modifiedAt);
    }

    private static RemoteObject object(RelativePath path, long sizeBytes, Instant lastModifiedAt, StorageClass storageClass) {
        return new RemoteObject(path, sizeBytes, lastModifiedAt, storageClass, RestoreState.notStarted());
    }

    private static LocalSnapshot localWith(LocalFile... files) {
        return LocalSnapshot.of(List.of(files));
    }

    private static RemoteSnapshot remoteWith(RemoteObject... objects) {
        return RemoteSnapshot.of(List.of(objects));
    }
}
