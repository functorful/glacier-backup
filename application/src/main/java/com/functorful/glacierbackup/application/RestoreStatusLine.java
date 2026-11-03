package com.functorful.glacierbackup.application;

import com.functorful.glacierbackup.domain.RestoreState;
import com.functorful.glacierbackup.domain.StorageClass;

public record RestoreStatusLine(String key, StorageClass storageClass, RestoreState state) {}
