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

class RmCommandTest {

    private static final String BUCKET = "test-bucket";
    private static final String REGION = "eu-west-1";
    private static final String RM = "rm";
    private static final String RECURSIVE_FLAG = "--recursive";
    private static final String KEY_A = "backup/a.txt";
    private static final String KEY_B = "backup/b.txt";
    private static final String NESTED_KEY_A = "backup/dir/a.txt";
    private static final String NESTED_KEY_B = "backup/dir/b.txt";
    private static final String NESTED_PREFIX = "backup/dir";

    @TempDir
    private Path tempDir;

    @Test
    void rmWithoutRecursiveDeletesEachExactKey() throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        seedObject(remoteStorage, KEY_A);
        seedObject(remoteStorage, KEY_B);
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of());
        TestAppContext context = new TestAppContext(config, remoteStorage);

        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);

        int exitCode = commandLine.execute(RM, KEY_A, KEY_B);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_OK);
        assertThat(remoteStorage.storedObjects()).as("stored objects after rm").isEmpty();
    }

    @Test
    void rmWithRecursivePassesFlagThroughToDeleteAllUnderPrefix() throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        seedObject(remoteStorage, NESTED_KEY_A);
        seedObject(remoteStorage, NESTED_KEY_B);
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of());
        TestAppContext context = new TestAppContext(config, remoteStorage);

        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);

        int exitCode = commandLine.execute(RM, NESTED_PREFIX, RECURSIVE_FLAG);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_OK);
        assertThat(remoteStorage.storedObjects()).as("stored objects after recursive rm").isEmpty();
    }

    @Test
    void rmWithoutRecursiveOnAPrefixFailsBecauseKeyDoesNotExistExactly() throws IOException {
        FakeRemoteStorage remoteStorage = new FakeRemoteStorage();
        seedObject(remoteStorage, NESTED_KEY_A);
        ToolConfig config = new ToolConfig(BUCKET, REGION, StorageClass.DEEP_ARCHIVE, Map.of());
        TestAppContext context = new TestAppContext(config, remoteStorage);

        StringWriter err = new StringWriter();
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> context);
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute(RM, NESTED_PREFIX);

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_PARTIAL_FAILURE);
        assertThat(err.toString()).contains("1 failed:").contains(NESTED_PREFIX);
    }

    private void seedObject(FakeRemoteStorage remoteStorage, String key) throws IOException {
        Path source = tempDir.resolve(key.replace('/', '_'));
        Files.writeString(source, "data");
        remoteStorage.put(source, key, StorageClass.STANDARD);
    }
}
