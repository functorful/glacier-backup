package com.functorful.glacierbackup.cli;

import com.functorful.glacierbackup.application.port.Log;
import java.io.PrintWriter;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Prefixes every line with a local-time timestamp and level, matching the
 * UNAS runner's own {@code [date '+%Y-%m-%dT%H:%M:%S']} log delimiters.
 * INFO goes to the out writer; WARN, ERROR and DEBUG go to the err writer.
 */
public final class TimestampedLog implements Log {

    public static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    public static final String LEVEL_INFO = "INFO";
    public static final String LEVEL_WARN = "WARN";
    public static final String LEVEL_ERROR = "ERROR";
    public static final String LEVEL_DEBUG = "DEBUG";

    private final PrintWriter out;
    private final PrintWriter err;
    private final Clock clock;
    private final boolean verbose;

    public TimestampedLog(PrintWriter out, PrintWriter err, Clock clock, boolean verbose) {
        this.out = out;
        this.err = err;
        this.clock = clock;
        this.verbose = verbose;
    }

    @Override
    public void info(String message) {
        write(out, LEVEL_INFO, message);
    }

    @Override
    public void warn(String message) {
        write(err, LEVEL_WARN, message);
    }

    @Override
    public void error(String message) {
        write(err, LEVEL_ERROR, message);
    }

    @Override
    public void debug(String message) {
        if (verbose) {
            write(err, LEVEL_DEBUG, message);
        }
    }

    private void write(PrintWriter writer, String level, String message) {
        writer.println("[" + LocalDateTime.now(clock).format(TIMESTAMP_FORMAT) + "] " + level + " " + message);
    }
}
