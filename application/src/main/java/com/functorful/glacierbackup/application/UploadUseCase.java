package com.functorful.glacierbackup.application;

import com.functorful.glacierbackup.application.port.ProgressReporter;
import com.functorful.glacierbackup.application.port.RemoteStorage;
import com.functorful.glacierbackup.application.port.RemoteStorageException;
import com.functorful.glacierbackup.domain.RelativePath;
import com.functorful.glacierbackup.domain.StorageClass;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class UploadUseCase {

    private final RemoteStorage remoteStorage;
    private final ProgressReporter progressReporter;

    public UploadUseCase(RemoteStorage remoteStorage, ProgressReporter progressReporter) {
        this.remoteStorage = remoteStorage;
        this.progressReporter = progressReporter;
    }

    public UploadResult run(List<Path> files, String remotePrefix, StorageClass storageClass) {
        List<String> uploadedKeys = new ArrayList<>();
        List<FailedItem> failures = new ArrayList<>();

        for (Path file : files) {
            String key = RemoteKeys.keyFor(remotePrefix, RelativePath.of(file.getFileName().toString()));
            if (!Files.exists(file)) {
                String message = "Local file not found: " + file;
                progressReporter.started(key, 0L);
                failures.add(new FailedItem(key, message));
                progressReporter.failed(key, new NoSuchFileException(file.toString()));
                continue;
            }
            try {
                progressReporter.started(key, Files.size(file));
                remoteStorage.put(file, key, storageClass);
                uploadedKeys.add(key);
                progressReporter.completed(key);
            } catch (RemoteStorageException | IOException e) {
                failures.add(new FailedItem(key, e.getMessage()));
                progressReporter.failed(key, e);
            }
        }

        return new UploadResult(List.copyOf(uploadedKeys), List.copyOf(failures));
    }
}
