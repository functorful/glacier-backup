package com.functorful.glacierbackup.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.functorful.glacierbackup.application.testing.FakeRemoteStorage;
import com.functorful.glacierbackup.cli.config.ToolConfig;
import com.functorful.glacierbackup.domain.StorageClass;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class UpCommandTest {

    private static final String BUCKET = "test-bucket";
    private static final String REGION = "eu-west-1";
    private static final String UP = "up";
    private static final String UPLOADS_PREFIX = "uploads";
    private static final String STORAGE_CLASS_FLAG = "--storage-class";
    private static final String GLACIER_ARG = "glacier";
    private static final String FILE_A_NAME = "a.txt";
    private static final String FILE_B_NAME = "b.txt";
    private static final String MISSING_FILE_NAME = "missing.txt";
    private static final String UPLOADED_KEY_A = UPLOADS_PREFIX + "/" + FILE_A_NAME;
    private static final String UPLOADED_KEY_B = UPLOADS_PREFIX + "/" + FILE_B_NAME;
    private static final String UPLOADED_KEY_MISSING = UPLOADS_PREFIX + "/" + MISSING_FILE_NAME;

    @TempDir
    private Path tempDir;

    @Test
    void upMapsFilesAndPrefixWithStorageClass() throws IOException {
        Path fileA = tempDir.resolve(FILE_A_NAME);
        Path fileB = tempDir.resolve(FILE_B_NAME);
        Files.writeString(fileA, "one");
        Files.writeString(fileB, "two");

        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of());
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        TestAppContext context = new TestAppContext(config, remoteStorage);

        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);

        int exitCode = commandLine.execute(
                UP, fileA.toString(), fileB.toString(), UPLOADS_PREFIX, STORAGE_CLASS_FLAG, GLACIER_ARG);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_OK);
        assertThat(remoteStorage.storedObjects()).containsOnlyKeys(UPLOADED_KEY_A, UPLOADED_KEY_B);
        assertThat(remoteStorage.storedObjects().get(UPLOADED_KEY_A).storageClass()).isEqualTo(StorageClass.GLACIER);
    }

    @Test
    void upWithoutStorageClassFallsBackToConfigDefault() throws IOException {
        Path fileA = tempDir.resolve(FILE_A_NAME);
        Files.writeString(fileA, "one");

        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.GLACIER_IR, Map.of());
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        TestAppContext context = new TestAppContext(config, remoteStorage);

        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);

        int exitCode = commandLine.execute(UP, fileA.toString(), UPLOADS_PREFIX);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_OK);
        assertThat(remoteStorage.storedObjects().get(UPLOADED_KEY_A).storageClass()).isEqualTo(StorageClass.GLACIER_IR);
    }

    @Test
    void upWithMissingFileExitsPartialFailure() {
        Path missingFile = tempDir.resolve(MISSING_FILE_NAME);
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of());
        TestAppContext context = new TestAppContext(config, new FakeRemoteStorage());

        StringWriter err = new StringWriter();
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute(UP, missingFile.toString(), UPLOADS_PREFIX);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_PARTIAL_FAILURE);
        assertThat(err.toString()).contains("1 failed:").contains(UPLOADED_KEY_MISSING);
    }
}
