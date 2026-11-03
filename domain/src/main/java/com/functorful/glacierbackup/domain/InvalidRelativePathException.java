package com.functorful.glacierbackup.domain;

public class InvalidRelativePathException extends RuntimeException {

    public InvalidRelativePathException(String raw, String reason) {
        super("Invalid relative path '%s': %s".formatted(raw, reason));
    }
}
