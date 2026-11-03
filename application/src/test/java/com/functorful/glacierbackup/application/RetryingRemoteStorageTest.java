package com.functorful.glacierbackup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.functorful.glacierbackup.application.port.Log;
import com.functorful.glacierbackup.application.port.RemoteStorageException;
import com.functorful.glacierbackup.application.testing.FakeRemoteStorage;
import com.functorful.glacierbackup.domain.StorageClass;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetryingRemoteStorageTest {

    private static final String KEY = "media/a.bin";
    private static final String SOURCE_FILE_NAME = "a.bin";
    private static final byte[] CONTENT = "glacier-backup".getBytes();

    private final FakeRemoteStorage delegate = new FakeRemoteStorage();
    private final List<Duration> requestedSleeps = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final RetryingRemoteStorage storage =
            new RetryingRemoteStorage(delegate, warningRecordingLog(), requestedSleeps::add);

    @Test
    void putSucceedsOnASecondAttemptAfterATransientFailure(@TempDir Path tempDir) throws IOException {
        Path sourceFile = writeSourceFile(tempDir);
        delegate.failOnKey(KEY, 1);

        storage.put(sourceFile, KEY, StorageClass.STANDARD);

        assertThat(delegate.storedObjects()).containsKey(KEY);
        assertThat(requestedSleeps).containsExactly(RetryingRemoteStorage.BACKOFFS.get(0));
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains(KEY).contains("attempt 2/" + RetryingRemoteStorage.MAX_PUT_ATTEMPTS);
    }

    @Test
    void putGivesUpAfterMaxAttemptsAndRethrowsTheLastFailure(@TempDir Path tempDir) throws IOException {
        Path sourceFile = writeSourceFile(tempDir);
        delegate.failOnKey(KEY, RetryingRemoteStorage.MAX_PUT_ATTEMPTS);

        assertThatThrownBy(() -> storage.put(sourceFile, KEY, StorageClass.STANDARD))
                .isInstanceOf(RemoteStorageException.class);

        assertThat(delegate.storedObjects()).doesNotContainKey(KEY);
        assertThat(requestedSleeps)
                .containsExactly(RetryingRemoteStorage.BACKOFFS.get(0), RetryingRemoteStorage.BACKOFFS.get(1));
    }

    @Test
    void putDoesNotRetryAVanishedSourceFile(@TempDir Path tempDir) {
        Path vanishedSource = tempDir.resolve(SOURCE_FILE_NAME);

        assertThatThrownBy(() -> storage.put(vanishedSource, KEY, StorageClass.STANDARD))
                .isInstanceOf(RemoteStorageException.class)
                .hasRootCauseInstanceOf(NoSuchFileException.class);

        assertThat(requestedSleeps).as("a vanished source file must fail without any retry").isEmpty();
    }

    @Test
    void deleteFailuresAreNotRetried() {
        delegate.failOnKey(KEY, 2);

        assertThatThrownBy(() -> storage.delete(KEY)).isInstanceOf(RemoteStorageException.class);

        assertThat(requestedSleeps).as("only put is retried; delete must fail immediately").isEmpty();
    }

    private static Path writeSourceFile(Path tempDir) throws IOException {
        Path sourceFile = tempDir.resolve(SOURCE_FILE_NAME);
        Files.write(sourceFile, CONTENT);
        return sourceFile;
    }

    private Log warningRecordingLog() {
        return new Log() {
            @Override
            public void info(String message) {}

            @Override
            public void warn(String message) {
                warnings.add(message);
            }

            @Override
            public void error(String message) {}

            @Override
            public void debug(String message) {}
        };
    }
}
