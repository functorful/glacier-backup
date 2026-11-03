package com.functorful.glacierbackup.application;

import java.util.List;

public record DeleteResult(List<String> deletedKeys, List<FailedItem> failures) {

    public boolean hasFailures() {
        return !failures.isEmpty();
    }
}
