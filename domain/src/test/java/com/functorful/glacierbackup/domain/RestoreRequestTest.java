package com.functorful.glacierbackup.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

class RestoreRequestTest {

    private static final String PATH_VALUE = "photos/2024/img.jpg";
    private static final int VALID_DAYS = 7;
    private static final int ZERO_DAYS = 0;

    @Test
    void roundTripsFieldsForValidRequest() {
        RelativePath path = RelativePath.of(PATH_VALUE);

        RestoreRequest request = RestoreRequest.of(path, StorageClass.GLACIER, RestoreTier.BULK, VALID_DAYS);

        assertThat(request.path()).isEqualTo(path);
        assertThat(request.tier()).isEqualTo(RestoreTier.BULK);
        assertThat(request.days()).isEqualTo(VALID_DAYS);
    }

    @Test
    void rejectsStorageClassThatDoesNotRequireRestore() {
        RelativePath path = RelativePath.of(PATH_VALUE);

        assertThatExceptionOfType(RestoreNotApplicableException.class)
                .isThrownBy(() -> RestoreRequest.of(path, StorageClass.STANDARD, RestoreTier.BULK, VALID_DAYS));
    }

    @Test
    void rejectsExpeditedTierForDeepArchive() {
        RelativePath path = RelativePath.of(PATH_VALUE);

        assertThatExceptionOfType(UnsupportedRestoreTierException.class)
                .isThrownBy(() -> RestoreRequest.of(
                        path, StorageClass.DEEP_ARCHIVE, RestoreTier.EXPEDITED, VALID_DAYS));
    }

    @Test
    void allowsExpeditedTierForGlacier() {
        RelativePath path = RelativePath.of(PATH_VALUE);

        RestoreRequest request = RestoreRequest.of(path, StorageClass.GLACIER, RestoreTier.EXPEDITED, VALID_DAYS);

        assertThat(request.tier()).isEqualTo(RestoreTier.EXPEDITED);
    }

    @Test
    void rejectsNonPositiveDays() {
        RelativePath path = RelativePath.of(PATH_VALUE);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> RestoreRequest.of(path, StorageClass.GLACIER, RestoreTier.BULK, ZERO_DAYS));
    }
}
