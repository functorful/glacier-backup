package com.functorful.glacierbackup.application.port;

public interface Log {

    void info(String message);

    void warn(String message);

    void error(String message);

    /** Emitted only in verbose mode. */
    void debug(String message);

    static Log noOp() {
        return new Log() {
            @Override
            public void info(String message) {}

            @Override
            public void warn(String message) {}

            @Override
            public void error(String message) {}

            @Override
            public void debug(String message) {}
        };
    }
}
