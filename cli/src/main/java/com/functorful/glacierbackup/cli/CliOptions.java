package com.functorful.glacierbackup.cli;

import com.functorful.glacierbackup.application.port.Log;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;

/** Global CLI options resolved by the root command, handed to the app-context factory. */
public record CliOptions(Optional<Path> configPath, boolean verbose, OptionalInt scanThreads, Log log) {}
