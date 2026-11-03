package com.functorful.glacierbackup.application.port;

import com.functorful.glacierbackup.domain.LocalSnapshot;
import com.functorful.glacierbackup.domain.RelativePath;
import java.nio.file.Path;
import java.util.Map;

public record LocalScanResult(LocalSnapshot snapshot, Map<RelativePath, Path> sourceOverrides, int vanishedDirectoryCount) {

    public LocalScanResult {
        sourceOverrides = Map.copyOf(sourceOverrides);
    }
}
