package com.functorful.glacierbackup.domain;

public record SyncSummary(int uploadCount, int deleteCount, int skipCount, long bytesToUpload) {}
