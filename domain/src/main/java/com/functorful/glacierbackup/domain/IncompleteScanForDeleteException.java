package com.functorful.glacierbackup.domain;

/**
 * Thrown by {@link SyncPlanner#plan} when a policy requests remote deletes but the
 * local scan it is planning from is known to be incomplete (it skipped one or more
 * vanished directories). Planning deletes from such a scan risks reading a
 * transiently-unlistable directory's still-real files as remote orphans, so this is
 * refused outright rather than merely warned about.
 */
public class IncompleteScanForDeleteException extends RuntimeException {

    public IncompleteScanForDeleteException(int vanishedDirectoryCount) {
        super("Cannot plan remote deletes: scan skipped %d vanished directories".formatted(vanishedDirectoryCount));
    }
}
