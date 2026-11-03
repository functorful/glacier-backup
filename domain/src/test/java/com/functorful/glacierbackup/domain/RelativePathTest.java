package com.functorful.glacierbackup.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

class RelativePathTest {

    private static final String NESTED_RAW = "photos\\2024\\img.jpg";
    private static final String NESTED_NORMALIZED = "photos/2024/img.jpg";
    private static final String DOT_SLASH_RAW = "./a/b";
    private static final String DOT_SLASH_NORMALIZED = "a/b";
    private static final String ABSOLUTE_RAW = "/etc/passwd";
    private static final String BLANK_RAW = "  ";
    private static final String PARENT_TRAVERSAL_RAW = "a/../b";
    private static final String FIRST_SORTED_PATH = "a/b";
    private static final String SECOND_SORTED_PATH = "a/c";

    @Test
    void normalizesBackslashesAndLeadingDotSlash() {
        assertThat(RelativePath.of(NESTED_RAW).value()).isEqualTo(NESTED_NORMALIZED);
        assertThat(RelativePath.of(DOT_SLASH_RAW).value()).isEqualTo(DOT_SLASH_NORMALIZED);
    }

    @Test
    void rejectsAbsoluteBlankAndParentTraversal() {
        assertThatExceptionOfType(InvalidRelativePathException.class)
                .isThrownBy(() -> RelativePath.of(ABSOLUTE_RAW));
        assertThatExceptionOfType(InvalidRelativePathException.class)
                .isThrownBy(() -> RelativePath.of(BLANK_RAW));
        assertThatExceptionOfType(InvalidRelativePathException.class)
                .isThrownBy(() -> RelativePath.of(PARENT_TRAVERSAL_RAW));
    }

    @Test
    void rejectsNullRaw() {
        assertThatExceptionOfType(InvalidRelativePathException.class)
                .isThrownBy(() -> RelativePath.of(null));
    }

    @Test
    void sortsLexicographicallyByValue() {
        RelativePath first = RelativePath.of(FIRST_SORTED_PATH);
        RelativePath second = RelativePath.of(SECOND_SORTED_PATH);

        assertThat(first.compareTo(second))
                .as("'%s' must sort before '%s'".formatted(FIRST_SORTED_PATH, SECOND_SORTED_PATH))
                .isNegative();
        assertThat(second.compareTo(first))
                .as("'%s' must sort after '%s'".formatted(SECOND_SORTED_PATH, FIRST_SORTED_PATH))
                .isPositive();
    }
}
