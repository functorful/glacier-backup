package com.functorful.glacierbackup.application;

import com.functorful.glacierbackup.application.port.Log;
import com.functorful.glacierbackup.application.port.RemoteStorage;
import com.functorful.glacierbackup.application.port.RemoteStorageException;
import com.functorful.glacierbackup.domain.RemoteObject;
import com.functorful.glacierbackup.domain.RemoteSnapshot;
import com.functorful.glacierbackup.domain.RestoreState;
import com.functorful.glacierbackup.domain.RestoreTier;
import com.functorful.glacierbackup.domain.StorageClass;
import java.io.FileNotFoundException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Retries {@link #put} a bounded number of times. Uploads of multi-GB files over a
 * slow uplink are the only operation that fails transiently in practice (stalled
 * connections, write timeouts); every other operation is a cheap call already
 * covered by the SDK's own retries and delegates untouched.
 */
public final class RetryingRemoteStorage implements RemoteStorage {

    public static final int MAX_PUT_ATTEMPTS = 3;

    /**
     * First retry is cheap for blips; the second waits out congestion. A failed
     * multi-GB upload already cost minutes, so 70s of total delay is noise — and
     * the next nightly run remains the ultimate backstop.
     */
    static final List<Duration> BACKOFFS = List.of(Duration.ofSeconds(10), Duration.ofSeconds(60));

    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration);
    }

    private final RemoteStorage delegate;
    private final Log log;
    private final Sleeper sleeper;

    public RetryingRemoteStorage(RemoteStorage delegate, Log log) {
        this(delegate, log, RetryingRemoteStorage::interruptiblySleep);
    }

    RetryingRemoteStorage(RemoteStorage delegate, Log log, Sleeper sleeper) {
        this.delegate = delegate;
        this.log = log;
        this.sleeper = sleeper;
    }

    @Override
    public void put(Path sourceFile, String key, StorageClass storageClass) {
        for (int attempt = 1; ; attempt++) {
            try {
                delegate.put(sourceFile, key, storageClass);
                return;
            } catch (RemoteStorageException e) {
                if (attempt >= MAX_PUT_ATTEMPTS || !isRetryable(e)) {
                    throw e;
                }
                Duration backoff = BACKOFFS.get(attempt - 1);
                log.warn("retrying put of " + key + " (attempt " + (attempt + 1) + "/" + MAX_PUT_ATTEMPTS + ") in "
                        + backoff.toSeconds() + "s after: " + ErrorMessages.describe(e));
                sleeper.sleep(backoff);
            }
        }
    }

    /** A source file that vanished after the scan will not reappear on retry. */
    private static boolean isRetryable(RemoteStorageException error) {
        Throwable rootCause = ErrorMessages.rootCauseOf(error);
        return !(rootCause instanceof NoSuchFileException || rootCause instanceof FileNotFoundException);
    }

    private static void interruptiblySleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public RemoteSnapshot list(String prefix) {
        return delegate.list(prefix);
    }

    @Override
    public Optional<RemoteObject> stat(String key) {
        return delegate.stat(key);
    }

    @Override
    public void delete(String key) {
        delegate.delete(key);
    }

    @Override
    public void initiateRestore(String key, RestoreTier tier, int days) {
        delegate.initiateRestore(key, tier, days);
    }

    @Override
    public RestoreState restoreState(String key) {
        return delegate.restoreState(key);
    }

    @Override
    public void download(String key, Path target) {
        delegate.download(key, target);
    }
}
