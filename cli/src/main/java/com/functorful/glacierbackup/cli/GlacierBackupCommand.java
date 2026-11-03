package com.functorful.glacierbackup.cli;

import com.functorful.glacierbackup.infrastructure.fs.NioLocalVolume;
import io.micronaut.configuration.picocli.MicronautFactory;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.Callable;
import java.util.function.Function;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = GlacierBackupCommand.TOOL_NAME, mixinStandardHelpOptions = true,
        description = "Backs up volumes to AWS S3 Glacier storage classes.")
public class GlacierBackupCommand implements Callable<Integer> {

    public static final String TOOL_NAME = "glacier-backup";

    public static final int EXIT_OK = 0;
    public static final int EXIT_PARTIAL_FAILURE = 1;
    public static final int EXIT_USAGE = 2;

    @Option(names = "--config", description = "Path to the configuration file.")
    private Path configPath;

    @Option(names = {"-v", "--verbose"}, description = "Print detailed progress output.")
    private boolean verbose;

    @Option(names = "--scan-threads", paramLabel = "<n>",
            description = "Number of concurrent local scan threads ("
                    + NioLocalVolume.MIN_SCAN_THREADS + "-" + NioLocalVolume.MAX_SCAN_THREADS
                    + ", default " + NioLocalVolume.DEFAULT_SCAN_THREADS + ").")
    private Integer scanThreads;

    @Spec
    private CommandSpec spec;

    private final Clock clock;

    public GlacierBackupCommand() {
        this(Clock.systemDefaultZone());
    }

    GlacierBackupCommand(Clock clock) {
        this.clock = clock;
    }

    public Optional<Path> configPath() {
        return Optional.ofNullable(configPath);
    }

    public boolean verbose() {
        return verbose;
    }

    public OptionalInt scanThreads() {
        return scanThreads == null ? OptionalInt.empty() : OptionalInt.of(scanThreads);
    }

    Clock clock() {
        return clock;
    }

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getErr());
        return EXIT_USAGE;
    }

    public static CommandLine createCommandLine(Function<CliOptions, AppContext> contextFactory) {
        return createCommandLine(contextFactory, CommandLine.defaultFactory(), Clock.systemDefaultZone());
    }

    static CommandLine createCommandLine(Function<CliOptions, AppContext> contextFactory, Clock clock) {
        return createCommandLine(contextFactory, CommandLine.defaultFactory(), clock);
    }

    public static CommandLine createCommandLine(Function<CliOptions, AppContext> contextFactory, CommandLine.IFactory factory) {
        return createCommandLine(contextFactory, factory, Clock.systemDefaultZone());
    }

    private static CommandLine createCommandLine(
            Function<CliOptions, AppContext> contextFactory, CommandLine.IFactory factory, Clock clock) {
        CommandLine commandLine = new CommandLine(new GlacierBackupCommand(clock), factory)
                .addSubcommand(new SyncCommand(contextFactory))
                .addSubcommand(new UpCommand(contextFactory))
                .addSubcommand(new RmCommand(contextFactory))
                .addSubcommand(new LsCommand(contextFactory))
                .addSubcommand(new RestoreCommand(contextFactory))
                .addSubcommand(new RestoreStatusCommand(contextFactory))
                .addSubcommand(new GetCommand(contextFactory));
        // Set programmatically rather than via the annotation so the value can
        // come from the build-generated BuildVersion constant without any
        // native-image reflection or resource registration.
        commandLine.getCommandSpec().version(TOOL_NAME + " " + BuildVersion.VERSION);
        return commandLine;
    }

    public static void main(String[] args) {
        int exitCode;
        try (ApplicationContext context = ApplicationContext.run(Environment.CLI)) {
            exitCode = createCommandLine(S3AppContext::create, new MicronautFactory(context)).execute(args);
        }
        System.exit(exitCode);
    }
}
