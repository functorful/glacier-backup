package com.functorful.glacierbackup.application.port;

import com.functorful.glacierbackup.domain.RemoteObject;
import com.functorful.glacierbackup.domain.RemoteSnapshot;
import com.functorful.glacierbackup.domain.RestoreState;
import com.functorful.glacierbackup.domain.RestoreTier;
import com.functorful.glacierbackup.domain.StorageClass;
import java.nio.file.Path;
import java.util.Optional;

public interface RemoteStorage {

    RemoteSnapshot list(String prefix);

    Optional<RemoteObject> stat(String key);

    /**
     * Uploads {@code sourceFile} to {@code key}. Throws {@link RemoteStorageException}
     * on any failure — including a source file that no longer exists — and must not
     * leak implementation exceptions.
     */
    void put(Path sourceFile, String key, StorageClass storageClass);

    void delete(String key);

    void initiateRestore(String key, RestoreTier tier, int days);

    RestoreState restoreState(String key);

    void download(String key, Path target);
}
