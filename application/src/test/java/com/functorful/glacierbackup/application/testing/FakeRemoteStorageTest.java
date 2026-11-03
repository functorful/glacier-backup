package com.functorful.glacierbackup.application.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.functorful.glacierbackup.application.port.RemoteStorageException;
import com.functorful.glacierbackup.domain.RelativePath;
import com.functorful.glacierbackup.domain.StorageClass;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FakeRemoteStorageTest {

    private static final String PREFIX = "media";
    private static final String SIBLING_KEY = "media2/b.txt";
    private static final String MATCHING_KEY = "media/a.txt";
    private static final String BARE_KEY_NAMED_LIKE_PREFIX = "media";
    private static final byte[] CONTENT = "glacier-backup".getBytes();

    @Test
    void listUnderPrefixExcludesSiblingPrefixSharingTheSameStringPrefix(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        put(remoteStorage, tempDir, MATCHING_KEY);
        put(remoteStorage, tempDir, SIBLING_KEY);

        var snapshot = remoteStorage.list(PREFIX);

        assertThat(snapshot.all()).as("a sibling prefix must not leak into the listing").hasSize(1);
        assertThat(snapshot.find(RelativePath.of("a.txt"))).isPresent();
    }

    @Test
    void listUnderPrefixExcludesBareObjectNamedExactlyLikeThePrefix(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        put(remoteStorage, tempDir, MATCHING_KEY);
        put(remoteStorage, tempDir, BARE_KEY_NAMED_LIKE_PREFIX);

        var snapshot = remoteStorage.list(PREFIX);

        assertThat(snapshot.all()).as("a bare object named like the prefix must not be listed under it").hasSize(1);
        assertThat(snapshot.find(RelativePath.of("a.txt"))).isPresent();
    }

    @Test
    void statReturnsTheObjectWhenTheExactKeyExists(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        put(remoteStorage, tempDir, MATCHING_KEY);

        var found = remoteStorage.stat(MATCHING_KEY);

        assertThat(found).isPresent();
        assertThat(found.get().path()).isEqualTo(RelativePath.of(MATCHING_KEY));
    }

    @Test
    void statIsEmptyWhenTheExactKeyIsAbsent(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        put(remoteStorage, tempDir, SIBLING_KEY);

        assertThat(remoteStorage.stat(MATCHING_KEY)).isEmpty();
    }

    @Test
    void listWithEmptyPrefixIncludesEveryKeyRegardlessOfSiblingPrefixes(@TempDir Path tempDir) throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        put(remoteStorage, tempDir, MATCHING_KEY);
        put(remoteStorage, tempDir, SIBLING_KEY);

        var snapshot = remoteStorage.list("");

        assertThat(snapshot.all()).hasSize(2);
    }

    @Test
    void putOfVanishedSourceFileThrowsRemoteStorageExceptionLikeTheRealAdapter(@TempDir Path tempDir) {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        Path vanishedSource = tempDir.resolve("vanished.bin");

        assertThatThrownBy(() -> remoteStorage.put(vanishedSource, MATCHING_KEY, StorageClass.STANDARD))
                .isInstanceOf(RemoteStorageException.class)
                .hasRootCauseInstanceOf(NoSuchFileException.class);
    }

    private static void put(FakeRemoteStorage remoteStorage, Path tempDir, String key) throws IOException {
        Path sourceFile = Files.createTempFile(tempDir, "seed-", ".bin");
        Files.write(sourceFile, CONTENT);
        remoteStorage.put(sourceFile, key, StorageClass.STANDARD);
    }
}
