package com.functorful.glacierbackup.application;

import com.functorful.glacierbackup.application.port.RemoteStorage;
import com.functorful.glacierbackup.application.port.RemoteStorageException;
import com.functorful.glacierbackup.domain.RemoteObject;
import com.functorful.glacierbackup.domain.RestoreState;
import java.util.ArrayList;
import java.util.List;

public final class RestoreStatusUseCase {

    private final RemoteStorage remoteStorage;

    public RestoreStatusUseCase(RemoteStorage remoteStorage) {
        this.remoteStorage = remoteStorage;
    }

    public List<RestoreStatusLine> run(String prefix) {
        List<RestoreStatusLine> lines = new ArrayList<>();
        for (RemoteObject object : remoteStorage.list(prefix).all()) {
            if (!object.storageClass().requiresRestore()) {
                continue;
            }
            String key = RemoteKeys.keyFor(prefix, object.path());
            try {
                RestoreState state = remoteStorage.restoreState(key);
                lines.add(new RestoreStatusLine(key, object.storageClass(), state));
            } catch (RemoteStorageException e) {
                continue;
            }
        }
        return List.copyOf(lines);
    }
}
