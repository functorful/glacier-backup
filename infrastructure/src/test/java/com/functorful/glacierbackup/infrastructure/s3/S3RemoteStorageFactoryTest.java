package com.functorful.glacierbackup.infrastructure.s3;

import static com.functorful.glacierbackup.infrastructure.s3.S3RemoteStorageFactory.API_CALL_ATTEMPT_TIMEOUT;
import static com.functorful.glacierbackup.infrastructure.s3.S3RemoteStorageFactory.API_CALL_TIMEOUT;
import static com.functorful.glacierbackup.infrastructure.s3.S3RemoteStorageFactory.CONNECTION_ACQUISITION_TIMEOUT;
import static com.functorful.glacierbackup.infrastructure.s3.S3RemoteStorageFactory.MAX_CONCURRENCY;
import static com.functorful.glacierbackup.infrastructure.s3.S3RemoteStorageFactory.MINIMUM_PART_SIZE_BYTES;
import static com.functorful.glacierbackup.infrastructure.s3.S3RemoteStorageFactory.MULTIPART_API_CALL_BUFFER_SIZE_BYTES;
import static com.functorful.glacierbackup.infrastructure.s3.S3RemoteStorageFactory.WRITE_TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Encodes the timeout/buffer invariant chain that keeps multipart uploads from failing with
 * connection-pool acquire timeouts on a slow uplink: the pool must never be the
 * binding constraint, and at most {@code MAX_CONCURRENCY + 1} parts may be queued ahead of it.
 */
class S3RemoteStorageFactoryTest {

    @Test
    void connectionAcquisitionTimeoutIsAtLeastTheWriteTimeout() {
        assertThat(CONNECTION_ACQUISITION_TIMEOUT)
                .as("a queued part must never be starved of a connection before a part in flight could legitimately"
                        + " finish writing")
                .isGreaterThanOrEqualTo(WRITE_TIMEOUT);
    }

    @Test
    void apiCallAttemptTimeoutCoversWorstCaseQueueWaitPlusUpload() {
        assertThat(API_CALL_ATTEMPT_TIMEOUT)
                .as("one attempt must tolerate acquiring a connection after the pool is saturated, then the write"
                        + " itself, plus slack")
                .isGreaterThanOrEqualTo(CONNECTION_ACQUISITION_TIMEOUT.plus(WRITE_TIMEOUT));
    }

    @Test
    void apiCallTimeoutCoversAtLeastTwoAttempts() {
        assertThat(API_CALL_TIMEOUT)
                .as("the call timeout must bound at least two attempts under the STANDARD retry mode")
                .isGreaterThanOrEqualTo(API_CALL_ATTEMPT_TIMEOUT.multipliedBy(2));
    }

    @Test
    void apiCallBufferSizeCapsQueuedPartsAtMaxConcurrencyPlusOne() {
        assertThat(MULTIPART_API_CALL_BUFFER_SIZE_BYTES)
                .as("the buffer must admit exactly one more part than can be in flight, so queue wait is bounded by"
                        + " a single part-time")
                .isEqualTo((MAX_CONCURRENCY + 1) * MINIMUM_PART_SIZE_BYTES);
    }
}
