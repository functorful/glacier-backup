package com.functorful.glacierbackup.infrastructure.fs;

public final class LocalVolumeException extends RuntimeException {

    public LocalVolumeException(String message) {
        super(message);
    }

    public LocalVolumeException(String message, Throwable cause) {
        super(message, cause);
    }
}
