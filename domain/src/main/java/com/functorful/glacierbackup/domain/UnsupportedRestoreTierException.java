package com.functorful.glacierbackup.domain;

public class UnsupportedRestoreTierException extends RuntimeException {

    public UnsupportedRestoreTierException(RestoreTier tier, StorageClass storageClass) {
        super("Restore tier %s is not supported for storage class %s".formatted(tier, storageClass));
    }
}
