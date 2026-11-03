package com.functorful.glacierbackup.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.functorful.glacierbackup.application.port.ProgressReporter;
import com.functorful.glacierbackup.application.testing.FakeRemoteStorage;
import com.functorful.glacierbackup.cli.config.ToolConfig;
import com.functorful.glacierbackup.domain.StorageClass;
import com.functorful.glacierbackup.infrastructure.fs.LocalVolumeException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class AbstractCommandTest {

    private static final String BUCKET = "test-bucket";
    private static final String REGION = "eu-west-1";
    private static final String LS = "ls";
    private static final String SYNC = "sync";
    private static final String RM = "rm";
    private static final String INVALID_KEY = "../evil.txt";
    private static final String LOCAL_VOLUME_FAILURE_MESSAGE = "Root is not an existing directory: /no/such/dir";
    private static final String ROOT_CAUSE_PATH = "/no/such/dir/gone";
    private static final String SCAN_THREADS_FLAG = "--scan-threads";
    private static final int FLAG_SCAN_THREADS = 7;
    private static final String NON_NUMERIC_SCAN_THREADS = "many";

    @TempDir
    private Path tempDir;

    @Test
    void remoteStorageExceptionPrintsCleanErrorAndExitsPartialFailure() {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        remoteStorage.failOnListPrefix("");
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of());
        TestAppContext context = new TestAppContext(config, remoteStorage);
        StringWriter err = new StringWriter();
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute(LS);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_PARTIAL_FAILURE);
        assertThat(err.toString())
                .contains(" " + TimestampedLog.LEVEL_ERROR + " ")
                .contains("Simulated failure for prefix")
                .doesNotContain("\tat ");
    }

    @Test
    void localVolumeExceptionPrintsCleanErrorAndExitsPartialFailure() {
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of());
        TestAppContext context = new TestAppContext(
                config,
                new FakeRemoteStorage(),
                (root, filter) -> {
                    throw new LocalVolumeException(LOCAL_VOLUME_FAILURE_MESSAGE);
                },
                ProgressReporter.noOp());
        StringWriter err = new StringWriter();
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute(SYNC, "/no/such/dir", "backup");

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_PARTIAL_FAILURE);
        assertThat(err.toString())
                .contains(" " + TimestampedLog.LEVEL_ERROR + " ")
                .contains(LOCAL_VOLUME_FAILURE_MESSAGE)
                .doesNotContain("\tat ");
    }

    @Test
    void localVolumeExceptionWithCausePrintsBothTheOuterMessageAndTheRootCause() {
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of());
        NoSuchFileException rootCause = new NoSuchFileException(ROOT_CAUSE_PATH);
        TestAppContext context = new TestAppContext(
                config,
                new FakeRemoteStorage(),
                (root, filter) -> {
                    throw new LocalVolumeException(LOCAL_VOLUME_FAILURE_MESSAGE, rootCause);
                },
                ProgressReporter.noOp());
        StringWriter err = new StringWriter();
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute(SYNC, "/no/such/dir", "backup");

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_PARTIAL_FAILURE);
        assertThat(err.toString())
                .contains(LOCAL_VOLUME_FAILURE_MESSAGE)
                .contains(NoSuchFileException.class.getSimpleName())
                .contains(rootCause.getMessage());
    }

    @Test
    void scanThreadsOptionIsHandedToTheContextFactory() {
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of());
        TestAppContext context = new TestAppContext(config, new FakeRemoteStorage());
        AtomicReference<OptionalInt> captured = new AtomicReference<>();
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(options -> {
            captured.set(options.scanThreads());
            return context;
        });

        int exitCode = commandLine.execute(SCAN_THREADS_FLAG, String.valueOf(FLAG_SCAN_THREADS), LS);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_OK);
        assertThat(captured.get()).hasValue(FLAG_SCAN_THREADS);
    }

    @Test
    void absentScanThreadsOptionReachesTheContextFactoryAsEmpty() {
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of());
        TestAppContext context = new TestAppContext(config, new FakeRemoteStorage());
        AtomicReference<OptionalInt> captured = new AtomicReference<>();
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(options -> {
            captured.set(options.scanThreads());
            return context;
        });

        int exitCode = commandLine.execute(LS);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_OK);
        assertThat(captured.get()).isEmpty();
    }

    @Test
    void nonNumericScanThreadsOptionExitsUsage() {
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(options -> {
            throw new AssertionError("an unparseable option must not create an app context");
        });
        commandLine.setErr(new PrintWriter(new StringWriter()));

        int exitCode = commandLine.execute(SCAN_THREADS_FLAG, NON_NUMERIC_SCAN_THREADS, LS);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_USAGE);
    }

    @Test
    void invalidRelativePathExceptionPrintsCleanErrorAndExitsPartialFailure() throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        Path sourceFile = tempDir.resolve("seed.bin");
        Files.writeString(sourceFile, "glacier-backup");
        remoteStorage.put(sourceFile, INVALID_KEY, StorageClass.STANDARD);
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of());
        TestAppContext context = new TestAppContext(config, remoteStorage);
        StringWriter err = new StringWriter();
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute(RM, INVALID_KEY);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_PARTIAL_FAILURE);
        assertThat(err.toString()).contains(" " + TimestampedLog.LEVEL_ERROR + " ").doesNotContain("\tat ");
    }
}
