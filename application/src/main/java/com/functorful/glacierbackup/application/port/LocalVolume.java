package com.functorful.glacierbackup.application.port;

import com.functorful.glacierbackup.domain.PathFilter;
import java.nio.file.Path;

public interface LocalVolume {

    LocalScanResult scan(Path root, PathFilter filter);
}
