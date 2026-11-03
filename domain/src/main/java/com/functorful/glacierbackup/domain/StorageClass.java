package com.functorful.glacierbackup.domain;

public enum StorageClass {
    STANDARD,
    GLACIER_IR,
    GLACIER,
    DEEP_ARCHIVE;

    public boolean requiresRestore() {
        return this == GLACIER || this == DEEP_ARCHIVE;
    }
}
