package com.functorful.glacierbackup.cli.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.functorful.glacierbackup.domain.RelativePath;
import com.functorful.glacierbackup.domain.StorageClass;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigLoaderTest {

    private static final String CONFIG_FILE_NAME = "config.yml";

    private static final String BUCKET = "my-glacier-backup-bucket";
    private static final String REGION = "eu-west-1";
    private static final String MEDIA_SET_NAME = "media";
    private static final String MEDIA_LOCAL = "/srv/media";
    private static final String MEDIA_PREFIX = "media/";
    private static final String INCLUDED_PATH = "media/movies/film.mkv";
    private static final String EXCLUDED_PATH = "media/movies/.DS_Store";
    private static final String NON_INCLUDED_PATH = "docs/readme.md";

    private static final String FULL_CONFIG = """
            bucket: %s
            region: %s
            defaults:
              storageClass: GLACIER
            backupSets:
              %s:
                local: %s
                prefix: %s
                include: ["media/**"]
                exclude: ["**/.DS_Store"]
            """.formatted(BUCKET, REGION, MEDIA_SET_NAME, MEDIA_LOCAL, MEDIA_PREFIX);

    private static final String MINIMAL_CONFIG = """
            bucket: %s
            region: %s
            """.formatted(BUCKET, REGION);

    private static final String MISSING_BUCKET_CONFIG = """
            region: %s
            """.formatted(REGION);

    private static final String UNKNOWN_STORAGE_CLASS_CONFIG = """
            bucket: %s
            region: %s
            defaults:
              storageClass: NOT_A_REAL_CLASS
            """.formatted(BUCKET, REGION);

    private static final int CONFIGURED_SCAN_THREADS = 16;
    private static final String SCAN_THREADS_KEY_PATH = "defaults." + ConfigLoader.SCAN_THREADS_KEY;

    private static final String SCAN_THREADS_CONFIG = """
            bucket: %s
            region: %s
            defaults:
              scanThreads: %d
            """.formatted(BUCKET, REGION, CONFIGURED_SCAN_THREADS);

    private static final String SCAN_THREADS_TEMPLATE_CONFIG = """
            bucket: %s
            region: %s
            defaults:
              scanThreads: %s
            """;

    @Test
    void fullConfigRoundTripsWithBackupSetAndFilter(@TempDir Path tempDir) throws IOException {
        Path configFile = writeConfig(tempDir, FULL_CONFIG);

        ToolConfig config = new ConfigLoader().load(Optional.of(configFile));

        assertThat(config.bucket()).isEqualTo(BUCKET);
        assertThat(config.region()).isEqualTo(REGION);
        assertThat(config.defaultStorageClass()).isEqualTo(StorageClass.GLACIER);
        assertThat(config.backupSets()).containsOnlyKeys(MEDIA_SET_NAME);

        BackupSet mediaSet = config.backupSets().get(MEDIA_SET_NAME);
        assertThat(mediaSet.local()).isEqualTo(Path.of(MEDIA_LOCAL));
        assertThat(mediaSet.prefix()).isEqualTo(MEDIA_PREFIX);
        assertThat(mediaSet.filter().matches(RelativePath.of(INCLUDED_PATH)))
                .as("path matching include glob and not excluded must match")
                .isTrue();
        assertThat(mediaSet.filter().matches(RelativePath.of(EXCLUDED_PATH)))
                .as("path matching exclude glob must not match")
                .isFalse();
        assertThat(mediaSet.filter().matches(RelativePath.of(NON_INCLUDED_PATH)))
                .as("path outside include globs must not match")
                .isFalse();
    }

    @Test
    void minimalConfigAppliesDeepArchiveDefaultAndEmptyBackupSets(@TempDir Path tempDir) throws IOException {
        Path configFile = writeConfig(tempDir, MINIMAL_CONFIG);

        ToolConfig config = new ConfigLoader().load(Optional.of(configFile));

        assertThat(config.bucket()).isEqualTo(BUCKET);
        assertThat(config.region()).isEqualTo(REGION);
        assertThat(config.defaultStorageClass()).isEqualTo(StorageClass.DEEP_ARCHIVE);
        assertThat(config.backupSets()).isEmpty();
    }

    @Test
    void missingBucketThrowsConfigExceptionNamingBucket(@TempDir Path tempDir) throws IOException {
        Path configFile = writeConfig(tempDir, MISSING_BUCKET_CONFIG);

        assertThatThrownBy(() -> new ConfigLoader().load(Optional.of(configFile)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("bucket");
    }

    @Test
    void nonexistentExplicitPathThrowsConfigException(@TempDir Path tempDir) {
        Path missingFile = tempDir.resolve("does-not-exist.yml");

        assertThatThrownBy(() -> new ConfigLoader().load(Optional.of(missingFile)))
                .isInstanceOf(ConfigException.class);
    }

    @Test
    void unknownStorageClassThrowsConfigException(@TempDir Path tempDir) throws IOException {
        Path configFile = writeConfig(tempDir, UNKNOWN_STORAGE_CLASS_CONFIG);

        assertThatThrownBy(() -> new ConfigLoader().load(Optional.of(configFile)))
                .isInstanceOf(ConfigException.class);
    }

    @Test
    void scanThreadsAreParsedFromDefaults(@TempDir Path tempDir) throws IOException {
        Path configFile = writeConfig(tempDir, SCAN_THREADS_CONFIG);

        ToolConfig config = new ConfigLoader().load(Optional.of(configFile));

        assertThat(config.scanThreads()).hasValue(CONFIGURED_SCAN_THREADS);
    }

    @Test
    void scanThreadsAreEmptyWhenAbsent(@TempDir Path tempDir) throws IOException {
        Path configFile = writeConfig(tempDir, MINIMAL_CONFIG);

        ToolConfig config = new ConfigLoader().load(Optional.of(configFile));

        assertThat(config.scanThreads()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-number", "0", "-4", "129"})
    void invalidScanThreadsThrowConfigExceptionNamingTheKey(String invalidValue, @TempDir Path tempDir)
            throws IOException {
        Path configFile = writeConfig(tempDir,
                SCAN_THREADS_TEMPLATE_CONFIG.formatted(BUCKET, REGION, invalidValue));

        assertThatThrownBy(() -> new ConfigLoader().load(Optional.of(configFile)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(SCAN_THREADS_KEY_PATH);
    }

    @Test
    void resolutionFallsBackToUserConfigThenEtcConfig(@TempDir Path tempDir) throws IOException {
        Path userConfigFile = tempDir.resolve("user").resolve(CONFIG_FILE_NAME);
        Path etcConfigFile = tempDir.resolve("etc").resolve(CONFIG_FILE_NAME);
        Files.createDirectories(etcConfigFile.getParent());
        Files.writeString(etcConfigFile, MINIMAL_CONFIG);
        ConfigLoader loader = new ConfigLoader(userConfigFile, etcConfigFile);

        ToolConfig config = loader.load(Optional.empty());

        assertThat(config.bucket()).isEqualTo(BUCKET);
    }

    @Test
    void resolutionPrefersUserConfigOverEtcConfig(@TempDir Path tempDir) throws IOException {
        Path userConfigFile = tempDir.resolve("user").resolve(CONFIG_FILE_NAME);
        Path etcConfigFile = tempDir.resolve("etc").resolve(CONFIG_FILE_NAME);
        Files.createDirectories(userConfigFile.getParent());
        Files.writeString(userConfigFile, FULL_CONFIG);
        ConfigLoader loader = new ConfigLoader(userConfigFile, etcConfigFile);

        ToolConfig config = loader.load(Optional.empty());

        assertThat(config.defaultStorageClass()).isEqualTo(StorageClass.GLACIER);
    }

    @Test
    void resolutionWithNoConfigFoundThrowsConfigExceptionListingSearchedPaths(@TempDir Path tempDir) {
        Path userConfigFile = tempDir.resolve("user").resolve(CONFIG_FILE_NAME);
        Path etcConfigFile = tempDir.resolve("etc").resolve(CONFIG_FILE_NAME);
        ConfigLoader loader = new ConfigLoader(userConfigFile, etcConfigFile);

        assertThatThrownBy(() -> loader.load(Optional.empty()))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(userConfigFile.toString())
                .hasMessageContaining(etcConfigFile.toString());
    }

    private static Path writeConfig(Path tempDir, String content) throws IOException {
        Path configFile = tempDir.resolve(CONFIG_FILE_NAME);
        Files.writeString(configFile, content);
        return configFile;
    }
}
