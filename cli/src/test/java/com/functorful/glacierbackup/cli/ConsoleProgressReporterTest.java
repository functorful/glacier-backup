package com.functorful.glacierbackup.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.functorful.glacierbackup.application.port.Log;
import com.functorful.glacierbackup.application.port.RemoteStorageException;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class ConsoleProgressReporterTest {

    private static final String KEY = "media/a.bin";
    private static final String WRAPPER_MESSAGE = "Failed to put object: " + KEY;
    private static final String ROOT_CAUSE_MESSAGE = "Last write attempt timed out";

    private final RecordingLog log = new RecordingLog();
    private final ConsoleProgressReporter reporter = new ConsoleProgressReporter(log, Clock.systemUTC());

    @Test
    void failedLineIncludesRootCauseClassAndMessage() {
        RemoteStorageException error =
                new RemoteStorageException(WRAPPER_MESSAGE, new CompletionException(new IOException(ROOT_CAUSE_MESSAGE)));

        reporter.failed(KEY, error);

        assertThat(log.errorLines)
                .containsExactly(KEY + " FAILED (" + WRAPPER_MESSAGE + " (caused by IOException: " + ROOT_CAUSE_MESSAGE + "))");
    }

    @Test
    void failedLineWithoutCauseKeepsWrapperMessage() {
        reporter.failed(KEY, new RemoteStorageException(WRAPPER_MESSAGE));

        assertThat(log.errorLines).containsExactly(KEY + " FAILED (" + WRAPPER_MESSAGE + ")");
    }

    private static final class RecordingLog implements Log {

        private final List<String> errorLines = new ArrayList<>();

        @Override
        public void info(String message) {}

        @Override
        public void warn(String message) {}

        @Override
        public void error(String message) {
            errorLines.add(message);
        }

        @Override
        public void debug(String message) {}
    }
}
