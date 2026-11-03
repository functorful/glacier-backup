package com.functorful.glacierbackup.cli;

import com.functorful.glacierbackup.application.ErrorMessages;
import com.functorful.glacierbackup.application.port.Log;
import com.functorful.glacierbackup.application.port.ProgressReporter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public final class ConsoleProgressReporter implements ProgressReporter {

    private final Log log;
    private final Clock clock;

    private Instant phaseStart;

    public ConsoleProgressReporter(Log log, Clock clock) {
        this.log = log;
        this.clock = clock;
    }

    @Override
    public void started(String key, long sizeBytes) {
        // The final outcome (done/FAILED) is not known until completed() or failed() is called,
        // so the start is only announced in verbose mode.
        log.debug("started " + key + " (" + sizeBytes + " bytes)");
    }

    @Override
    public void completed(String key) {
        log.info(key + " ... done");
    }

    @Override
    public void failed(String key, Exception error) {
        log.error(key + " FAILED (" + ErrorMessages.describe(error) + ")");
    }

    @Override
    public void phaseStarted(String description) {
        phaseStart = clock.instant();
        log.debug(description + " ...");
    }

    @Override
    public void phaseCompleted(String description, long itemCount) {
        Duration elapsed = phaseStart != null ? Duration.between(phaseStart, clock.instant()) : Duration.ZERO;
        log.debug(description + ": " + itemCount + " items in " + elapsed.toSeconds() + "s");
    }

    @Override
    public void error(String message) {
        log.error(message);
    }
}
