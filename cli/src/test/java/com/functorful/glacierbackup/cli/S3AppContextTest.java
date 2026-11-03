package com.functorful.glacierbackup.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.functorful.glacierbackup.cli.config.ConfigException;
import com.functorful.glacierbackup.infrastructure.fs.NioLocalVolume;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class S3AppContextTest {

    private static final int FLAG_SCAN_THREADS = 12;
    private static final int CONFIGURED_SCAN_THREADS = 24;
    private static final String SCAN_THREADS_FLAG = "--scan-threads";

    @Test
    void scanThreadsFlagBeatsConfiguredValue() {
        int resolved = S3AppContext.resolveScanThreads(
                OptionalInt.of(FLAG_SCAN_THREADS), OptionalInt.of(CONFIGURED_SCAN_THREADS));

        assertThat(resolved).isEqualTo(FLAG_SCAN_THREADS);
    }

    @Test
    void configuredScanThreadsBeatDefault() {
        int resolved = S3AppContext.resolveScanThreads(
                OptionalInt.empty(), OptionalInt.of(CONFIGURED_SCAN_THREADS));

        assertThat(resolved).isEqualTo(CONFIGURED_SCAN_THREADS);
    }

    @Test
    void defaultScanThreadsApplyWhenNothingIsSet() {
        int resolved = S3AppContext.resolveScanThreads(OptionalInt.empty(), OptionalInt.empty());

        assertThat(resolved).isEqualTo(NioLocalVolume.DEFAULT_SCAN_THREADS);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -4, 129})
    void outOfRangeScanThreadsFlagThrowsConfigExceptionNamingTheFlag(int outOfRange) {
        assertThatThrownBy(() -> S3AppContext.resolveScanThreads(OptionalInt.of(outOfRange), OptionalInt.empty()))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(SCAN_THREADS_FLAG);
    }
}
