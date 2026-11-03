package com.functorful.glacierbackup.application.testing;

import com.functorful.glacierbackup.application.RemoteKeys;
import com.functorful.glacierbackup.application.port.RemoteStorage;
import com.functorful.glacierbackup.application.port.RemoteStorageException;
import com.functorful.glacierbackup.domain.InvalidRelativePathException;
import com.functorful.glacierbackup.domain.RelativePath;
import com.functorful.glacierbackup.domain.RemoteObject;
import com.functorful.glacierbackup.domain.RemoteSnapshot;
import com.functorful.glacierbackup.domain.RestoreState;
import com.functorful.glacierbackup.domain.RestoreTier;
import com.functorful.glacierbackup.domain.StorageClass;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class FakeRemoteStorage implements RemoteStorage {

    public record StoredObject(byte[] bytes, StorageClass storageClass, Instant lastModifiedAt, RestoreState restoreState) {}

    public record RestoreCall(String key, RestoreTier tier, int days) {}

    private final Map<String, StoredObject> objectsByKey = new LinkedHashMap<>();
    private final Set<String> armedFailureKeys = new HashSet<>();
    private final Map<String, Integer> armedFailureCountsByKey = new LinkedHashMap<>();
    private final Set<String> armedFailureListPrefixes = new HashSet<>();
    private final List<RestoreCall> restoreCalls = new ArrayList<>();
    private int restoreStateCallCount = 0;

    public void failOnKey(String key) {
        armedFailureKeys.add(key);
    }

    /** Arms the next {@code times} operations on {@code key} to fail; later ones succeed. */
    public void failOnKey(String key, int times) {
        armedFailureCountsByKey.merge(key, times, Integer::sum);
    }

    public void failOnListPrefix(String prefix) {
        armedFailureListPrefixes.add(prefix);
    }

    public Map<String, StoredObject> storedObjects() {
        return Map.copyOf(objectsByKey);
    }

    public int restoreStateCallCount() {
        return restoreStateCallCount;
    }

    public List<RestoreCall> restoreCalls() {
        return List.copyOf(restoreCalls);
    }

    public void seedRestoreState(String key, RestoreState restoreState) {
        StoredObject existing = requireStored(key);
        objectsByKey.put(key, new StoredObject(existing.bytes(), existing.storageClass(), existing.lastModifiedAt(), restoreState));
    }

    @Override
    public RemoteSnapshot list(String prefix) {
        maybeFailList(prefix);
        List<RemoteObject> objects = new ArrayList<>();
        for (Map.Entry<String, StoredObject> entry : objectsByKey.entrySet()) {
            String key = entry.getKey();
            if (!RemoteKeys.belongsToPrefix(key, prefix)) {
                continue;
            }
            RelativePath path;
            try {
                path = RemoteKeys.pathFor(prefix, key);
            } catch (IllegalArgumentException | InvalidRelativePathException e) {
                continue;
            }
            StoredObject stored = entry.getValue();
            objects.add(new RemoteObject(path, stored.bytes().length, stored.lastModifiedAt(), stored.storageClass(), stored.restoreState()));
        }
        return RemoteSnapshot.of(objects);
    }

    @Override
    public Optional<RemoteObject> stat(String key) {
        maybeFail(key);
        StoredObject stored = objectsByKey.get(key);
        if (stored == null) {
            return Optional.empty();
        }
        RelativePath path = RelativePath.of(key);
        return Optional.of(new RemoteObject(path, stored.bytes().length, stored.lastModifiedAt(), stored.storageClass(), stored.restoreState()));
    }

    @Override
    public void put(Path sourceFile, String key, StorageClass storageClass) {
        maybeFail(key);
        try {
            byte[] bytes = Files.readAllBytes(sourceFile);
            Instant lastModifiedAt = Files.getLastModifiedTime(sourceFile).toInstant();
            objectsByKey.put(key, new StoredObject(bytes, storageClass, lastModifiedAt, RestoreState.notStarted()));
        } catch (IOException e) {
            throw new RemoteStorageException("Failed to read source file: " + sourceFile, e);
        }
    }

    @Override
    public void delete(String key) {
        maybeFail(key);
        objectsByKey.remove(key);
    }

    @Override
    public void initiateRestore(String key, RestoreTier tier, int days) {
        maybeFail(key);
        StoredObject existing = requireStored(key);
        objectsByKey.put(key, new StoredObject(existing.bytes(), existing.storageClass(), existing.lastModifiedAt(), RestoreState.inProgress()));
        restoreCalls.add(new RestoreCall(key, tier, days));
    }

    @Override
    public RestoreState restoreState(String key) {
        maybeFail(key);
        restoreStateCallCount++;
        return requireStored(key).restoreState();
    }

    @Override
    public void download(String key, Path target) {
        maybeFail(key);
        StoredObject existing = requireStored(key);
        try {
            Files.write(target, existing.bytes());
        } catch (IOException e) {
            throw new RemoteStorageException("Failed to write target file: " + target, e);
        }
    }

    private StoredObject requireStored(String key) {
        StoredObject existing = objectsByKey.get(key);
        if (existing == null) {
            throw new RemoteStorageException("No such key: " + key);
        }
        return existing;
    }

    private void maybeFail(String key) {
        Integer remaining = armedFailureCountsByKey.get(key);
        if (remaining != null) {
            if (remaining > 1) {
                armedFailureCountsByKey.put(key, remaining - 1);
            } else {
                armedFailureCountsByKey.remove(key);
            }
            throw new RemoteStorageException("Simulated failure for key: " + key);
        }
        if (armedFailureKeys.remove(key)) {
            throw new RemoteStorageException("Simulated failure for key: " + key);
        }
    }

    private void maybeFailList(String prefix) {
        if (armedFailureListPrefixes.remove(prefix)) {
            throw new RemoteStorageException("Simulated failure for prefix: " + prefix);
        }
    }
}
