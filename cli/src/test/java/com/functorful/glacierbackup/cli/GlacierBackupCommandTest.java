package com.functorful.glacierbackup.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class GlacierBackupCommandTest {

    @Test
    void printsUsageWhenNoSubcommandGiven() {
        StringWriter err = new StringWriter();
        CommandLine commandLine = new CommandLine(new GlacierBackupCommand());
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute();

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_USAGE);
        assertThat(err.toString()).contains(GlacierBackupCommand.TOOL_NAME);
    }

    @Test
    void printsBuildVersion() {
        StringWriter out = new StringWriter();
        CommandLine commandLine = GlacierBackupCommand.createCommandLine(configPath -> {
            throw new AssertionError("--version must not create an app context");
        });
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("--version");

        assertThat(exitCode).isEqualTo(GlacierBackupCommand.EXIT_OK);
        assertThat(out.toString().trim())
                .isEqualTo(GlacierBackupCommand.TOOL_NAME + " " + BuildVersion.VERSION);
    }
}
