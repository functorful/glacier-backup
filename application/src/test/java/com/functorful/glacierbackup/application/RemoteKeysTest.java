package com.functorful.glacierbackup.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.functorful.glacierbackup.domain.RelativePath;
import org.junit.jupiter.api.Test;

class RemoteKeysTest {

    private static final String PREFIX_NO_TRAILING_SLASH = "media";
    private static final String PREFIX_WITH_TRAILING_SLASH = "media/";
    private static final String EMPTY_PREFIX = "";
    private static final RelativePath NESTED_PATH = RelativePath.of("a/b.bin");
    private static final String EXPECTED_KEY = "media/a/b.bin";

    @Test
    void buildsKeyFromPrefixWithoutTrailingSlash() {
        assertThat(RemoteKeys.keyFor(PREFIX_NO_TRAILING_SLASH, NESTED_PATH)).isEqualTo(EXPECTED_KEY);
    }

    @Test
    void buildsKeyFromPrefixWithTrailingSlash() {
        assertThat(RemoteKeys.keyFor(PREFIX_WITH_TRAILING_SLASH, NESTED_PATH)).isEqualTo(EXPECTED_KEY);
    }

    @Test
    void emptyPrefixYieldsPathOnly() {
        assertThat(RemoteKeys.keyFor(EMPTY_PREFIX, NESTED_PATH)).isEqualTo(NESTED_PATH.value());
    }

    @Test
    void pathForIsInverseOfKeyForWithPrefix() {
        String key = RemoteKeys.keyFor(PREFIX_NO_TRAILING_SLASH, NESTED_PATH);

        assertThat(RemoteKeys.pathFor(PREFIX_NO_TRAILING_SLASH, key)).isEqualTo(NESTED_PATH);
    }

    @Test
    void pathForIsInverseOfKeyForWithEmptyPrefix() {
        String key = RemoteKeys.keyFor(EMPTY_PREFIX, NESTED_PATH);

        assertThat(RemoteKeys.pathFor(EMPTY_PREFIX, key)).isEqualTo(NESTED_PATH);
    }

    @Test
    void requestPrefixAddsTrailingSeparatorWhenMissing() {
        assertThat(RemoteKeys.requestPrefix(PREFIX_NO_TRAILING_SLASH)).isEqualTo(PREFIX_WITH_TRAILING_SLASH);
    }

    @Test
    void requestPrefixIsIdempotentWhenSeparatorAlreadyPresent() {
        assertThat(RemoteKeys.requestPrefix(PREFIX_WITH_TRAILING_SLASH)).isEqualTo(PREFIX_WITH_TRAILING_SLASH);
    }

    @Test
    void requestPrefixOfEmptyPrefixIsEmpty() {
        assertThat(RemoteKeys.requestPrefix(EMPTY_PREFIX)).isEqualTo(EMPTY_PREFIX);
    }

    @Test
    void belongsToPrefixAcceptsKeyUnderTheDirectory() {
        assertThat(RemoteKeys.belongsToPrefix("media/a.bin", PREFIX_NO_TRAILING_SLASH)).isTrue();
    }

    @Test
    void belongsToPrefixRejectsSiblingPrefixSharingTheSameStringPrefix() {
        assertThat(RemoteKeys.belongsToPrefix("media2/b.bin", PREFIX_NO_TRAILING_SLASH)).isFalse();
    }

    @Test
    void belongsToPrefixRejectsBareObjectNamedExactlyLikeThePrefix() {
        assertThat(RemoteKeys.belongsToPrefix(PREFIX_NO_TRAILING_SLASH, PREFIX_NO_TRAILING_SLASH)).isFalse();
    }

    @Test
    void belongsToPrefixAcceptsEverythingUnderTheEmptyPrefix() {
        assertThat(RemoteKeys.belongsToPrefix("media2/b.bin", EMPTY_PREFIX)).isTrue();
    }
}
