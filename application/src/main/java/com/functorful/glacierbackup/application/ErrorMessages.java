package com.functorful.glacierbackup.application;

/**
 * Renders a throwable as a single log-friendly line. Wrapper exceptions such as
 * {@code RemoteStorageException} carry a fixed message, so the root cause is the
 * only part that says what actually went wrong.
 */
public final class ErrorMessages {

    private ErrorMessages() {}

    public static String describe(Throwable error) {
        Throwable rootCause = rootCauseOf(error);
        if (rootCause == error) {
            return messageOf(error);
        }
        return messageOf(error) + " (caused by " + rootCause.getClass().getSimpleName() + ": " + messageOf(rootCause) + ")";
    }

    static Throwable rootCauseOf(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message != null ? message : error.getClass().getSimpleName();
    }
}
