package com.functorful.glacierbackup.application.port;

public interface ProgressReporter {

    void started(String key, long sizeBytes);

    void completed(String key);

    void failed(String key, Exception error);

    default void phaseStarted(String description) {}

    default void phaseCompleted(String description, long itemCount) {}

    /**
     * A failure that is not tied to a single transferred item's key. No default body:
     * this exists for safety refusals (e.g. delete planning declined outright), so
     * every implementor must decide explicitly whether to surface it rather than
     * silently inheriting a no-op.
     */
    void error(String message);

    static ProgressReporter noOp() {
        return new ProgressReporter() {
            @Override
            public void started(String key, long sizeBytes) {}

            @Override
            public void completed(String key) {}

            @Override
            public void failed(String key, Exception error) {}

            @Override
            public void error(String message) {}
        };
    }
}
