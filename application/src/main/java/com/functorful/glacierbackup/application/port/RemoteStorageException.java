package com.functorful.glacierbackup.application.port;

public class RemoteStorageException extends RuntimeException {

    public RemoteStorageException(String message) {
        super(message);
    }

    public RemoteStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
