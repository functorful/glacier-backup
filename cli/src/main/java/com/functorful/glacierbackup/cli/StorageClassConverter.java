package com.functorful.glacierbackup.cli;

import com.functorful.glacierbackup.domain.StorageClass;
import java.util.Locale;
import picocli.CommandLine.ITypeConverter;

final class StorageClassConverter implements ITypeConverter<StorageClass> {

    @Override
    public StorageClass convert(String value) {
        return StorageClass.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
