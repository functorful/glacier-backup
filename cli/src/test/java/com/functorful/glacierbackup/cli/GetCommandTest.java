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

class GetCommandTest {

    private static final String BUCKET = "test-bucket";
    private static final String REGION = "eu-west-1";
    private static final String GET = "get";
    private static final String KEY = "backup/archive.dat";
    private static final String NOT_READY_KEY = "backup/frozen.dat";
    private static final String RESTORE_STATUS_HINT = "run restore-status to check progress";

    @TempDir
    private Path tempDir;

    @Test
    void getDownloadsFileUnderTargetDirAtTheFullKeyPath() throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        seedObject(remoteStorage, KEY, StorageClass.STANDARD);
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of());
        TestAppContext context = new TestAppContext(config, remoteStorage);
        Path targetDir = tempDir.resolve("downloads");
        Files.createDirectories(targetDir);

        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);

        int exitCode = commandLine.execute(GET, KEY, targetDir.toString());

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_OK);
        assertThat(targetDir.resolve(KEY)).as("downloaded file at full key path").exists();
    }

    @Test
    void getOfNotReadyObjectFailsAndPrintsRestoreStatusHint() throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        seedObject(remoteStorage, NOT_READY_KEY, StorageClass.GLACIER);
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of());
        TestAppContext context = new TestAppContext(config, remoteStorage);
        Path targetDir = tempDir.resolve("downloads");
        Files.createDirectories(targetDir);

        StringWriter err = new StringWriter();
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute(GET, NOT_READY_KEY, targetDir.toString());

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_PARTIAL_FAILURE);
        assertThat(err.toString()).contains("1 failed:").contains(NOT_READY_KEY).contains(RESTORE_STATUS_HINT);
    }

    private void seedObject(FakeRemoteStorage remoteStorage, String key, StorageClass storageClass) throws IOException {
        Path source = tempDir.resolve(key.replace('/', '_'));
        Files.writeString(source, "data");
        remoteStorage.put(source, key, storageClass);
    }
}
