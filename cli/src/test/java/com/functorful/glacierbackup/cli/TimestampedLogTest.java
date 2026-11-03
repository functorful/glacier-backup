package com.functorful.glacierbackup.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class TimestampedLogTest {

    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-27T14:03:21Z"), ZONE);
    private static final String TIMESTAMP =
            LocalDateTime.now(FIXED_CLOCK).format(TimestampedLog.TIMESTAMP_FORMAT);
    private static final String MESSAGE = "backup/keep.txt ... done";

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private TimestampedLog log(boolean verbose) {
        return new TimestampedLog(new PrintWriter(out), new PrintWriter(err), FIXED_CLOCK, verbose);
    }

    @Test
    void infoWritesTimestampedLineToOut() {
        log(false).info(MESSAGE);

        assertThat(out.toString())
                .isEqualTo("[" + TIMESTAMP + "] " + TimestampedLog.LEVEL_INFO + " " + MESSAGE + System.lineSeparator());
        assertThat(err.toString()).as("info must not write to err").isEmpty();
    }

    @Test
    void warnWritesTimestampedLineToErr() {
        log(false).warn(MESSAGE);

        assertThat(err.toString())
                .isEqualTo("[" + TIMESTAMP + "] " + TimestampedLog.LEVEL_WARN + " " + MESSAGE + System.lineSeparator());
        assertThat(out.toString()).as("warn must not write to out").isEmpty();
    }

    @Test
    void errorWritesTimestampedLineToErr() {
        log(false).error(MESSAGE);

        assertThat(err.toString())
                .isEqualTo("[" + TIMESTAMP + "] " + TimestampedLog.LEVEL_ERROR + " " + MESSAGE + System.lineSeparator());
    }

    @Test
    void debugWritesTimestampedLineToErrWhenVerbose() {
        log(true).debug(MESSAGE);

        assertThat(err.toString())
                .isEqualTo("[" + TIMESTAMP + "] " + TimestampedLog.LEVEL_DEBUG + " " + MESSAGE + System.lineSeparator());
    }

    @Test
    void debugIsSuppressedWhenNotVerbose() {
        log(false).debug(MESSAGE);

        assertThat(out.toString()).as("suppressed debug must not write to out").isEmpty();
        assertThat(err.toString()).as("suppressed debug must not write to err").isEmpty();
    }
}
