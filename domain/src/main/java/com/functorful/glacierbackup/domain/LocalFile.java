package com.functorful.glacierbackup.domain;

import java.time.Instant;

public record LocalFile(RelativePath path, long sizeBytes, Instant modifiedAt) {}
