package com.functorful.glacierbackup.domain;

import java.time.Instant;

public sealed interface RestoreState permits RestoreState.NotStarted, RestoreState.InProgress, RestoreState.Ready {

    static RestoreState notStarted() {
        return new NotStarted();
    }

    static RestoreState inProgress() {
        return new InProgress();
    }

    static RestoreState readyUntil(Instant until) {
        return new Ready(until);
    }

    record NotStarted() implements RestoreState {}

    record InProgress() implements RestoreState {}

    record Ready(Instant until) implements RestoreState {}
}
