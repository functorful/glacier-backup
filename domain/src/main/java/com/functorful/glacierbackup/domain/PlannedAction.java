package com.functorful.glacierbackup.domain;

public sealed interface PlannedAction permits PlannedAction.Upload, PlannedAction.DeleteRemote, PlannedAction.Skip {

    record Upload(LocalFile file, StorageClass storageClass, String reason) implements PlannedAction {}

    record DeleteRemote(RelativePath path, String reason) implements PlannedAction {}

    record Skip(RelativePath path, String reason) implements PlannedAction {}
}
