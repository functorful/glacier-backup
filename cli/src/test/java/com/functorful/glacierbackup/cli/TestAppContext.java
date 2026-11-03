package com.functorful.glacierbackup.cli;

import com.functorful.glacierbackup.application.DeleteUseCase;
import com.functorful.glacierbackup.application.DownloadUseCase;
import com.functorful.glacierbackup.application.ListUseCase;
import com.functorful.glacierbackup.application.RestoreStatusUseCase;
import com.functorful.glacierbackup.application.RestoreUseCase;
import com.functorful.glacierbackup.application.SyncUseCase;
import com.functorful.glacierbackup.application.UploadUseCase;
import com.functorful.glacierbackup.application.port.LocalVolume;
import com.functorful.glacierbackup.application.port.ProgressReporter;
import com.functorful.glacierbackup.application.port.RemoteStorage;
import com.functorful.glacierbackup.application.testing.FakeLocalVolume;
import com.functorful.glacierbackup.cli.config.ToolConfig;

/**
 * Real use cases wired to fake ports, so command tests observe genuine use-case behavior
 * without needing to stub the final use-case classes themselves.
 */
final class TestAppContext implements AppContext {

    private final ToolConfig config;
    private final SyncUseCase syncUseCase;
    private final UploadUseCase uploadUseCase;
    private final DeleteUseCase deleteUseCase;
    private final ListUseCase listUseCase;
    private final RestoreUseCase restoreUseCase;
    private final RestoreStatusUseCase restoreStatusUseCase;
    private final DownloadUseCase downloadUseCase;

    TestAppContext(ToolConfig config, RemoteStorage remoteStorage) {
        this(config, remoteStorage, FakeLocalVolume.scanningRealDirectory(), ProgressReporter.noOp());
    }

    TestAppContext(ToolConfig config, RemoteStorage remoteStorage, LocalVolume localVolume, ProgressReporter progressReporter) {
        this.config = config;
        this.syncUseCase = new SyncUseCase(localVolume, remoteStorage, progressReporter);
        this.uploadUseCase = new UploadUseCase(remoteStorage, progressReporter);
        this.deleteUseCase = new DeleteUseCase(remoteStorage);
        this.listUseCase = new ListUseCase(remoteStorage);
        this.restoreUseCase = new RestoreUseCase(remoteStorage);
        this.restoreStatusUseCase = new RestoreStatusUseCase(remoteStorage);
        this.downloadUseCase = new DownloadUseCase(remoteStorage, progressReporter);
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
