package com.functorful.glacierbackup.application;

import com.functorful.glacierbackup.application.port.RemoteStorage;
import com.functorful.glacierbackup.domain.RemoteObject;
import com.functorful.glacierbackup.domain.RestoreState;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ListUseCase {

    private final RemoteStorage remoteStorage;

    public ListUseCase(RemoteStorage remoteStorage) {
        this.remoteStorage = remoteStorage;
    }

    public List<ListingEntry> run(String prefix, boolean withRestoreState) {
        List<ListingEntry> entries = new ArrayList<>();
        for (RemoteObject object : remoteStorage.list(prefix).all()) {
            String key = RemoteKeys.keyFor(prefix, object.path());
            Optional<RestoreState> restoreState =
                    withRestoreState && object.storageClass().requiresRestore()
                            ? Optional.of(remoteStorage.restoreState(key))
                            : Optional.empty();
            entries.add(new ListingEntry(key, object.sizeBytes(), object.lastModifiedAt(), object.storageClass(), restoreState));
        }
        return List.copyOf(entries);
    }
}
