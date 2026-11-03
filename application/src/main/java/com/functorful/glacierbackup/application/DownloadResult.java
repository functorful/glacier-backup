package com.functorful.glacierbackup.application;

import java.util.List;

public record DownloadResult(List<String> downloadedKeys, List<FailedItem> failures) {

    public boolean hasFailures() {
        return !failures.isEmpty();
    }
}
