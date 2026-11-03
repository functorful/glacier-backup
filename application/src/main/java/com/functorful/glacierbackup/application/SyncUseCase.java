package com.functorful.glacierbackup.application;

import com.functorful.glacierbackup.application.port.LocalScanResult;
import com.functorful.glacierbackup.application.port.LocalVolume;
import com.functorful.glacierbackup.application.port.ProgressReporter;
import com.functorful.glacierbackup.application.port.RemoteStorage;
import com.functorful.glacierbackup.application.port.RemoteStorageException;
import com.functorful.glacierbackup.domain.PlannedAction;
import com.functorful.glacierbackup.domain.RemoteSnapshot;
import com.functorful.glacierbackup.domain.SyncPlan;
import com.functorful.glacierbackup.domain.SyncPlanner;
import com.functorful.glacierbackup.domain.SyncPolicy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public final class SyncUseCase {

    static final String REMOTE_LIST_THREAD_NAME = "remote-list";
    public static final String REFUSING_DELETES_MESSAGE = "refusing to plan remote deletes: scan skipped %d vanished directories";
    // Bracketed so it can never be mistaken for a real remote key in printed failure
    // output, which otherwise lists nothing but transferable object keys.
    static final String REFUSING_DELETES_FAILURE_KEY = "[delete-plan]";

    private final LocalVolume localVolume;
    private final RemoteStorage remoteStorage;
    private final ProgressReporter progressReporter;

    public SyncUseCase(LocalVolume localVolume, RemoteStorage remoteStorage, ProgressReporter progressReporter) {
        this.localVolume = localVolume;
        this.remoteStorage = remoteStorage;
        this.progressReporter = progressReporter;
    }

    public SyncResult run(Path localRoot, String remotePrefix, SyncPolicy policy, boolean dryRun) {
        // The remote listing shares no state with the local scan, so it runs on a
        // background thread while the scan keeps the calling thread (and all
        // progress reporting, which is not thread-safe) to itself. The listing's
        // reported duration is only the residual wait after the scan finishes.
        FutureTask<RemoteSnapshot> listTask = new FutureTask<>(() -> remoteStorage.list(remotePrefix));
        Thread listThread = new Thread(listTask, REMOTE_LIST_THREAD_NAME);
        listThread.setDaemon(true);
        listThread.start();

        progressReporter.phaseStarted("scanning local root " + localRoot);
        LocalScanResult scanResult;
        try {
            scanResult = localVolume.scan(localRoot, policy.filter());
        } catch (RuntimeException e) {
            listTask.cancel(true);
            throw e;
        }
        progressReporter.phaseCompleted("local scan", scanResult.snapshot().size());

        progressReporter.phaseStarted("listing remote prefix '" + remotePrefix + "'");
        RemoteSnapshot remote = awaitRemoteListing(listTask, remotePrefix);
        progressReporter.phaseCompleted("remote listing", remote.size());

        SyncPolicy effectivePolicy = policy;
        List<FailedItem> failures = new ArrayList<>();
        if (policy.deleteRemote() && scanResult.vanishedDirectoryCount() > 0) {
            String message = REFUSING_DELETES_MESSAGE.formatted(scanResult.vanishedDirectoryCount());
            progressReporter.error(message);
            failures.add(new FailedItem(REFUSING_DELETES_FAILURE_KEY, message));
            effectivePolicy = new SyncPolicy(false, policy.storageClass(), policy.filter());
        }

        progressReporter.phaseStarted("planning sync");
        SyncPlan plan = SyncPlanner.plan(scanResult.snapshot(), remote, effectivePolicy, scanResult.vanishedDirectoryCount());
        progressReporter.phaseCompleted("sync plan", plan.summary().uploadCount() + plan.summary().deleteCount());

        if (dryRun) {
            return new SyncResult(plan, List.of(), List.of(), List.copyOf(failures), true);
        }

        List<String> uploadedKeys = new ArrayList<>();
        List<String> deletedKeys = new ArrayList<>();

        for (PlannedAction.Upload upload : plan.uploads()) {
            String key = RemoteKeys.keyFor(remotePrefix, upload.file().path());
            progressReporter.started(key, upload.file().sizeBytes());
            try {
                Path source = scanResult.sourceOverrides().getOrDefault(upload.file().path(), localRoot.resolve(upload.file().path().value()));
                remoteStorage.put(source, key, upload.storageClass());
                uploadedKeys.add(key);
                progressReporter.completed(key);
            } catch (RuntimeException e) {
                // One item's failure — whatever its type — must never discard the
                // rest of the group or the failure summary.
                failures.add(new FailedItem(key, ErrorMessages.describe(e)));
                progressReporter.failed(key, e);
            }
        }

        for (PlannedAction.DeleteRemote delete : plan.deletes()) {
            String key = RemoteKeys.keyFor(remotePrefix, delete.path());
            progressReporter.started(key, 0L);
            try {
                remoteStorage.delete(key);
                deletedKeys.add(key);
                progressReporter.completed(key);
            } catch (RuntimeException e) {
                failures.add(new FailedItem(key, ErrorMessages.describe(e)));
                progressReporter.failed(key, e);
            }
        }

        return new SyncResult(plan, List.copyOf(uploadedKeys), List.copyOf(deletedKeys), List.copyOf(failures), false);
    }

    private static RemoteSnapshot awaitRemoteListing(FutureTask<RemoteSnapshot> listTask, String remotePrefix) {
        try {
            return listTask.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listTask.cancel(true);
            throw new RemoteStorageException("Interrupted while listing remote prefix '" + remotePrefix + "'", e);
        } catch (ExecutionException e) {
            // Surface the listing's own exception so error messages and exit-code
            // mapping stay identical to a direct list call.
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new RemoteStorageException("Failed to list remote prefix '" + remotePrefix + "'", e.getCause());
        }
    }
}
