package com.functorful.glacierbackup.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

public final class RemoteSnapshot {

    private final TreeMap<RelativePath, RemoteObject> objectsByPath;

    private RemoteSnapshot(TreeMap<RelativePath, RemoteObject> objectsByPath) {
        this.objectsByPath = objectsByPath;
    }

    public static RemoteSnapshot of(Collection<RemoteObject> objects) {
        TreeMap<RelativePath, RemoteObject> objectsByPath = new TreeMap<>();
        for (RemoteObject object : objects) {
            if (objectsByPath.putIfAbsent(object.path(), object) != null) {
                throw new IllegalArgumentException("Duplicate path in snapshot: " + object.path().value());
            }
        }
        return new RemoteSnapshot(objectsByPath);
    }

    public Optional<RemoteObject> find(RelativePath path) {
        return Optional.ofNullable(objectsByPath.get(path));
    }

    public List<RemoteObject> all() {
        return List.copyOf(objectsByPath.values());
    }

    public int size() {
        return objectsByPath.size();
    }
}
