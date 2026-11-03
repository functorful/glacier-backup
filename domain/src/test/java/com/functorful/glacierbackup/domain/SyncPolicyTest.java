package com.functorful.glacierbackup.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SyncPolicyTest {

    private static final String ANY_PATH = "anything/at/all.bin";

    @Test
    void defaultsToNoDeleteDeepArchiveAndAcceptAllFilter() {
        SyncPolicy policy = SyncPolicy.defaults();

        assertThat(policy.deleteRemote()).as("default policy must not delete remote objects").isFalse();
        assertThat(policy.storageClass()).isEqualTo(StorageClass.DEEP_ARCHIVE);
        assertThat(policy.filter().matches(RelativePath.of(ANY_PATH)))
                .as("default policy filter must accept every path")
                .isTrue();
    }

    @Test
    void retainsExplicitlyProvidedValues() {
        PathFilter filter = PathFilter.of(List.of(), List.of());

        SyncPolicy policy = new SyncPolicy(true, StorageClass.GLACIER, filter);

        assertThat(policy.deleteRemote()).as("policy must retain the provided deleteRemote flag").isTrue();
        assertThat(policy.storageClass()).isEqualTo(StorageClass.GLACIER);
        assertThat(policy.filter()).isSameAs(filter);
    }
}
