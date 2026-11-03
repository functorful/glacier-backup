package com.functorful.glacierbackup.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.functorful.glacierbackup.application.testing.FakeRemoteStorage;
import com.functorful.glacierbackup.cli.config.ToolConfig;
import com.functorful.glacierbackup.domain.RestoreTier;
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

class RestoreCommandTest {

    private static final String BUCKET = "test-bucket";
    private static final String REGION = "eu-west-1";
    private static final String RESTORE = "restore";
    private static final String TIER_FLAG = "--tier";
    private static final String BULK = "bulk";
    private static final String DAYS_FLAG = "--days";
    private static final String KEY = "backup/archive.dat";
    private static final String MISSING_KEY = "backup/missing.dat";
    private static final int DEFAULT_DAYS = 7;

    @TempDir
    private Path tempDir;

    @Test
    void restoreWithTierOptionRequestsThatTierFromTheUseCase() throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        seedObject(remoteStorage, KEY, StorageClass.GLACIER);
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of());
        TestAppContext context = new TestAppContext(config, remoteStorage);

        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);

        int exitCode = commandLine.execute(RESTORE, KEY, TIER_FLAG, BULK);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_OK);
        assertThat(remoteStorage.restoreCalls())
                .as("recorded restore calls")
                .containsExactly(new FakeRemoteStorage.RestoreCall(KEY, RestoreTier.BULK, DEFAULT_DAYS));
    }

    @Test
    void restoreWithoutDaysOptionDefaultsToSevenDays() throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        seedObject(remoteStorage, KEY, StorageClass.GLACIER);
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of());
        TestAppContext context = new TestAppContext(config, remoteStorage);

        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);

        int exitCode = commandLine.execute(RESTORE, KEY);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_OK);
        assertThat(remoteStorage.restoreCalls())
                .as("recorded restore calls")
                .containsExactly(new FakeRemoteStorage.RestoreCall(KEY, RestoreTier.STANDARD, DEFAULT_DAYS));
    }

    @Test
    void restoreWithExplicitDaysOptionPassesItThrough() throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        seedObject(remoteStorage, KEY, StorageClass.GLACIER);
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of());
        TestAppContext context = new TestAppContext(config, remoteStorage);

        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);

        int exitCode = commandLine.execute(RESTORE, KEY, DAYS_FLAG, "3");

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_OK);
        assertThat(remoteStorage.restoreCalls())
                .as("recorded restore calls")
                .containsExactly(new FakeRemoteStorage.RestoreCall(KEY, RestoreTier.STANDARD, 3));
    }

    @Test
    void restoreOfMissingKeyFailsWithPartialFailureExitCode() {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of());
        TestAppContext context = new TestAppContext(config, remoteStorage);

        StringWriter err = new StringWriter();
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute(RESTORE, MISSING_KEY);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_PARTIAL_FAILURE);
        assertThat(err.toString()).contains("1 failed:").contains(MISSING_KEY);
    }

    private void seedObject(FakeRemoteStorage remoteStorage, String key, StorageClass storageClass) throws IOException {
        Path source = tempDir.resolve(key.replace('/', '_'));
        Files.writeString(source, "data");
        remoteStorage.put(source, key, storageClass);
    }
}
