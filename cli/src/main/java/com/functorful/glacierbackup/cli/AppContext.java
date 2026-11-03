package com.functorful.glacierbackup.cli;

import com.functorful.glacierbackup.application.DeleteUseCase;
import com.functorful.glacierbackup.application.DownloadUseCase;
import com.functorful.glacierbackup.application.ListUseCase;
import com.functorful.glacierbackup.application.RestoreStatusUseCase;
import com.functorful.glacierbackup.application.RestoreUseCase;
import com.functorful.glacierbackup.application.SyncUseCase;
import com.functorful.glacierbackup.application.UploadUseCase;
import com.functorful.glacierbackup.cli.config.ToolConfig;

public interface AppContext {

    ToolConfig config();

    SyncUseCase syncUseCase();

    UploadUseCase uploadUseCase();

    DeleteUseCase deleteUseCase();

    ListUseCase listUseCase();

    RestoreUseCase restoreUseCase();

    RestoreStatusUseCase restoreStatusUseCase();

    DownloadUseCase downloadUseCase();
}
