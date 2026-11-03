package com.functorful.glacierbackup.cli;

import com.functorful.glacierbackup.application.DownloadResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "get", description = "Download remote objects to a local directory.")
public final class GetCommand extends AbstractCommand {

    @Parameters(arity = "2..*", paramLabel = "<remote-path>... <local-dir>")
    private List<String> positionals = new ArrayList<>();

    @Option(names = "--recursive", description = "Download all objects under the given prefixes.")
    private boolean recursive;

    public GetCommand(Function<CliOptions, AppContext> contextFactory) {
        super(contextFactory);
    }

    @Override
    protected Integer doCall(AppContext context) {
        List<String> remotePaths = positionals.subList(0, positionals.size() - 1);
        Path targetDir = Path.of(positionals.get(positionals.size() - 1));

        DownloadResult result = context.downloadUseCase().run(remotePaths, targetDir, recursive);

        if (result.hasFailures()) {
            printFailures(result.failures());
            return GlacierBackupCommand.EXIT_PARTIAL_FAILURE;
        }
        return GlacierBackupCommand.EXIT_OK;
    }
}
