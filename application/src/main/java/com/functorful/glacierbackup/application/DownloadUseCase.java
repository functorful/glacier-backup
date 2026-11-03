package com.functorful.glacierbackup.application;

import com.functorful.glacierbackup.application.port.ProgressReporter;
import com.functorful.glacierbackup.application.port.RemoteStorage;
import com.functorful.glacierbackup.application.port.RemoteStorageException;
import com.functorful.glacierbackup.domain.RemoteObject;
import com.functorful.glacierbackup.domain.RestoreState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class DownloadUseCase {

    private final RemoteStorage remoteStorage;
    private final ProgressReporter progressReporter;

    public DownloadUseCase(RemoteStorage remoteStorage, ProgressReporter progressReporter) {
        this.remoteStorage = remoteStorage;
        this.progressReporter = progressReporter;
    }

    public DownloadResult run(List<String> remotePaths, Path targetDir, boolean recursive) {
        List<String> downloadedKeys = new ArrayList<>();
        List<FailedItem> failures = new ArrayList<>();

        for (String remotePath : remotePaths) {
            try {
                if (recursive) {
                    downloadUnderPrefix(remotePath, targetDir, downloadedKeys, failures);
                } else {
                    downloadExactKey(remotePath, targetDir, downloadedKeys, failures);
                }
            } catch (RemoteStorageException e) {
                failures.add(new FailedItem(remotePath, e.getMessage()));
            }
        }

        return new DownloadResult(List.copyOf(downloadedKeys), List.copyOf(failures));
    }

    private void downloadExactKey(String key, Path targetDir, List<String> downloadedKeys, List<FailedItem> failures) {
        Optional<RemoteObject> found = remoteStorage.stat(key);
        if (found.isEmpty()) {
            failures.add(new FailedItem(key, "Key not found: " + key));
            return;
        }
        downloadObject(key, found.get(), targetDir, downloadedKeys, failures);
    }

    private void downloadUnderPrefix(String prefix, Path targetDir, List<String> downloadedKeys, List<FailedItem> failures) {
        List<RemoteObject> objects = remoteStorage.list(prefix).all();
        if (objects.isEmpty()) {
            failures.add(new FailedItem(prefix, "No objects found under prefix: " + prefix));
            return;
        }
        for (RemoteObject object : objects) {
            String key = RemoteKeys.keyFor(prefix, object.path());
            downloadObject(key, object, targetDir, downloadedKeys, failures);
        }
    }

    private void downloadObject(
            String key, RemoteObject object, Path targetDir, List<String> downloadedKeys, List<FailedItem> failures) {
        if (object.storageClass().requiresRestore()) {
            RestoreState state;
            try {
                state = remoteStorage.restoreState(key);
            } catch (RemoteStorageException e) {
                failures.add(new FailedItem(key, e.getMessage()));
                return;
            }
            if (!(state instanceof RestoreState.Ready)) {
                String message = "Object %s is not ready to download yet; run restore-status to check progress".formatted(key);
                failures.add(new FailedItem(key, message));
                return;
            }
        }

        Path target = targetDir.resolve(key);
        try {
            progressReporter.started(key, object.sizeBytes());
            createParentDirectories(target);
            remoteStorage.download(key, target);
            downloadedKeys.add(key);
            progressReporter.completed(key);
        } catch (RemoteStorageException e) {
            failures.add(new FailedItem(key, e.getMessage()));
            progressReporter.failed(key, e);
        }
    }

    private static void createParentDirectories(Path target) {
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
