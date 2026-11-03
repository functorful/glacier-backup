package com.functorful.glacierbackup.cli;

import com.functorful.glacierbackup.application.DeleteUseCase;
import com.functorful.glacierbackup.application.DownloadUseCase;
import com.functorful.glacierbackup.application.ListUseCase;
import com.functorful.glacierbackup.application.RestoreStatusUseCase;
import com.functorful.glacierbackup.application.RestoreUseCase;
import com.functorful.glacierbackup.application.RetryingRemoteStorage;
import com.functorful.glacierbackup.application.SyncUseCase;
import com.functorful.glacierbackup.application.UploadUseCase;
import com.functorful.glacierbackup.application.port.LocalVolume;
import com.functorful.glacierbackup.application.port.Log;
import com.functorful.glacierbackup.application.port.ProgressReporter;
import com.functorful.glacierbackup.application.port.RemoteStorage;
import com.functorful.glacierbackup.cli.config.ConfigException;
import com.functorful.glacierbackup.cli.config.ConfigLoader;
import com.functorful.glacierbackup.cli.config.ToolConfig;
import com.functorful.glacierbackup.infrastructure.fs.NioLocalVolume;
import com.functorful.glacierbackup.infrastructure.s3.S3RemoteStorageFactory;
import java.net.URI;
import java.time.Clock;
import java.util.OptionalInt;

public final class S3AppContext implements AppContext {

    private final ToolConfig config;
    private final SyncUseCase syncUseCase;
    private final UploadUseCase uploadUseCase;
    private final DeleteUseCase deleteUseCase;
    private final ListUseCase listUseCase;
    private final RestoreUseCase restoreUseCase;
    private final RestoreStatusUseCase restoreStatusUseCase;
    private final DownloadUseCase downloadUseCase;

    private S3AppContext(
            ToolConfig config,
            SyncUseCase syncUseCase,
            UploadUseCase uploadUseCase,
            DeleteUseCase deleteUseCase,
            ListUseCase listUseCase,
            RestoreUseCase restoreUseCase,
            RestoreStatusUseCase restoreStatusUseCase,
            DownloadUseCase downloadUseCase) {
        this.config = config;
        this.syncUseCase = syncUseCase;
        this.uploadUseCase = uploadUseCase;
        this.deleteUseCase = deleteUseCase;
        this.listUseCase = listUseCase;
        this.restoreUseCase = restoreUseCase;
        this.restoreStatusUseCase = restoreStatusUseCase;
        this.downloadUseCase = downloadUseCase;
    }

    public static AppContext create(CliOptions options) {
        ToolConfig config = new ConfigLoader().load(options.configPath());
        RemoteStorage remoteStorage = S3RemoteStorageFactory.create(config.bucket(), config.region());
        return create(config, remoteStorage, options);
    }

    static AppContext create(CliOptions options, URI endpointOverride) {
        ToolConfig config = new ConfigLoader().load(options.configPath());
        RemoteStorage remoteStorage = S3RemoteStorageFactory.create(config.bucket(), config.region(), endpointOverride);
        return create(config, remoteStorage, options);
    }

    /** Resolves the scan thread count: --scan-threads beats defaults.scanThreads beats the built-in default. */
    static int resolveScanThreads(OptionalInt flag, OptionalInt configured) {
        int scanThreads = flag.orElse(configured.orElse(NioLocalVolume.DEFAULT_SCAN_THREADS));
        // Config values are range-checked by ConfigLoader, so only the flag can land here out of range.
        if (scanThreads < NioLocalVolume.MIN_SCAN_THREADS || scanThreads > NioLocalVolume.MAX_SCAN_THREADS) {
            throw new ConfigException("--scan-threads must be between " + NioLocalVolume.MIN_SCAN_THREADS
                    + " and " + NioLocalVolume.MAX_SCAN_THREADS + ": " + scanThreads);
        }
        return scanThreads;
    }

    private static AppContext create(ToolConfig config, RemoteStorage backingStorage, CliOptions options) {
        Log log = options.log();
        RemoteStorage remoteStorage = new RetryingRemoteStorage(backingStorage, log);
        LocalVolume localVolume = new NioLocalVolume(log, resolveScanThreads(options.scanThreads(), config.scanThreads()));
        ProgressReporter progressReporter = new ConsoleProgressReporter(log, Clock.systemDefaultZone());

        return new S3AppContext(
                config,
                new SyncUseCase(localVolume, remoteStorage, progressReporter),
                new UploadUseCase(remoteStorage, progressReporter),
                new DeleteUseCase(remoteStorage),
                new ListUseCase(remoteStorage),
                new RestoreUseCase(remoteStorage),
                new RestoreStatusUseCase(remoteStorage),
                new DownloadUseCase(remoteStorage, progressReporter));
    }

    @Override
    public ToolConfig config() {
        return config;
    }

    @Override
    public SyncUseCase syncUseCase() {
        return syncUseCase;
    }

    @Override
    public UploadUseCase uploadUseCase() {
        return uploadUseCase;
    }

    @Override
    public DeleteUseCase deleteUseCase() {
        return deleteUseCase;
    }

    @Override
    public ListUseCase listUseCase() {
        return listUseCase;
    }

    @Override
    public RestoreUseCase restoreUseCase() {
        return restoreUseCase;
    }

    @Override
    public RestoreStatusUseCase restoreStatusUseCase() {
        return restoreStatusUseCase;
    }

    @Override
    public DownloadUseCase downloadUseCase() {
        return downloadUseCase;
    }
}
