package com.functorful.glacierbackup.cli.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.functorful.glacierbackup.domain.RelativePath;
import com.functorful.glacierbackup.domain.StorageClass;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Keeps the shipped example configs loadable, so they cannot drift from {@link ConfigLoader}. */
class ExampleConfigsTest {

    private static final String EXAMPLES_DIR_PROPERTY = "examples.dir";
    private static final String GENERIC_EXAMPLE = "config.example.yml";
    private static final String UNAS_EXAMPLE = "unas-pro/config.yml";

    private static final String PHOTOS_SET = "photos";
    private static final String DOCUMENTS_SET = "documents";
    private static final int GENERIC_SCAN_THREADS = 32;

    private static Path example(String relativePath) {
        String dir = System.getProperty(EXAMPLES_DIR_PROPERTY);
        assertThat(dir).as("system property " + EXAMPLES_DIR_PROPERTY + " set by the cli test task").isNotNull();
        Path file = Path.of(dir).resolve(relativePath);
        assertThat(Files.isRegularFile(file)).as("example config exists: " + file).isTrue();
        return file;
    }

    @Test
    void genericExampleLoadsWithAllSupportedKeys() {
        ToolConfig config = new ConfigLoader().load(Optional.of(example(GENERIC_EXAMPLE)));

        assertThat(config.bucket()).isNotBlank();
        assertThat(config.region()).isNotBlank();
        assertThat(config.defaultStorageClass()).isEqualTo(StorageClass.DEEP_ARCHIVE);
        assertThat(config.scanThreads()).hasValue(GENERIC_SCAN_THREADS);
        assertThat(config.backupSets()).containsKeys(PHOTOS_SET, DOCUMENTS_SET);

        BackupSet documents = config.backupSets().get(DOCUMENTS_SET);
        assertThat(documents.filter().matches(RelativePath.of("notes/todo.txt"))).isTrue();
        assertThat(documents.filter().matches(RelativePath.of("notes/.DS_Store"))).isFalse();
        assertThat(documents.filter().matches(RelativePath.of("misc/picture.png"))).isFalse();
    }

    @Test
    void unasProExampleLoadsAndExcludesNasCruft() {
        ToolConfig config = new ConfigLoader().load(Optional.of(example(UNAS_EXAMPLE)));

        assertThat(config.backupSets()).isNotEmpty();
        config.backupSets().values().forEach(set -> {
            assertThat(set.local().toString()).contains("<volume-uuid>");
            assertThat(set.prefix()).endsWith("/");
            assertThat(set.filter().matches(RelativePath.of("a/b/photo.jpg"))).isTrue();
            assertThat(set.filter().matches(RelativePath.of("a/@eaDir/thumb.jpg"))).isFalse();
            assertThat(set.filter().matches(RelativePath.of("@eaDir/thumb.jpg"))).isFalse();
            assertThat(set.filter().matches(RelativePath.of("a/#recycle/old.jpg"))).isFalse();
            assertThat(set.filter().matches(RelativePath.of(".DS_Store"))).isFalse();
            assertThat(set.filter().matches(RelativePath.of("a/.DS_Store"))).isFalse();
        });
    }
}
