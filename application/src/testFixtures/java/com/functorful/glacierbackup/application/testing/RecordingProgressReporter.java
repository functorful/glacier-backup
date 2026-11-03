package com.functorful.glacierbackup.application.testing;

import com.functorful.glacierbackup.application.port.ProgressReporter;
import java.util.ArrayList;
import java.util.List;

public final class RecordingProgressReporter implements ProgressReporter {

    private final List<String> started = new ArrayList<>();
    private final List<String> completed = new ArrayList<>();
    private final List<String> failed = new ArrayList<>();
    private final List<String> phasesStarted = new ArrayList<>();
    private final List<String> phasesCompleted = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    @Override
    public void started(String key, long sizeBytes) {
        started.add(key);
    }

    @Override
    public void completed(String key) {
        completed.add(key);
    }

    @Override
    public void failed(String key, Exception error) {
        failed.add(key);
    }

    @Override
    public void phaseStarted(String description) {
        phasesStarted.add(description);
    }

    @Override
    public void phaseCompleted(String description, long itemCount) {
        phasesCompleted.add(description + ":" + itemCount);
    }

    @Override
    public void error(String message) {
        errors.add(message);
    }

    public List<String> startedKeys() {
        return List.copyOf(started);
    }

    public List<String> completedKeys() {
        return List.copyOf(completed);
    }

    public List<String> failedKeys() {
        return List.copyOf(failed);
    }

    public List<String> startedPhases() {
        return List.copyOf(phasesStarted);
    }

    /** Each entry is {@code <description>:<itemCount>}. */
    public List<String> completedPhases() {
        return List.copyOf(phasesCompleted);
    }

    public List<String> errorMessages() {
        return List.copyOf(errors);
    }
}
