package com.functorful.glacierbackup.cli;

import com.functorful.glacierbackup.application.RestoreResult;
import com.functorful.glacierbackup.domain.RestoreTier;
import java.util.List;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "restore", description = "Initiate a restore for remote objects in Glacier storage classes.")
public final class RestoreCommand extends AbstractCommand {

    private static final int DEFAULT_DAYS = 7;

    @Parameters(arity = "1..*", paramLabel = "<remote-path>")
    private List<String> remotePaths;

    @Option(names = "--tier", converter = RestoreTierConverter.class, description = "Restore tier: bulk, standard or expedited.")
    private RestoreTier tier = RestoreTier.STANDARD;

    @Option(names = "--days", description = "Number of days the restored copy remains available.")
    private int days = DEFAULT_DAYS;

    @Option(names = "--recursive", description = "Restore all objects under the given prefixes.")
    private boolean recursive;

    public RestoreCommand(Function<CliOptions, AppContext> contextFactory) {
        super(contextFactory);
    }

    @Override
    protected Integer doCall(AppContext context) {
        RestoreResult result = context.restoreUseCase().run(remotePaths, tier, days, recursive);

        if (result.hasFailures()) {
            printFailures(result.failures());
            return GlacierBackupCommand.EXIT_PARTIAL_FAILURE;
        }
        return GlacierBackupCommand.EXIT_OK;
    }
}
