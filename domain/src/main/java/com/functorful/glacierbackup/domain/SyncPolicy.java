package com.functorful.glacierbackup.domain;

public record SyncPolicy(boolean deleteRemote, StorageClass storageClass, PathFilter filter) {

    public static SyncPolicy defaults() {
        return new SyncPolicy(false, StorageClass.DEEP_ARCHIVE, PathFilter.acceptAll());
    }
}
