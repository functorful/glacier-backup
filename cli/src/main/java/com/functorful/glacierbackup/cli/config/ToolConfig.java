package com.functorful.glacierbackup.cli.config;

import com.functorful.glacierbackup.domain.StorageClass;
import java.util.Map;
import java.util.OptionalInt;

public record ToolConfig(String bucket, String region, StorageClass defaultStorageClass, OptionalInt scanThreads,
        Map<String, BackupSet> backupSets) {

    public ToolConfig(String bucket, String region, StorageClass defaultStorageClass, Map<String, BackupSet> backupSets) {
        this(bucket, region, defaultStorageClass, OptionalInt.empty(), backupSets);
    }
}
