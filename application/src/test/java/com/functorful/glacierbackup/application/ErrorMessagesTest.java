package com.functorful.glacierbackup.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class ErrorMessagesTest {

    private static final String WRAPPER_MESSAGE = "Failed to put object: media/a.bin";
    private static final String ROOT_CAUSE_MESSAGE = "Last write attempt timed out";

    @Test
    void describesExceptionWithoutCauseByItsMessageAlone() {
        RuntimeException error = new RuntimeException(WRAPPER_MESSAGE);

        assertThat(ErrorMessages.describe(error)).isEqualTo(WRAPPER_MESSAGE);
    }

    @Test
    void appendsRootCauseClassAndMessageThroughIntermediateWrappers() {
        RuntimeException error = new RuntimeException(
                WRAPPER_MESSAGE, new CompletionException(new IOException(ROOT_CAUSE_MESSAGE)));

        assertThat(ErrorMessages.describe(error))
                .isEqualTo(WRAPPER_MESSAGE + " (caused by IOException: " + ROOT_CAUSE_MESSAGE + ")");
    }

    @Test
    void fallsBackToClassSimpleNameWhenMessagesAreNull() {
        RuntimeException error = new RuntimeException((String) null, new IOException((String) null));

        assertThat(ErrorMessages.describe(error)).isEqualTo("RuntimeException (caused by IOException: IOException)");
    }
}
