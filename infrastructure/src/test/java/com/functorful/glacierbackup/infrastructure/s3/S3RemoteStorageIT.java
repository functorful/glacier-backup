package com.functorful.glacierbackup.infrastructure.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.functorful.glacierbackup.application.RemoteKeys;
import com.functorful.glacierbackup.application.port.RemoteStorageException;
import com.functorful.glacierbackup.domain.RelativePath;
import com.functorful.glacierbackup.domain.RemoteObject;
import com.functorful.glacierbackup.domain.RemoteSnapshot;
import com.functorful.glacierbackup.domain.StorageClass;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

@Testcontainers
@Tag("integration")
class S3RemoteStorageIT {

    private static final DockerImageName LOCALSTACK_IMAGE = DockerImageName.parse("localstack/localstack:3.8");
    private static final String BUCKET = "glacier-backup-it";
    private static final int TINY_OBJECT_SIZE_BYTES = 1;
    private static final int PAGINATION_OBJECT_COUNT = 1_001;
    /** Larger than the factory's 16 MiB minimum part size, so the upload takes the real multipart path. */
    private static final int MULTIPART_OBJECT_SIZE_BYTES = 20 * 1024 * 1024;

    /**
     * Larger than the factory's 80 MiB api call buffer size (5 parts), so submitting all of the
     * file's parts requires more than one round through the bounded buffer.
     */
    private static final int LARGE_MULTIPART_OBJECT_SIZE_BYTES = 96 * 1024 * 1024;

    @Container
    private static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(LOCALSTACK_IMAGE).withServices(LocalStackContainer.Service.S3);

    private static S3RemoteStorage remoteStorage;

    @BeforeAll
    static void setUpClientsAndBucket() {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey());
        StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(credentials);
        URI endpoint = LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.S3);
        Region region = Region.of(LOCALSTACK.getRegion());

        S3Client s3Client = S3Client.builder()
                .endpointOverride(endpoint)
                .region(region)
                .credentialsProvider(credentialsProvider)
                .forcePathStyle(true)
                .build();
        S3AsyncClient s3AsyncClient = S3AsyncClient.builder()
                .endpointOverride(endpoint)
                .region(region)
                .credentialsProvider(credentialsProvider)
                .forcePathStyle(true)
                .multipartEnabled(true)
                .build();
        S3TransferManager transferManager = S3TransferManager.builder().s3Client(s3AsyncClient).build();

        s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        remoteStorage = new S3RemoteStorage(s3Client, transferManager, BUCKET);
    }

    @Test
    void putThenListRoundTripsKeySizeAndPrefixFiltering(@TempDir Path tempDir) throws Exception {
        String prefix = "round-trip";
        RelativePath relativePath = RelativePath.of("a.bin");
        byte[] content = "round-trip-bytes".getBytes();
        Path sourceFile = tempDir.resolve("a.bin");
        Files.write(sourceFile, content);

        remoteStorage.put(sourceFile, RemoteKeys.keyFor(prefix, relativePath), StorageClass.STANDARD);
        RemoteSnapshot matchingSnapshot = remoteStorage.list(prefix);
        RemoteSnapshot otherPrefixSnapshot = remoteStorage.list("other-prefix");

        assertThat(matchingSnapshot.find(relativePath)).as("object must be listed under its own prefix").isPresent();
        assertThat(matchingSnapshot.find(relativePath).get().sizeBytes())
                .as("listed size must match the bytes written")
                .isEqualTo(content.length);
        assertThat(otherPrefixSnapshot.find(relativePath))
                .as("object must not appear under an unrelated prefix")
                .isEmpty();
    }

    @Test
    void putHonorsDeepArchiveStorageClassInListing(@TempDir Path tempDir) throws Exception {
        String prefix = "deep-archive";
        RelativePath relativePath = RelativePath.of("archived.bin");
        Path sourceFile = tempDir.resolve("archived.bin");
        Files.write(sourceFile, new byte[TINY_OBJECT_SIZE_BYTES]);

        remoteStorage.put(sourceFile, RemoteKeys.keyFor(prefix, relativePath), StorageClass.DEEP_ARCHIVE);
        RemoteSnapshot snapshot = remoteStorage.list(prefix);

        Optional<RemoteObject> found = snapshot.find(relativePath);
        assertThat(found).as("archived object must be listed").isPresent();
        assertThat(found.get().storageClass()).isEqualTo(StorageClass.DEEP_ARCHIVE);
    }

    @Test
    void listPaginatesBeyondLocalStackPageSize(@TempDir Path tempDir) throws Exception {
        String prefix = "pagination";
        Path sourceFile = tempDir.resolve("tiny.bin");
        Files.write(sourceFile, new byte[TINY_OBJECT_SIZE_BYTES]);

        for (int i = 0; i < PAGINATION_OBJECT_COUNT; i++) {
            RelativePath relativePath = RelativePath.of("item-" + i + ".bin");
            remoteStorage.put(sourceFile, RemoteKeys.keyFor(prefix, relativePath), StorageClass.STANDARD);
        }
        RemoteSnapshot snapshot = remoteStorage.list(prefix);

        assertThat(snapshot.size())
                .as("listing must paginate past LocalStack's page size of 1000")
                .isEqualTo(PAGINATION_OBJECT_COUNT);
    }

    @Test
    void statReturnsTheObjectWhenTheKeyExists(@TempDir Path tempDir) throws Exception {
        String key = RemoteKeys.keyFor("stat", RelativePath.of("present.bin"));
        byte[] content = "stat-bytes".getBytes();
        Path sourceFile = tempDir.resolve("present.bin");
        Files.write(sourceFile, content);
        remoteStorage.put(sourceFile, key, StorageClass.STANDARD);

        Optional<RemoteObject> found = remoteStorage.stat(key);

        assertThat(found).as("stat must find an existing key without listing the bucket").isPresent();
        assertThat(found.get().sizeBytes()).isEqualTo(content.length);
    }

    @Test
    void statIsEmptyWhenTheKeyDoesNotExist() {
        Optional<RemoteObject> found = remoteStorage.stat("stat/missing.bin");

        assertThat(found).as("stat of a missing key must not throw").isEmpty();
    }

    @Test
    void deleteRemovesObject(@TempDir Path tempDir) throws Exception {
        String prefix = "delete";
        RelativePath relativePath = RelativePath.of("to-delete.bin");
        String key = RemoteKeys.keyFor(prefix, relativePath);
        Path sourceFile = tempDir.resolve("to-delete.bin");
        Files.write(sourceFile, new byte[TINY_OBJECT_SIZE_BYTES]);
        remoteStorage.put(sourceFile, key, StorageClass.STANDARD);

        remoteStorage.delete(key);
        RemoteSnapshot snapshot = remoteStorage.list(prefix);

        assertThat(snapshot.find(relativePath)).as("deleted object must no longer be listed").isEmpty();
    }

    @Test
    void downloadRoundTripsBytes(@TempDir Path tempDir) throws Exception {
        String prefix = "download";
        String key = RemoteKeys.keyFor(prefix, RelativePath.of("payload.bin"));
        byte[] content = "download-round-trip-payload".getBytes();
        Path sourceFile = tempDir.resolve("source.bin");
        Files.write(sourceFile, content);
        remoteStorage.put(sourceFile, key, StorageClass.STANDARD);

        Path targetFile = tempDir.resolve("nested-target-dir").resolve("downloaded.bin");
        remoteStorage.download(key, targetFile);

        assertThat(Files.readAllBytes(targetFile)).as("downloaded bytes must match the uploaded bytes").isEqualTo(content);
    }

    @Test
    void listExcludesSiblingPrefixesAndBareObjectNamedLikeThePrefix(@TempDir Path tempDir) throws Exception {
        String prefix = "backup";
        Path sourceFile = tempDir.resolve("tiny.bin");
        Files.write(sourceFile, new byte[TINY_OBJECT_SIZE_BYTES]);

        remoteStorage.put(sourceFile, "backup/a.txt", StorageClass.STANDARD);
        remoteStorage.put(sourceFile, "backup2/b.txt", StorageClass.STANDARD);
        remoteStorage.put(sourceFile, "backup", StorageClass.STANDARD);

        RemoteSnapshot snapshot = remoteStorage.list(prefix);

        assertThat(snapshot.all()).as("only the object nested under the prefix directory must be listed").hasSize(1);
        assertThat(snapshot.find(RelativePath.of("a.txt"))).isPresent();
    }

    @Test
    void factoryBuiltStorageUploadsFileLargerThanOnePartThroughTheTunedClient(@TempDir Path tempDir) throws Exception {
        var factoryStorage = S3RemoteStorageFactory.create(
                BUCKET, LOCALSTACK.getRegion(), LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.S3));
        String key = RemoteKeys.keyFor("multipart", RelativePath.of("large.bin"));
        Path sourceFile = tempDir.resolve("large.bin");
        byte[] content = new byte[MULTIPART_OBJECT_SIZE_BYTES];
        Files.write(sourceFile, content);

        factoryStorage.put(sourceFile, key, StorageClass.STANDARD);

        Optional<RemoteObject> found = factoryStorage.stat(key);
        assertThat(found).as("the multipart upload must complete through the tuned client").isPresent();
        assertThat(found.get().sizeBytes()).isEqualTo(MULTIPART_OBJECT_SIZE_BYTES);
    }

    @Test
    void factoryBuiltStorageUploadsFileLargerThanTheApiCallBufferBoundThroughTheTunedClient(@TempDir Path tempDir)
            throws Exception {
        var factoryStorage = S3RemoteStorageFactory.create(
                BUCKET, LOCALSTACK.getRegion(), LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.S3));
        String key = RemoteKeys.keyFor("multipart", RelativePath.of("very-large.bin"));
        Path sourceFile = tempDir.resolve("very-large.bin");
        byte[] content = new byte[LARGE_MULTIPART_OBJECT_SIZE_BYTES];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 251);
        }
        Files.write(sourceFile, content);

        factoryStorage.put(sourceFile, key, StorageClass.STANDARD);

        Optional<RemoteObject> found = factoryStorage.stat(key);
        assertThat(found)
                .as("the multipart upload must complete through the tuned client even when more parts are queued"
                        + " than the api call buffer admits at once")
                .isPresent();
        assertThat(found.get().sizeBytes())
                .as("uploaded size must match the source file despite spanning multiple buffer refills")
                .isEqualTo(LARGE_MULTIPART_OBJECT_SIZE_BYTES);

        Path downloaded = tempDir.resolve("very-large-downloaded.bin");
        factoryStorage.download(key, downloaded);
        assertThat(Files.readAllBytes(downloaded))
                .as("downloaded bytes must match the uploaded bytes across every part boundary")
                .isEqualTo(content);
    }

    @Test
    void putWrapsVanishedSourceFileInRemoteStorageException(@TempDir Path tempDir) {
        String key = RemoteKeys.keyFor("vanished", RelativePath.of("vanished.bin"));
        Path vanishedSource = tempDir.resolve("vanished.bin");

        assertThatThrownBy(() -> remoteStorage.put(vanishedSource, key, StorageClass.STANDARD))
                .isInstanceOf(RemoteStorageException.class)
                .hasMessageContaining(key)
                .hasRootCauseInstanceOf(NoSuchFileException.class);
    }

    @Test
    void nestedKeysMapToCorrectRelativePath(@TempDir Path tempDir) throws Exception {
        String prefix = "nested";
        RelativePath relativePath = RelativePath.of("a/b/c.bin");
        Path sourceFile = tempDir.resolve("c.bin");
        Files.write(sourceFile, new byte[TINY_OBJECT_SIZE_BYTES]);

        remoteStorage.put(sourceFile, RemoteKeys.keyFor(prefix, relativePath), StorageClass.STANDARD);
        RemoteSnapshot snapshot = remoteStorage.list(prefix);

        assertThat(snapshot.all()).as("snapshot must contain exactly the nested entry").hasSize(1);
        assertThat(snapshot.find(relativePath)).as("nested key must map back to its original relative path").isPresent();
    }
}
