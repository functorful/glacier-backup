package com.functorful.glacierbackup.cli.config;

import com.functorful.glacierbackup.domain.PathFilter;
import java.nio.file.Path;
import java.util.List;

public record BackupSet(Path local, String prefix, List<String> include, List<String> exclude) {

    public PathFilter filter() {
        return PathFilter.of(include, exclude);
    }
}
