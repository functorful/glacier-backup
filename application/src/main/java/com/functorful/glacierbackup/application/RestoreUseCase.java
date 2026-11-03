package com.functorful.glacierbackup.application;

import com.functorful.glacierbackup.application.port.RemoteStorage;
import com.functorful.glacierbackup.application.port.RemoteStorageException;
import com.functorful.glacierbackup.domain.RelativePath;
import com.functorful.glacierbackup.domain.RemoteObject;
import com.functorful.glacierbackup.domain.RestoreRequest;
import com.functorful.glacierbackup.domain.RestoreTier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class RestoreUseCase {

    private final RemoteStorage remoteStorage;

    public RestoreUseCase(RemoteStorage remoteStorage) {
        this.remoteStorage = remoteStorage;
    }

    public RestoreResult run(List<String> remotePaths, RestoreTier tier, int days, boolean recursive) {
        List<String> requestedKeys = new ArrayList<>();
        List<FailedItem> failures = new ArrayList<>();

        for (String remotePath : remotePaths) {
            try {
                if (recursive) {
                    restoreUnderPrefix(remotePath, tier, days, requestedKeys, failures);
                } else {
                    restoreExactKey(remotePath, tier, days, requestedKeys, failures);
                }
            } catch (RemoteStorageException e) {
                failures.add(new FailedItem(remotePath, e.getMessage()));
            }
        }

        return new RestoreResult(List.copyOf(requestedKeys), List.copyOf(failures));
    }

    private void restoreExactKey(String key, RestoreTier tier, int days, List<String> requestedKeys, List<FailedItem> failures) {
        Optional<RemoteObject> found = remoteStorage.stat(key);
        if (found.isEmpty()) {
            failures.add(new FailedItem(key, "Key not found: " + key));
            return;
        }
        restoreObject(key, found.get(), tier, days, requestedKeys, failures);
    }

    private void restoreUnderPrefix(String prefix, RestoreTier tier, int days, List<String> requestedKeys, List<FailedItem> failures) {
        List<RemoteObject> objects = remoteStorage.list(prefix).all();
        if (objects.isEmpty()) {
            failures.add(new FailedItem(prefix, "No objects found under prefix: " + prefix));
            return;
        }
        for (RemoteObject object : objects) {
            String key = RemoteKeys.keyFor(prefix, object.path());
            restoreObject(key, object, tier, days, requestedKeys, failures);
        }
    }

    private void restoreObject(
            String key, RemoteObject object, RestoreTier tier, int days, List<String> requestedKeys, List<FailedItem> failures) {
        try {
            RestoreRequest.of(RelativePath.of(key), object.storageClass(), tier, days);
        } catch (RuntimeException e) {
            failures.add(new FailedItem(key, e.getMessage()));
            return;
        }
        try {
            remoteStorage.initiateRestore(key, tier, days);
            requestedKeys.add(key);
        } catch (RemoteStorageException e) {
            failures.add(new FailedItem(key, e.getMessage()));
        }
    }
}
