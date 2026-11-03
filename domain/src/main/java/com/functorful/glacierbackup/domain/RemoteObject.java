package com.functorful.glacierbackup.domain;

import java.time.Instant;

public record RemoteObject(
        RelativePath path, long sizeBytes, Instant lastModifiedAt, StorageClass storageClass, RestoreState restoreState) {

    public boolean downloadable() {
        return !storageClass.requiresRestore() || restoreState instanceof RestoreState.Ready;
    }
}
