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

class LsCommandTest {

    private static final String BUCKET = "test-bucket";
    private static final String REGION = "eu-west-1";
    private static final String LS = "ls";
    private static final String BACKUP_PREFIX = "backup";
    private static final String RECURSIVE_FLAG = "--recursive";
    private static final String LONG_FLAG = "-l";

    @TempDir
    private Path tempDir;

    @Test
    void lsPlainPrintsKeysOnly() throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        seedObject(remoteStorage, "backup/a.txt", StorageClass.STANDARD);
        seedObject(remoteStorage, "backup/b.txt", StorageClass.STANDARD);
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of());
        TestAppContext context = new TestAppContext(config, remoteStorage);

        StringWriter out = new StringWriter();
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute(LS, BACKUP_PREFIX, RECURSIVE_FLAG);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_OK);
        assertThat(LogLines.messages(out.toString())).containsExactly("backup/a.txt", "backup/b.txt");
    }

    @Test
    void lsNonRecursiveCollapsesDeeperKeysToNextDirSegment() throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        seedObject(remoteStorage, "backup/top.txt", StorageClass.STANDARD);
        seedObject(remoteStorage, "backup/nested/deep.txt", StorageClass.STANDARD);
        seedObject(remoteStorage, "backup/nested/deep2.txt", StorageClass.STANDARD);
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of());
        TestAppContext context = new TestAppContext(config, remoteStorage);

        StringWriter out = new StringWriter();
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute(LS, BACKUP_PREFIX);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_OK);
        assertThat(LogLines.messages(out.toString())).containsExactly("nested/", "top.txt");
    }

    @Test
    void lsLongFormatRendersStorageClassAndReadyUntilTimestamp() throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        String key = "backup/archive.dat";
        seedObject(remoteStorage, key, StorageClass.DEEP_ARCHIVE);
        Instant readyUntil = Instant.parse("2026-09-01T00:00:00Z");
        remoteStorage.seedRestoreState(key, RestoreState.readyUntil(readyUntil));
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of());
        TestAppContext context = new TestAppContext(config, remoteStorage);

        StringWriter out = new StringWriter();
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute(LS, BACKUP_PREFIX, LONG_FLAG, RECURSIVE_FLAG);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_OK);
        assertThat(out.toString())
                .contains(StorageClass.DEEP_ARCHIVE.toString())
                .contains("ready-until " + readyUntil)
                .contains(key);
    }

    @Test
    void lsLongFormatRendersDashForNotStartedRestoreState() throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        String key = "backup/archive.dat";
        seedObject(remoteStorage, key, StorageClass.DEEP_ARCHIVE);
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of());
        TestAppContext context = new TestAppContext(config, remoteStorage);

        StringWriter out = new StringWriter();
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute(LS, BACKUP_PREFIX, LONG_FLAG, RECURSIVE_FLAG);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_OK);
        assertThat(out.toString().lines().toList()).anySatisfy(line -> assertThat(line).contains(" - ").contains(key));
    }

    private void seedObject(FakeRemoteStorage remoteStorage, String key, StorageClass storageClass) throws IOException {
        Path source = tempDir.resolve(key.replace('/', '_'));
        Files.writeString(source, "data");
        remoteStorage.put(source, key, storageClass);
    }
}
