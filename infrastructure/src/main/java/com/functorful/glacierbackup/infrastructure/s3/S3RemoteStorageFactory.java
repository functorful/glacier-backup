package com.functorful.glacierbackup.infrastructure.s3;

import com.functorful.glacierbackup.application.port.RemoteStorage;
import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

public final class S3RemoteStorageFactory {

    private static final String LOCALSTACK_DUMMY_ACCESS_KEY = "test";
    private static final String LOCALSTACK_DUMMY_SECRET_KEY = "test";

    // The async client is tuned for multi-GB uploads over a slow residential
    // uplink. The stock Netty write timeout (30s) force-closed connections
    // mid-part whenever the uplink was saturated, failing every large file.
    //
    // The timeout/buffer chain below is one invariant, not independent knobs
    // (see S3RemoteStorageFactoryTest, which encodes each relationship):
    //   acquisition timeout  >= write timeout
    //   attempt timeout      >= acquisition timeout + write timeout (+ slack)
    //   call timeout         >= 2 * attempt timeout (STANDARD retry mode allows a retry)
    //   api call buffer size == (max concurrency + 1) * minimum part size
    // Breaking any inequality reopens a known failure mode: parts queued
    // behind a saturated pool were timing out on connection acquisition (2 min)
    // long before a part legitimately in flight could finish writing (up to 15 min).

    /** A 16 MiB part at a contended ~50 KB/s takes ~5.5 min; 15 min gives ~3x headroom. */
    static final Duration WRITE_TIMEOUT = Duration.ofMinutes(15);

    /** S3 responses are small; 5 min tolerates long server-side pauses on CompleteMultipartUpload. */
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(5);

    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Must be >= WRITE_TIMEOUT: a part waiting in the queue must never time out on
     * acquiring a connection before a part already in flight could legitimately
     * finish writing. Bounded above all by the api call buffer size, which caps how
     * many parts can queue ahead of the pool in the first place.
     */
    static final Duration CONNECTION_ACQUISITION_TIMEOUT = Duration.ofMinutes(16);

    /**
     * The default of 50 concurrent parts makes every part crawl and hit the write
     * timeout on a slow uplink. Four saturate it, finish each part sooner, and cap
     * the multipart client's in-memory part buffering.
     */
    static final int MAX_CONCURRENCY = 4;

    /**
     * Double the 8 MiB default: halves the request count for multi-GB files while
     * keeping the 10_000-part object ceiling at 160 GiB and each part comfortably
     * inside the write timeout.
     */
    static final long MINIMUM_PART_SIZE_BYTES = 16L * 1024 * 1024;

    /**
     * Bounds how many parts the multipart client may buffer/submit ahead of the
     * connection pool. Sized to MAX_CONCURRENCY + 1 parts so at most one part is
     * ever queued behind the four in flight, keeping queue wait bounded by a single
     * part-time instead of growing with file size.
     */
    static final long MULTIPART_API_CALL_BUFFER_SIZE_BYTES = (MAX_CONCURRENCY + 1) * MINIMUM_PART_SIZE_BYTES;

    /**
     * Bounds one part attempt for the new worst case: a part may wait up to one
     * part-time in the queue (bounded by the buffer above) before its own upload
     * can take up to WRITE_TIMEOUT, plus slack.
     */
    static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofMinutes(35);

    /** Bounds one part call across the SDK's own STANDARD-mode retries (roughly 2x the attempt timeout). */
    static final Duration API_CALL_TIMEOUT = Duration.ofMinutes(75);

    private S3RemoteStorageFactory() {}

    public static RemoteStorage create(String bucket, String region) {
        Region awsRegion = Region.of(region);
        DefaultCredentialsProvider credentialsProvider = DefaultCredentialsProvider.create();

        S3AsyncClient s3AsyncClient = tunedAsyncClientBuilder()
                .region(awsRegion)
                .credentialsProvider(credentialsProvider)
                .build();
        S3TransferManager transferManager = S3TransferManager.builder().s3Client(s3AsyncClient).build();
        S3Client s3Client = S3Client.builder().region(awsRegion).credentialsProvider(credentialsProvider).build();

        return new S3RemoteStorage(s3Client, transferManager, bucket);
    }

    public static RemoteStorage create(String bucket, String region, URI endpointOverride) {
        Region awsRegion = Region.of(region);
        AwsCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(LOCALSTACK_DUMMY_ACCESS_KEY, LOCALSTACK_DUMMY_SECRET_KEY));

        S3AsyncClient s3AsyncClient = tunedAsyncClientBuilder()
                .endpointOverride(endpointOverride)
                .region(awsRegion)
                .credentialsProvider(credentialsProvider)
                .forcePathStyle(true)
                .build();
        S3TransferManager transferManager = S3TransferManager.builder().s3Client(s3AsyncClient).build();
        S3Client s3Client = S3Client.builder()
                .endpointOverride(endpointOverride)
                .region(awsRegion)
                .credentialsProvider(credentialsProvider)
                .forcePathStyle(true)
                .build();

        return new S3RemoteStorage(s3Client, transferManager, bucket);
    }

    private static S3AsyncClientBuilder tunedAsyncClientBuilder() {
        return S3AsyncClient.builder()
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .writeTimeout(WRITE_TIMEOUT)
                        .readTimeout(READ_TIMEOUT)
                        .connectionTimeout(CONNECTION_TIMEOUT)
                        .connectionAcquisitionTimeout(CONNECTION_ACQUISITION_TIMEOUT)
                        .maxConcurrency(MAX_CONCURRENCY))
                .multipartEnabled(true)
                .multipartConfiguration(mc -> mc.minimumPartSizeInBytes(MINIMUM_PART_SIZE_BYTES)
                        .apiCallBufferSizeInBytes(MULTIPART_API_CALL_BUFFER_SIZE_BYTES))
                .overrideConfiguration(oc -> oc.apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT)
                        .apiCallTimeout(API_CALL_TIMEOUT)
                        .retryStrategy(RetryMode.STANDARD));
    }
}
