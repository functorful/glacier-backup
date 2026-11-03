package com.functorful.glacierbackup.application;

import java.util.List;

public record UploadResult(List<String> uploadedKeys, List<FailedItem> failures) {

    public boolean hasFailures() {
        return !failures.isEmpty();
    }
}
