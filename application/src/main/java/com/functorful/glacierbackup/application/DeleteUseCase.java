package com.functorful.glacierbackup.application;

import com.functorful.glacierbackup.application.port.RemoteStorage;
import com.functorful.glacierbackup.application.port.RemoteStorageException;
import java.util.ArrayList;
import java.util.List;

public final class DeleteUseCase {

    private final RemoteStorage remoteStorage;

    public DeleteUseCase(RemoteStorage remoteStorage) {
        this.remoteStorage = remoteStorage;
    }

    public DeleteResult run(List<String> remotePaths, boolean recursive) {
        List<String> deletedKeys = new ArrayList<>();
        List<FailedItem> failures = new ArrayList<>();

        for (String remotePath : remotePaths) {
            try {
                if (recursive) {
                    deleteUnderPrefix(remotePath, deletedKeys, failures);
                } else {
                    deleteExactKey(remotePath, deletedKeys, failures);
                }
            } catch (RemoteStorageException e) {
                failures.add(new FailedItem(remotePath, e.getMessage()));
            }
        }

        return new DeleteResult(List.copyOf(deletedKeys), List.copyOf(failures));
    }

    private void deleteExactKey(String key, List<String> deletedKeys, List<FailedItem> failures) {
        if (remoteStorage.stat(key).isEmpty()) {
            failures.add(new FailedItem(key, "Key not found: " + key));
            return;
        }
        deleteKey(key, deletedKeys, failures);
    }

    private void deleteUnderPrefix(String prefix, List<String> deletedKeys, List<FailedItem> failures) {
        List<String> keysUnderPrefix =
                remoteStorage.list(prefix).all().stream().map(object -> RemoteKeys.keyFor(prefix, object.path())).toList();
        if (keysUnderPrefix.isEmpty()) {
            failures.add(new FailedItem(prefix, "No objects found under prefix: " + prefix));
            return;
        }
        for (String key : keysUnderPrefix) {
            deleteKey(key, deletedKeys, failures);
        }
    }

    private void deleteKey(String key, List<String> deletedKeys, List<FailedItem> failures) {
        try {
            remoteStorage.delete(key);
            deletedKeys.add(key);
        } catch (RemoteStorageException e) {
            failures.add(new FailedItem(key, e.getMessage()));
        }
    }
}
