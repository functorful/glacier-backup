package com.functorful.glacierbackup.cli;

import com.functorful.glacierbackup.application.UploadResult;
import com.functorful.glacierbackup.domain.StorageClass;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "up", description = "Upload local files to remote storage.")
public final class UpCommand extends AbstractCommand {

    @Parameters(arity = "2..*", paramLabel = "<local-file>... <remote-prefix>")
    private List<String> positionals = new ArrayList<>();

    @Option(names = "--storage-class", converter = StorageClassConverter.class, description = "Storage class for uploads.")
    private StorageClass storageClass;

    public UpCommand(Function<CliOptions, AppContext> contextFactory) {
        super(contextFactory);
    }

    @Override
    protected Integer doCall(AppContext context) {
        String remotePrefix = positionals.get(positionals.size() - 1);
        List<Path> files = positionals.subList(0, positionals.size() - 1).stream().map(Path::of).toList();
        StorageClass effectiveStorageClass = storageClass != null ? storageClass : context.config().defaultStorageClass();

        UploadResult result = context.uploadUseCase().run(files, remotePrefix, effectiveStorageClass);

        if (result.hasFailures()) {
            printFailures(result.failures());
            return GlacierBackupCommand.EXIT_PARTIAL_FAILURE;
        }
        return GlacierBackupCommand.EXIT_OK;
    }
}
