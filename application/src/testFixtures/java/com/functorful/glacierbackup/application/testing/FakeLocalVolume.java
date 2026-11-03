package com.functorful.glacierbackup.application.testing;

import com.functorful.glacierbackup.application.port.LocalScanResult;
import com.functorful.glacierbackup.application.port.LocalVolume;
import com.functorful.glacierbackup.domain.LocalFile;
import com.functorful.glacierbackup.domain.LocalSnapshot;
import com.functorful.glacierbackup.domain.PathFilter;
import com.functorful.glacierbackup.domain.RelativePath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class FakeLocalVolume implements LocalVolume {

    private final Optional<LocalSnapshot> presetSnapshot;
    private final Map<RelativePath, Path> presetOverrides;
    private final int presetVanishedDirectoryCount;

    private FakeLocalVolume(
            Optional<LocalSnapshot> presetSnapshot, Map<RelativePath, Path> presetOverrides, int presetVanishedDirectoryCount) {
        this.presetSnapshot = presetSnapshot;
        this.presetOverrides = presetOverrides;
        this.presetVanishedDirectoryCount = presetVanishedDirectoryCount;
    }

    public static FakeLocalVolume withSnapshot(LocalSnapshot snapshot) {
        return new FakeLocalVolume(Optional.of(snapshot), Map.of(), 0);
    }

    public static FakeLocalVolume withSnapshotAndOverrides(LocalSnapshot snapshot, Map<RelativePath, Path> sourceOverrides) {
        return new FakeLocalVolume(Optional.of(snapshot), Map.copyOf(sourceOverrides), 0);
    }

    public static FakeLocalVolume withSnapshotAndVanishedDirectories(LocalSnapshot snapshot, int vanishedDirectoryCount) {
        return new FakeLocalVolume(Optional.of(snapshot), Map.of(), vanishedDirectoryCount);
    }

    public static FakeLocalVolume scanningRealDirectory() {
        return new FakeLocalVolume(Optional.empty(), Map.of(), 0);
    }

    @Override
    public LocalScanResult scan(Path root, PathFilter filter) {
        if (presetSnapshot.isPresent()) {
            return new LocalScanResult(presetSnapshot.get(), presetOverrides, presetVanishedDirectoryCount);
        }
        try (Stream<Path> paths = Files.walk(root)) {
            List<LocalFile> files = new ArrayList<>();
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                RelativePath relativePath = RelativePath.of(root.relativize(path).toString());
                if (!filter.matches(relativePath)) {
                    continue;
                }
                files.add(new LocalFile(relativePath, Files.size(path), Files.getLastModifiedTime(path).toInstant()));
            }
            return new LocalScanResult(LocalSnapshot.of(files), Map.of(), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
