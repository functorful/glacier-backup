package com.functorful.glacierbackup.cli;

import com.functorful.glacierbackup.application.SyncResult;
import com.functorful.glacierbackup.cli.config.BackupSet;
import com.functorful.glacierbackup.domain.PathFilter;
import com.functorful.glacierbackup.domain.PlannedAction;
import com.functorful.glacierbackup.domain.StorageClass;
import com.functorful.glacierbackup.domain.SyncPolicy;
import com.functorful.glacierbackup.domain.SyncSummary;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "sync", description = "Synchronize a local directory or configured backup set to remote storage.")
public final class SyncCommand extends AbstractCommand {

    static final String UPLOAD_LINE_PREFIX = "UPLOAD (";
    static final String DELETE_LINE_PREFIX = "DELETE (";
    static final String TO_UPLOAD_BY_REASON = " to upload: ";
    static final String TO_DELETE_BY_REASON = " to delete: ";
    static final String TO_UPLOAD_TOTAL = " to upload (";

    @Parameters(arity = "1..2", paramLabel = "<local-path|set-name> [remote-prefix]")
    private List<String> positionals = new ArrayList<>();

    @Option(names = "--delete", description = "Delete remote objects with no local counterpart.")
    private boolean delete;

    @Option(names = "--dry-run", description = "Print the plan without executing it.")
    private boolean dryRun;

    @Option(names = "--exclude", description = "Glob pattern to exclude.")
    private List<String> excludeGlobs = new ArrayList<>();

    @Option(names = "--include", description = "Glob pattern to include.")
    private List<String> includeGlobs = new ArrayList<>();

    @Option(names = "--storage-class", converter = StorageClassConverter.class, description = "Storage class for uploads.")
    private StorageClass storageClass;

    public SyncCommand(Function<CliOptions, AppContext> contextFactory) {
        super(contextFactory);
    }

    @Override
    protected Integer doCall(AppContext context) {
        String firstPositional = positionals.get(0);
        String secondPositional = positionals.size() > 1 ? positionals.get(1) : null;
        Optional<BackupSet> backupSet = Optional.ofNullable(context.config().backupSets().get(firstPositional));

        if (secondPositional != null && backupSet.isPresent()) {
            log().error("Cannot use a backup set name together with a remote prefix: " + firstPositional);
            return GlacierBackupCommand.EXIT_USAGE;
        }

        Path localRoot;
        String remotePrefix;
        List<String> effectiveIncludes = new ArrayList<>(includeGlobs);
        List<String> effectiveExcludes = new ArrayList<>(excludeGlobs);

        if (secondPositional == null) {
            if (backupSet.isEmpty()) {
                log().error("Unknown backup set: " + firstPositional);
                return GlacierBackupCommand.EXIT_USAGE;
            }
            BackupSet set = backupSet.get();
            localRoot = set.local();
            remotePrefix = set.prefix();
            effectiveIncludes.addAll(0, set.include());
            effectiveExcludes.addAll(0, set.exclude());
        } else {
            localRoot = Path.of(firstPositional);
            remotePrefix = secondPositional;
        }

        StorageClass effectiveStorageClass = storageClass != null ? storageClass : context.config().defaultStorageClass();
        PathFilter filter = PathFilter.of(effectiveIncludes, effectiveExcludes);
        SyncPolicy policy = new SyncPolicy(delete, effectiveStorageClass, filter);

        SyncResult result = context.syncUseCase().run(localRoot, remotePrefix, policy, dryRun);

        if (dryRun) {
            printPlan(result);
        }

        if (result.hasFailures()) {
            printFailures(result.failures());
            return GlacierBackupCommand.EXIT_PARTIAL_FAILURE;
        }
        return GlacierBackupCommand.EXIT_OK;
    }

    private void printPlan(SyncResult result) {
        if (verbose()) {
            for (PlannedAction.Upload upload : result.plan().uploads()) {
                log().info(UPLOAD_LINE_PREFIX + upload.reason() + ") " + upload.file().path().value());
            }
            for (PlannedAction.DeleteRemote deleteAction : result.plan().deletes()) {
                log().info(DELETE_LINE_PREFIX + deleteAction.reason() + ") " + deleteAction.path().value());
            }
        }
        Map<String, Long> uploadsByReason = new LinkedHashMap<>();
        for (PlannedAction.Upload upload : result.plan().uploads()) {
            uploadsByReason.merge(upload.reason(), 1L, Long::sum);
        }
        Map<String, Long> deletesByReason = new LinkedHashMap<>();
        for (PlannedAction.DeleteRemote deleteAction : result.plan().deletes()) {
            deletesByReason.merge(deleteAction.reason(), 1L, Long::sum);
        }
        uploadsByReason.forEach((reason, count) -> log().info(count + TO_UPLOAD_BY_REASON + reason));
        deletesByReason.forEach((reason, count) -> log().info(count + TO_DELETE_BY_REASON + reason));
        SyncSummary summary = result.plan().summary();
        log().info(summary.uploadCount() + TO_UPLOAD_TOTAL + summary.bytesToUpload() + " bytes), " + summary.deleteCount()
                + " to delete, " + summary.skipCount() + " skipped");
    }
}
