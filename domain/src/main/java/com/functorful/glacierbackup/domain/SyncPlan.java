package com.functorful.glacierbackup.domain;

import java.util.List;

public record SyncPlan(List<PlannedAction> actions) {

    public List<PlannedAction.Upload> uploads() {
        return actions.stream().filter(PlannedAction.Upload.class::isInstance).map(PlannedAction.Upload.class::cast).toList();
    }

    public List<PlannedAction.DeleteRemote> deletes() {
        return actions.stream().filter(PlannedAction.DeleteRemote.class::isInstance).map(PlannedAction.DeleteRemote.class::cast).toList();
    }

    public List<PlannedAction.Skip> skips() {
        return actions.stream().filter(PlannedAction.Skip.class::isInstance).map(PlannedAction.Skip.class::cast).toList();
    }

    public SyncSummary summary() {
        List<PlannedAction.Upload> uploads = uploads();
        long bytesToUpload = uploads.stream().mapToLong(upload -> upload.file().sizeBytes()).sum();
        return new SyncSummary(uploads.size(), deletes().size(), skips().size(), bytesToUpload);
    }
}
