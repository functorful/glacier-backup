package com.functorful.glacierbackup.infrastructure.s3;

import com.functorful.glacierbackup.domain.RestoreState;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RestoreHeaderParser {

    static final String ONGOING_TRUE_HEADER = "ongoing-request=\"true\"";
    static final String ONGOING_FALSE_HEADER = "ongoing-request=\"false\"";
    private static final Pattern EXPIRY_DATE_PATTERN = Pattern.compile("expiry-date=\"([^\"]+)\"");

    private RestoreHeaderParser() {}

    public static RestoreState parse(Optional<String> restoreHeader) {
        return restoreHeader.map(RestoreHeaderParser::parseHeader).orElseGet(RestoreState::notStarted);
    }

    private static RestoreState parseHeader(String header) {
        if (header.contains(ONGOING_TRUE_HEADER)) {
            return RestoreState.inProgress();
        }
        if (header.contains(ONGOING_FALSE_HEADER)) {
            return parseExpiryDate(header).<RestoreState>map(RestoreState::readyUntil).orElseGet(RestoreState::notStarted);
        }
        return RestoreState.notStarted();
    }

    private static Optional<Instant> parseExpiryDate(String header) {
        Matcher matcher = EXPIRY_DATE_PATTERN.matcher(header);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ZonedDateTime.parse(matcher.group(1), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
