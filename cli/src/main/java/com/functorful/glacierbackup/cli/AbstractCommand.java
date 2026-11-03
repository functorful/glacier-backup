package com.functorful.glacierbackup.cli;

import com.functorful.glacierbackup.application.ErrorMessages;
import com.functorful.glacierbackup.application.FailedItem;
import com.functorful.glacierbackup.application.port.Log;
import com.functorful.glacierbackup.application.port.RemoteStorageException;
import com.functorful.glacierbackup.cli.config.ConfigException;
import com.functorful.glacierbackup.domain.InvalidRelativePathException;
import com.functorful.glacierbackup.infrastructure.fs.LocalVolumeException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command
abstract class AbstractCommand implements Callable<Integer> {

    @ParentCommand
    private GlacierBackupCommand parent;

    @Spec
    private CommandSpec spec;

    private final Function<CliOptions, AppContext> contextFactory;

    private Log log;

    protected AbstractCommand(Function<CliOptions, AppContext> contextFactory) {
        this.contextFactory = contextFactory;
    }

    @Override
    public final Integer call() {
        try {
            return doCall(contextFactory.apply(new CliOptions(parent.configPath(), parent.verbose(), parent.scanThreads(), log())));
        } catch (ConfigException e) {
            log().error(ErrorMessages.describe(e));
            return GlacierBackupCommand.EXIT_USAGE;
        } catch (RemoteStorageException | LocalVolumeException | InvalidRelativePathException e) {
            log().error(ErrorMessages.describe(e));
            return GlacierBackupCommand.EXIT_PARTIAL_FAILURE;
        }
    }

    protected abstract Integer doCall(AppContext context);

    protected final boolean verbose() {
        return parent.verbose();
    }

    protected final Log log() {
        if (log == null) {
            log = new TimestampedLog(
                    spec.commandLine().getOut(), spec.commandLine().getErr(), parent.clock(), parent.verbose());
        }
        return log;
    }

    protected final void printFailures(List<FailedItem> failures) {
        log().error(failures.size() + " failed:");
        for (FailedItem failure : failures) {
            log().error(failure.key() + ": " + failure.message());
        }
    }
}
