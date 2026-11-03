package com.functorful.glacierbackup.infrastructure.s3;

import com.functorful.glacierbackup.application.RemoteKeys;
import com.functorful.glacierbackup.application.port.RemoteStorage;
import com.functorful.glacierbackup.application.port.RemoteStorageException;
import com.functorful.glacierbackup.domain.InvalidRelativePathException;
import com.functorful.glacierbackup.domain.RelativePath;
import com.functorful.glacierbackup.domain.RemoteObject;
import com.functorful.glacierbackup.domain.RemoteSnapshot;
import com.functorful.glacierbackup.domain.RestoreState;
import com.functorful.glacierbackup.domain.RestoreTier;
import com.functorful.glacierbackup.domain.StorageClass;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GlacierJobParameters;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.RestoreObjectRequest;
import software.amazon.awssdk.services.s3.model.RestoreRequest;
import software.amazon.awssdk.services.s3.model.RestoreStatus;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tier;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.UploadFileRequest;

public final class S3RemoteStorage implements RemoteStorage {

    private static final String DIRECTORY_MARKER_SUFFIX = "/";

    private final S3Client s3Client;
    private final S3TransferManager transferManager;
    private final String bucket;

    public S3RemoteStorage(S3Client s3Client, S3TransferManager transferManager, String bucket) {
        this.s3Client = s3Client;
        this.transferManager = transferManager;
        this.bucket = bucket;
    }

    @Override
    public RemoteSnapshot list(String prefix) {
        try {
            String requestPrefix = RemoteKeys.requestPrefix(prefix);
            ListObjectsV2Request request = ListObjectsV2Request.builder().bucket(bucket).prefix(requestPrefix).build();
            ListObjectsV2Iterable pages = s3Client.listObjectsV2Paginator(request);
            List<RemoteObject> objects = new ArrayList<>();
            for (S3Object s3Object : pages.contents()) {
                String key = s3Object.key();
                if (key.endsWith(DIRECTORY_MARKER_SUFFIX) || !RemoteKeys.belongsToPrefix(key, prefix)) {
                    continue;
                }
                RelativePath path;
                try {
                    path = RemoteKeys.pathFor(prefix, key);
                } catch (IllegalArgumentException | InvalidRelativePathException e) {
                    continue;
                }
                objects.add(new RemoteObject(
                        path,
                        s3Object.size(),
                        s3Object.lastModified(),
                        storageClassFrom(s3Object.storageClassAsString()),
                        restoreStateFrom(s3Object.restoreStatus())));
            }
            return RemoteSnapshot.of(objects);
        } catch (SdkException e) {
            throw new RemoteStorageException("Failed to list objects under prefix: " + prefix, e);
        }
    }

    @Override
    public Optional<RemoteObject> stat(String key) {
        try {
            HeadObjectResponse response = s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            RelativePath path = RelativePath.of(key);
            return Optional.of(new RemoteObject(
                    path,
                    response.contentLength(),
                    response.lastModified(),
                    storageClassFrom(response.storageClassAsString()),
                    RestoreHeaderParser.parse(Optional.ofNullable(response.restore()))));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw new RemoteStorageException("Failed to stat object: " + key, e);
        } catch (SdkException e) {
            throw new RemoteStorageException("Failed to stat object: " + key, e);
        }
    }

    @Override
    public void put(Path sourceFile, String key, StorageClass storageClass) {
        try {
            UploadFileRequest request = UploadFileRequest.builder()
                    .putObjectRequest(por -> por.bucket(bucket)
                            .key(key)
                            .storageClass(software.amazon.awssdk.services.s3.model.StorageClass.valueOf(storageClass.name())))
                    .source(sourceFile)
                    .build();
            transferManager.uploadFile(request).completionFuture().join();
        } catch (SdkException | CompletionException | UncheckedIOException e) {
            // UncheckedIOException: the transfer manager sizes the source file
            // synchronously, so a file that vanished after the scan surfaces here
            // rather than through the completion future.
            throw new RemoteStorageException("Failed to put object: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException e) {
            throw new RemoteStorageException("Failed to delete object: " + key, e);
        }
    }

    @Override
    public void initiateRestore(String key, RestoreTier tier, int days) {
        try {
            RestoreRequest restoreRequest = RestoreRequest.builder()
                    .days(days)
                    .glacierJobParameters(GlacierJobParameters.builder().tier(Tier.valueOf(tier.name())).build())
                    .build();
            s3Client.restoreObject(
                    RestoreObjectRequest.builder().bucket(bucket).key(key).restoreRequest(restoreRequest).build());
        } catch (SdkException e) {
            throw new RemoteStorageException("Failed to initiate restore: " + key, e);
        }
    }

    @Override
    public RestoreState restoreState(String key) {
        try {
            HeadObjectResponse response = s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return RestoreHeaderParser.parse(Optional.ofNullable(response.restore()));
        } catch (SdkException e) {
            throw new RemoteStorageException("Failed to get restore state: " + key, e);
        }
    }

    @Override
    public void download(String key, Path target) {
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(), target);
        } catch (SdkException | IOException e) {
            throw new RemoteStorageException("Failed to download object: " + key, e);
        }
    }

    static StorageClass storageClassFrom(String awsStorageClass) {
        if (awsStorageClass == null) {
            return StorageClass.STANDARD;
        }
        try {
            return StorageClass.valueOf(awsStorageClass);
        } catch (IllegalArgumentException e) {
            return StorageClass.STANDARD;
        }
    }

    private static RestoreState restoreStateFrom(RestoreStatus restoreStatus) {
        if (restoreStatus == null) {
            return RestoreState.notStarted();
        }
        if (Boolean.TRUE.equals(restoreStatus.isRestoreInProgress())) {
            return RestoreState.inProgress();
        }
        return restoreStatus.restoreExpiryDate() != null
                ? RestoreState.readyUntil(restoreStatus.restoreExpiryDate())
                : RestoreState.notStarted();
    }
}
