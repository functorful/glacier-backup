package com.functorful.glacierbackup.application;

import com.functorful.glacierbackup.domain.SyncPlan;
import java.util.List;

public record SyncResult(SyncPlan plan, List<String> uploadedKeys, List<String> deletedKeys, List<FailedItem> failures, boolean dryRun) {

    public boolean hasFailures() {
        return !failures.isEmpty();
    }
}
