package com.functorful.glacierbackup.domain;

public class RestoreNotApplicableException extends RuntimeException {

    public RestoreNotApplicableException(StorageClass storageClass) {
        super("Storage class %s does not require restore".formatted(storageClass));
    }
}
