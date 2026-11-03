package com.functorful.glacierbackup.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.functorful.glacierbackup.application.SyncUseCase;
import com.functorful.glacierbackup.application.port.ProgressReporter;
import com.functorful.glacierbackup.application.testing.FakeLocalVolume;
import com.functorful.glacierbackup.application.testing.FakeRemoteStorage;
import com.functorful.glacierbackup.cli.config.BackupSet;
import com.functorful.glacierbackup.cli.config.ConfigException;
import com.functorful.glacierbackup.cli.config.ToolConfig;
import com.functorful.glacierbackup.domain.LocalSnapshot;
import com.functorful.glacierbackup.domain.StorageClass;
import com.functorful.glacierbackup.domain.SyncPlanner;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class SyncCommandTest {

    private static final String BUCKET = "test-bucket";
    private static final String REGION = "eu-west-1";
    private static final String SYNC = "sync";
    private static final String MEDIA_SET = "media";
    private static final String BACKUP_PREFIX = "backup";
    private static final String DRY_RUN_FLAG = "--dry-run";
    private static final String DELETE_FLAG = "--delete";
    private static final String KEEP_FILE_NAME = "keep.txt";
    private static final String SKIP_DIR_NAME = "skip";
    private static final String IGNORED_FILE_NAME = "ignored.txt";
    private static final String KEEP_KEY = "backup/keep.txt";
    private static final String DIRECT_PREFIX = "direct";
    private static final String VERBOSE_FLAG = "-v";
    private static final String CONFIG_NOT_FOUND_MESSAGE = "Config file not found: missing.yml";
    private static final String CONFIG_READ_FAILURE_MESSAGE = "Failed to read config file: broken.yml";
    private static final String CONFIG_READ_FAILURE_CAUSE_MESSAGE = "Permission denied";
    private static final String CAUSED_BY_MARKER = "caused by";
    private static final int VANISHED_DIRECTORY_COUNT = 3;

    @TempDir
    private Path tempDir;

    @Test
    void syncWithBackupSetNameResolvesLocalPrefixAndFiltersFromConfig() throws IOException {
        Files.writeString(tempDir.resolve(KEEP_FILE_NAME), "hello");
        Path skipDir = Files.createDirectory(tempDir.resolve(SKIP_DIR_NAME));
        Files.writeString(skipDir.resolve(IGNORED_FILE_NAME), "nope");

        BackupSet backupSet = new BackupSet(tempDir, BACKUP_PREFIX, List.of("*.txt"), List.of("skip/**"));
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.GLACIER, Map.of(MEDIA_SET, backupSet));
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        TestAppContext context = new TestAppContext(config, remoteStorage);

        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);

        int exitCode = commandLine.execute(SYNC, MEDIA_SET);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_OK);
        assertThat(remoteStorage.storedObjects()).containsOnlyKeys(KEEP_KEY);
        assertThat(remoteStorage.storedObjects().get(KEEP_KEY).storageClass()).isEqualTo(StorageClass.GLACIER);
    }

    @Test
    void syncWithTwoPositionalArgsUsesLocalPathAndPrefixDirectly() throws IOException {
        Files.writeString(tempDir.resolve(KEEP_FILE_NAME), "hello");
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.STANDARD, Map.of());
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        TestAppContext context = new TestAppContext(config, remoteStorage);

        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);

        int exitCode = commandLine.execute(SYNC, tempDir.toString(), DIRECT_PREFIX);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_OK);
        assertThat(remoteStorage.storedObjects()).containsOnlyKeys(DIRECT_PREFIX + "/" + KEEP_FILE_NAME);
    }

    @Test
    void syncDryRunPrintsSummaryOnlyAndExitsOkWithoutExecuting() throws IOException {
        Files.writeString(tempDir.resolve(KEEP_FILE_NAME), "hello");
        BackupSet backupSet = new BackupSet(tempDir, BACKUP_PREFIX, List.of(), List.of());
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of(MEDIA_SET, backupSet));
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        TestAppContext context = new TestAppContext(config, remoteStorage);

        StringWriter out = new StringWriter();
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute(SYNC, MEDIA_SET, DRY_RUN_FLAG);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_OK);
        assertThat(remoteStorage.storedObjects()).as("stored objects after dry run").isEmpty();
        assertThat(out.toString())
                .contains("1" + SyncCommand.TO_UPLOAD_BY_REASON + SyncPlanner.REASON_NEW)
                .contains("1" + SyncCommand.TO_UPLOAD_TOTAL)
                .doesNotContain(SyncCommand.UPLOAD_LINE_PREFIX)
                .doesNotContain(SyncCommand.DELETE_LINE_PREFIX);
    }

    @Test
    void syncDryRunWithRefusedDeletesPrintsFailuresAndExitsPartialFailure() {
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of());
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        FakeLocalVolume localVolume =
                FakeLocalVolume.withSnapshotAndVanishedDirectories(LocalSnapshot.of(List.of()), VANISHED_DIRECTORY_COUNT);
        TestAppContext context = new TestAppContext(config, remoteStorage, localVolume, ProgressReporter.noOp());

        StringWriter err = new StringWriter();
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute(SYNC, tempDir.toString(), DIRECT_PREFIX, DELETE_FLAG, DRY_RUN_FLAG);

        assertThat(exitCode)
                .as("a dry run that refuses deletes must not exit as if it succeeded")
                .isEqualTo(GlacierBackupCommand.EXIT_PARTIAL_FAILURE);
        assertThat(err.toString())
                .as("the refusal must be visible in the dry run's own failure output, not just the ERROR log line")
                .contains(SyncUseCase.REFUSING_DELETES_MESSAGE.formatted(VANISHED_DIRECTORY_COUNT));
    }

    @Test
    void syncDryRunWithoutRefusalStillExitsOk() throws IOException {
        Files.writeString(tempDir.resolve(KEEP_FILE_NAME), "hello");
        BackupSet backupSet = new BackupSet(tempDir, BACKUP_PREFIX, List.of(), List.of());
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of(MEDIA_SET, backupSet));
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        TestAppContext context = new TestAppContext(config, remoteStorage);

        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);

        int exitCode = commandLine.execute(SYNC, MEDIA_SET, DRY_RUN_FLAG);

        assertThat(exitCode).as("a dry run with nothing to refuse must still exit OK").isEqualTo(GlacierBackupCommand.EXIT_OK);
    }

    @Test
    void syncDryRunVerbosePrintsPerFilePlanLinesOnStdout() throws IOException {
        Files.writeString(tempDir.resolve(KEEP_FILE_NAME), "hello");
        BackupSet backupSet = new BackupSet(tempDir, BACKUP_PREFIX, List.of(), List.of());
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of(MEDIA_SET, backupSet));
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        TestAppContext context = new TestAppContext(config, remoteStorage);

        StringWriter out = new StringWriter();
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute(VERBOSE_FLAG, SYNC, MEDIA_SET, DRY_RUN_FLAG);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_OK);
        assertThat(remoteStorage.storedObjects()).as("stored objects after dry run").isEmpty();
        assertThat(out.toString())
                .contains(SyncCommand.UPLOAD_LINE_PREFIX + SyncPlanner.REASON_NEW + ") " + KEEP_FILE_NAME)
                .contains("1" + SyncCommand.TO_UPLOAD_TOTAL);
    }

    @Test
    void syncWithFailuresExitsPartialFailureAndPrintsFailedKeys() throws IOException {
        Files.writeString(tempDir.resolve("broken.txt"), "content");
        BackupSet backupSet = new BackupSet(tempDir, BACKUP_PREFIX, List.of(), List.of());
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of(MEDIA_SET, backupSet));
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        String failingKey = "backup/broken.txt";
        remoteStorage.failOnKey(failingKey);
        TestAppContext context = new TestAppContext(config, remoteStorage);

        StringWriter err = new StringWriter();
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute(SYNC, MEDIA_SET);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_PARTIAL_FAILURE);
        assertThat(err.toString()).contains("1 failed:").contains(failingKey);
    }

    @Test
    void syncWithUnknownBackupSetExitsUsage() {
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of());
        TestAppContext context = new TestAppContext(config, new FakeRemoteStorage());

        StringWriter err = new StringWriter();
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute(SYNC, "unknown-set");

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_USAGE);
        assertThat(err.toString()).contains("unknown-set");
    }

    @Test
    void syncWithBackupSetNameAndExplicitPrefixExitsUsage() {
        BackupSet backupSet = new BackupSet(tempDir, BACKUP_PREFIX, List.of(), List.of());
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of(MEDIA_SET, backupSet));
        TestAppContext context = new TestAppContext(config, new FakeRemoteStorage());

        StringWriter err = new StringWriter();
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute(SYNC, MEDIA_SET, "other-prefix");

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_USAGE);
    }

    @Test
    void syncVerbosePrintsDebugPhaseLinesOnlyWithVerboseFlag() throws IOException {
        Files.writeString(tempDir.resolve(KEEP_FILE_NAME), "hello");
        BackupSet backupSet = new BackupSet(tempDir, BACKUP_PREFIX, List.of(), List.of());
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of(MEDIA_SET, backupSet));

        String quietErr = runSync(config);
        String verboseErr = runSync(config, VERBOSE_FLAG);

        assertThat(quietErr).doesNotContain(TimestampedLog.LEVEL_DEBUG);
        assertThat(verboseErr)
                .contains(" " + TimestampedLog.LEVEL_DEBUG + " ")
                .contains("scanning local root " + tempDir)
                .contains("listing remote prefix '" + BACKUP_PREFIX + "'")
                .contains("planning sync");
    }

    private String runSync(ToolConfig config, String... globalFlags) {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        StringWriter err = new StringWriter();
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(options -> new TestAppContext(
                config,
                remoteStorage,
                FakeLocalVolume.scanningRealDirectory(),
                new ConsoleProgressReporter(options.log(), Clock.systemDefaultZone())));
        commandLine.setErr(new PrintWriter(err));

        String[] args = new String[globalFlags.length + 2];
        System.arraycopy(globalFlags, 0, args, 0, globalFlags.length);
        args[globalFlags.length] = SYNC;
        args[globalFlags.length + 1] = MEDIA_SET;
        int exitCode = commandLine.execute(args);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_OK);
        return err.toString();
    }

    @Test
    void syncPropagatesConfigExceptionAsUsageError() {
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> {
            throw new ConfigException(CONFIG_NOT_FOUND_MESSAGE);
        });
        StringWriter err = new StringWriter();
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute(SYNC, MEDIA_SET);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_USAGE);
        assertThat(err.toString()).contains(CONFIG_NOT_FOUND_MESSAGE).doesNotContain(CAUSED_BY_MARKER);
    }

    @Test
    void syncPropagatesConfigExceptionCauseAsUsageError() {
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> {
            throw new ConfigException(CONFIG_READ_FAILURE_MESSAGE, new IOException(CONFIG_READ_FAILURE_CAUSE_MESSAGE));
        });
        StringWriter err = new StringWriter();
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute(SYNC, MEDIA_SET);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_USAGE);
        assertThat(err.toString())
                .contains(CONFIG_READ_FAILURE_MESSAGE)
                .contains(IOException.class.getSimpleName())
                .contains(CONFIG_READ_FAILURE_CAUSE_MESSAGE);
    }
}
