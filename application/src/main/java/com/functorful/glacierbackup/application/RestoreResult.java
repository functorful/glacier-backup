package com.functorful.glacierbackup.application;

import java.util.List;

public record RestoreResult(List<String> requestedKeys, List<FailedItem> failures) {

    public boolean hasFailures() {
        return !failures.isEmpty();
    }
}
