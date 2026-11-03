package com.functorful.glacierbackup.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.functorful.glacierbackup.application.testing.FakeRemoteStorage;
import com.functorful.glacierbackup.cli.config.ToolConfig;
import com.functorful.glacierbackup.domain.RestoreState;
import com.functorful.glacierbackup.domain.StorageClass;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class RestoreStatusCommandTest {

    private static final String BUCKET = "test-bucket";
    private static final String REGION = "eu-west-1";
    private static final String RESTORE_STATUS = "restore-status";
    private static final String BACKUP_PREFIX = "backup";
    private static final String IN_PROGRESS_KEY = "backup/in-progress.dat";
    private static final String READY_KEY = "backup/ready.dat";

    @TempDir
    private Path tempDir;

    @Test
    void restoreStatusRendersInProgressAndReadyUntilLines() throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        seedObject(remoteStorage, IN_PROGRESS_KEY, StorageClass.GLACIER);
        remoteStorage.seedRestoreState(IN_PROGRESS_KEY, RestoreState.inProgress());
        seedObject(remoteStorage, READY_KEY, StorageClass.GLACIER);
        Instant readyUntil = Instant.parse("2026-09-01T00:00:00Z");
        remoteStorage.seedRestoreState(READY_KEY, RestoreState.readyUntil(readyUntil));
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of());
        TestAppContext context = new TestAppContext(config, remoteStorage);

        StringWriter out = new StringWriter();
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute(RESTORE_STATUS, BACKUP_PREFIX);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_OK);
        assertThat(out.toString().lines().toList())
                .anySatisfy(line -> assertThat(line)
                        .contains(IN_PROGRESS_KEY)
                        .contains(StorageClass.GLACIER.toString())
                        .contains("in-progress"))
                .anySatisfy(line -> assertThat(line)
                        .contains(READY_KEY)
                        .contains(StorageClass.GLACIER.toString())
                        .contains("ready-until " + readyUntil));
    }

    @Test
    void restoreStatusWithoutPrefixDefaultsToEmptyPrefix() throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        seedObject(remoteStorage, READY_KEY, StorageClass.GLACIER);
        Instant readyUntil = Instant.parse("2026-09-01T00:00:00Z");
        remoteStorage.seedRestoreState(READY_KEY, RestoreState.readyUntil(readyUntil));
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of());
        TestAppContext context = new TestAppContext(config, remoteStorage);

        StringWriter out = new StringWriter();
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute(RESTORE_STATUS);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_OK);
        assertThat(out.toString()).contains(READY_KEY);
    }

    private void seedObject(FakeRemoteStorage remoteStorage, String key, StorageClass storageClass) throws IOException {
        Path source = tempDir.resolve(key.replace('/', '_'));
        Files.writeString(source, "data");
        remoteStorage.put(source, key, storageClass);
    }
}
