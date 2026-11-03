package com.functorful.glacierbackup.cli;

import java.util.List;
import java.util.regex.Pattern;

/** Strips the {@link TimestampedLog} prefix so tests can assert on the bare messages. */
final class LogLines {

    private static final Pattern PREFIX = Pattern.compile("^\\[\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\] ("
            + TimestampedLog.LEVEL_INFO + "|" + TimestampedLog.LEVEL_WARN + "|" + TimestampedLog.LEVEL_ERROR + "|"
            + TimestampedLog.LEVEL_DEBUG + ") ");

    private LogLines() {}

    static List<String> messages(String output) {
        return output.lines().map(line -> PREFIX.matcher(line).replaceFirst("")).toList();
    }
}
