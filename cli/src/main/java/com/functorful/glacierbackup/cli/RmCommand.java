package com.functorful.glacierbackup.cli;

import com.functorful.glacierbackup.application.DeleteResult;
import java.util.List;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "rm", description = "Delete remote objects.")
public final class RmCommand extends AbstractCommand {

    @Parameters(arity = "1..*", paramLabel = "<remote-path>")
    private List<String> remotePaths;

    @Option(names = "--recursive", description = "Delete all objects under the given prefixes.")
    private boolean recursive;

    public RmCommand(Function<CliOptions, AppContext> contextFactory) {
        super(contextFactory);
    }

    @Override
    protected Integer doCall(AppContext context) {
        DeleteResult result = context.deleteUseCase().run(remotePaths, recursive);

        if (result.hasFailures()) {
            printFailures(result.failures());
            return GlacierBackupCommand.EXIT_PARTIAL_FAILURE;
        }
        return GlacierBackupCommand.EXIT_OK;
    }
}
