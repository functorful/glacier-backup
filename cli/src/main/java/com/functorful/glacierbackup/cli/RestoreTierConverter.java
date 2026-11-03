package com.functorful.glacierbackup.cli;

import com.functorful.glacierbackup.domain.RestoreTier;
import java.util.Locale;
import picocli.CommandLine.ITypeConverter;

final class RestoreTierConverter implements ITypeConverter<RestoreTier> {

    @Override
    public RestoreTier convert(String value) {
        return RestoreTier.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
