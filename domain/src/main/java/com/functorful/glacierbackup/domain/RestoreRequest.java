package com.functorful.glacierbackup.domain;

public record RestoreRequest(RelativePath path, RestoreTier tier, int days) {

    public static RestoreRequest of(RelativePath path, StorageClass storageClass, RestoreTier tier, int days) {
        if (!storageClass.requiresRestore()) {
            throw new RestoreNotApplicableException(storageClass);
        }
        if (tier == RestoreTier.EXPEDITED && storageClass == StorageClass.DEEP_ARCHIVE) {
            throw new UnsupportedRestoreTierException(tier, storageClass);
        }
        if (days < 1) {
            throw new IllegalArgumentException("days must be at least 1, got %d".formatted(days));
        }
        return new RestoreRequest(path, tier, days);
    }
}
