package com.functorful.glacierbackup.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

public final class LocalSnapshot {

    private final TreeMap<RelativePath, LocalFile> filesByPath;

    private LocalSnapshot(TreeMap<RelativePath, LocalFile> filesByPath) {
        this.filesByPath = filesByPath;
    }

    public static LocalSnapshot of(Collection<LocalFile> files) {
        TreeMap<RelativePath, LocalFile> filesByPath = new TreeMap<>();
        for (LocalFile file : files) {
            if (filesByPath.putIfAbsent(file.path(), file) != null) {
                throw new IllegalArgumentException("Duplicate path in snapshot: " + file.path().value());
            }
        }
        return new LocalSnapshot(filesByPath);
    }

    public Optional<LocalFile> find(RelativePath path) {
        return Optional.ofNullable(filesByPath.get(path));
    }

    public List<LocalFile> all() {
        return List.copyOf(filesByPath.values());
    }

    public int size() {
        return filesByPath.size();
    }
}
