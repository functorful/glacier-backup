package com.functorful.glacierbackup.infrastructure.s3;

import static org.assertj.core.api.Assertions.assertThat;

import com.functorful.glacierbackup.domain.RestoreState;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RestoreHeaderParserTest {

    private static final String EXPIRY_DATE_HEADER_VALUE = "Sun, 23 Dec 2012 00:00:00 GMT";
    private static final Instant EXPIRY_INSTANT = Instant.parse("2012-12-23T00:00:00Z");
    private static final String GARBAGE_HEADER = "not-a-restore-header";

    @Test
    void absentHeaderYieldsNotStarted() {
        RestoreState state = RestoreHeaderParser.parse(Optional.empty());

        assertThat(state).isEqualTo(RestoreState.notStarted());
    }

    @Test
    void ongoingTrueHeaderYieldsInProgress() {
        RestoreState state = RestoreHeaderParser.parse(Optional.of(RestoreHeaderParser.ONGOING_TRUE_HEADER));

        assertThat(state).isEqualTo(RestoreState.inProgress());
    }

    @Test
    void ongoingFalseHeaderWithExpiryDateYieldsReadyUntilExpiry() {
        String header = RestoreHeaderParser.ONGOING_FALSE_HEADER + ", expiry-date=\"" + EXPIRY_DATE_HEADER_VALUE + "\"";

        RestoreState state = RestoreHeaderParser.parse(Optional.of(header));

        assertThat(state).isEqualTo(RestoreState.readyUntil(EXPIRY_INSTANT));
    }

    @Test
    void garbageHeaderYieldsNotStarted() {
        RestoreState state = RestoreHeaderParser.parse(Optional.of(GARBAGE_HEADER));

        assertThat(state).isEqualTo(RestoreState.notStarted());
    }
}
