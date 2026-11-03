package com.functorful.glacierbackup.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SyncPlanner {

    public static final String REASON_NEW = "new file, not present remotely";
    public static final String REASON_SIZE_DIFFERS = "local and remote sizes differ";
    public static final String REASON_LOCAL_NEWER = "local file modified after remote";
    public static final String REASON_UP_TO_DATE = "local and remote are in sync";
    public static final String REASON_REMOTE_ORPHAN = "remote object has no local counterpart";

    private SyncPlanner() {}

    /**
     * @param vanishedDirectoryCount how many directories the local scan that produced
     *     {@code local} had to skip mid-walk (vanished transiently, or vanished before
     *     their type could even be determined). When non-zero, {@code local}'s contents
     *     may be missing entries for reasons other than a real local deletion &mdash;
     *     planning {@link PlannedAction.DeleteRemote} actions from it would risk
     *     deleting still-real remote objects, so {@code policy.deleteRemote()} combined
     *     with a non-zero count is refused outright.
     */
    public static SyncPlan plan(LocalSnapshot local, RemoteSnapshot remote, SyncPolicy policy, int vanishedDirectoryCount) {
        if (policy.deleteRemote() && vanishedDirectoryCount > 0) {
            throw new IncompleteScanForDeleteException(vanishedDirectoryCount);
        }
        List<PlannedAction.Upload> uploads = new ArrayList<>();
        List<PlannedAction.Skip> skips = new ArrayList<>();
        for (LocalFile file : local.all()) {
            if (!policy.filter().matches(file.path())) {
                continue;
            }
            Optional<RemoteObject> counterpart = remote.find(file.path());
            if (counterpart.isEmpty()) {
                uploads.add(new PlannedAction.Upload(file, policy.storageClass(), REASON_NEW));
            } else if (counterpart.get().sizeBytes() != file.sizeBytes()) {
                uploads.add(new PlannedAction.Upload(file, policy.storageClass(), REASON_SIZE_DIFFERS));
            } else if (file.modifiedAt().isAfter(counterpart.get().lastModifiedAt())) {
                uploads.add(new PlannedAction.Upload(file, policy.storageClass(), REASON_LOCAL_NEWER));
            } else {
                skips.add(new PlannedAction.Skip(file.path(), REASON_UP_TO_DATE));
            }
        }

        List<PlannedAction.DeleteRemote> deletes = new ArrayList<>();
        if (policy.deleteRemote()) {
            for (RemoteObject object : remote.all()) {
                if (policy.filter().matches(object.path()) && local.find(object.path()).isEmpty()) {
                    deletes.add(new PlannedAction.DeleteRemote(object.path(), REASON_REMOTE_ORPHAN));
                }
            }
        }

        List<PlannedAction> actions = new ArrayList<>();
        actions.addAll(uploads);
        actions.addAll(deletes);
        actions.addAll(skips);
        return new SyncPlan(List.copyOf(actions));
    }
}
