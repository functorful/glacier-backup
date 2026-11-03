package com.functorful.glacierbackup.application;

import com.functorful.glacierbackup.domain.RestoreState;
import com.functorful.glacierbackup.domain.StorageClass;
import java.time.Instant;
import java.util.Optional;

public record ListingEntry(
        String key, long sizeBytes, Instant lastModifiedAt, StorageClass storageClass, Optional<RestoreState> restoreState) {}
