package com.functorful.glacierbackup.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import picocli.CommandLine;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

@Testcontainers
@Tag("integration")
class EndToEndIT {

    private static final DockerImageName LOCALSTACK_IMAGE = DockerImageName.parse("localstack/localstack:3.8");
    private static final String BUCKET = "glacier-backup-e2e";
    private static final String REMOTE_PREFIX = "data/";
    private static final String CONFIG_FILE_NAME = "config.yml";
    private static final String FIRST_FILE_NAME = "first.txt";
    private static final String SECOND_FILE_NAME = "second.txt";
    private static final String FIRST_FILE_CONTENT = "first-file-bytes";
    private static final String SECOND_FILE_CONTENT = "second-file-bytes";
    private static final String CONFIG_OPTION = "--config";
    private static final String SYNC = "sync";
    private static final String LS = "ls";
    private static final String RM = "rm";
    private static final String GET = "get";
    private static final String RECURSIVE_OPTION = "--recursive";

    @Container
    private static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(LOCALSTACK_IMAGE).withServices(LocalStackContainer.Service.S3);

    @BeforeAll
    static void createBucket() {
        URI endpoint = LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.S3);
        StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey()));
        try (S3Client s3Client = S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(credentialsProvider)
                .forcePathStyle(true)
                .build()) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
    }

    @Test
    void syncLsRmAndGetWireThroughToRealS3Adapter(@TempDir Path sourceDir, @TempDir Path targetDir) throws Exception {
        Files.writeString(sourceDir.resolve(FIRST_FILE_NAME), FIRST_FILE_CONTENT);
        Files.writeString(sourceDir.resolve(SECOND_FILE_NAME), SECOND_FILE_CONTENT);
        Path configPath = writeConfig(sourceDir.resolveSibling(CONFIG_FILE_NAME));
        String firstKey = REMOTE_PREFIX + FIRST_FILE_NAME;
        String secondKey = REMOTE_PREFIX + SECOND_FILE_NAME;

        CliResult syncResult = run(configPath, SYNC, sourceDir.toString(), REMOTE_PREFIX);
        assertThat(syncResult.exitCode()).as("sync must succeed").isEqualTo(GlacierBackupCommand.EXIT_OK);

        CliResult lsAfterSync = run(configPath, LS, REMOTE_PREFIX, RECURSIVE_OPTION);
        assertThat(lsAfterSync.exitCode()).as("ls must succeed").isEqualTo(GlacierBackupCommand.EXIT_OK);
        assertThat(LogLines.messages(lsAfterSync.out())).as("both synced keys must be listed").contains(firstKey, secondKey);

        CliResult rmResult = run(configPath, RM, firstKey);
        assertThat(rmResult.exitCode()).as("rm must succeed").isEqualTo(GlacierBackupCommand.EXIT_OK);

        CliResult lsAfterRm = run(configPath, LS, REMOTE_PREFIX, RECURSIVE_OPTION);
        assertThat(LogLines.messages(lsAfterRm.out()))
                .as("deleted key must no longer be listed and the survivor must remain")
                .containsExactly(secondKey);

        CliResult getResult = run(configPath, GET, secondKey, targetDir.toString());
        assertThat(getResult.exitCode()).as("get must succeed").isEqualTo(GlacierBackupCommand.EXIT_OK);
        assertThat(Files.readAllBytes(targetDir.resolve(secondKey)))
                .as("downloaded bytes must match the source file")
                .isEqualTo(SECOND_FILE_CONTENT.getBytes());
    }

    private static Path writeConfig(Path configPath) throws Exception {
        String yaml = """
                bucket: %s
                region: %s
                defaults:
                  storageClass: STANDARD
                """
                .formatted(BUCKET, LOCALSTACK.getRegion());
        Files.writeString(configPath, yaml);
        return configPath;
    }

    private static CliResult run(Path configPath, String... args) {
        URI endpoint = LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.S3);
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(options -> S3AppContext.create(options, endpoint));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));

        String[] fullArgs = new String[args.length + 2];
        fullArgs[0] = CONFIG_OPTION;
        fullArgs[1] = configPath.toString();
        System.arraycopy(args, 0, fullArgs, 2, args.length);

        int exitCode = commandLine.execute(fullArgs);
        return new CliResult(exitCode, out.toString(), err.toString());
    }

    private record CliResult(int exitCode, String out, String err) {}
}
