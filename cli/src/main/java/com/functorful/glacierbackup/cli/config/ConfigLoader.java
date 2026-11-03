package com.functorful.glacierbackup.cli.config;

import com.functorful.glacierbackup.domain.StorageClass;
import com.functorful.glacierbackup.infrastructure.fs.NioLocalVolume;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class ConfigLoader {

    private static final String XDG_CONFIG_HOME_ENV = "XDG_CONFIG_HOME";
    private static final String USER_HOME_PROPERTY = "user.home";
    private static final String DOT_CONFIG_DIR = ".config";
    private static final String APP_CONFIG_DIR = "glacier-backup";
    private static final String CONFIG_FILE_NAME = "config.yml";
    private static final Path ETC_CONFIG_FILE = Path.of("/etc", APP_CONFIG_DIR, CONFIG_FILE_NAME);

    private static final String BUCKET_KEY = "bucket";
    private static final String REGION_KEY = "region";
    private static final String DEFAULTS_KEY = "defaults";
    private static final String STORAGE_CLASS_KEY = "storageClass";
    static final String SCAN_THREADS_KEY = "scanThreads";
    private static final String BACKUP_SETS_KEY = "backupSets";
    private static final String LOCAL_KEY = "local";
    private static final String PREFIX_KEY = "prefix";
    private static final String INCLUDE_KEY = "include";
    private static final String EXCLUDE_KEY = "exclude";

    private final Path userConfigFile;
    private final Path etcConfigFile;

    public ConfigLoader() {
        this(defaultUserConfigFile(), ETC_CONFIG_FILE);
    }

    ConfigLoader(Path userConfigFile, Path etcConfigFile) {
        this.userConfigFile = userConfigFile;
        this.etcConfigFile = etcConfigFile;
    }

    public ToolConfig load(Optional<Path> explicitPath) {
        Path configFile = resolveConfigFile(explicitPath);
        Map<String, Object> root = parse(configFile);
        return toToolConfig(root);
    }

    private Path resolveConfigFile(Optional<Path> explicitPath) {
        if (explicitPath.isPresent()) {
            Path path = explicitPath.get();
            if (!Files.isRegularFile(path)) {
                throw new ConfigException("Config file not found: " + path);
            }
            return path;
        }
        List<Path> searched = List.of(userConfigFile, etcConfigFile);
        return searched.stream()
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new ConfigException("No config file found. Searched: "
                        + searched.stream().map(Path::toString).reduce((a, b) -> a + ", " + b).orElse("")));
    }

    private static Map<String, Object> parse(Path configFile) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        try (InputStream in = Files.newInputStream(configFile)) {
            Object loaded = yaml.load(in);
            if (!(loaded instanceof Map<?, ?> map)) {
                throw new ConfigException("Config file is not a YAML mapping: " + configFile);
            }
            return asStringKeyedMap(map);
        } catch (IOException e) {
            throw new ConfigException("Failed to read config file: " + configFile, e);
        }
    }

    private static ToolConfig toToolConfig(Map<String, Object> root) {
        String bucket = requireString(root, BUCKET_KEY);
        String region = requireString(root, REGION_KEY);
        Map<String, Object> defaults = asStringKeyedMap(root.getOrDefault(DEFAULTS_KEY, Map.of()));
        StorageClass defaultStorageClass = readStorageClass(defaults);
        OptionalInt scanThreads = readScanThreads(defaults);
        Map<String, Object> backupSetsRaw = asStringKeyedMap(root.getOrDefault(BACKUP_SETS_KEY, Map.of()));
        Map<String, BackupSet> backupSets = backupSetsRaw.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> toBackupSet(entry.getKey(), asStringKeyedMap(entry.getValue())),
                        (a, b) -> a, LinkedHashMap::new));
        return new ToolConfig(bucket, region, defaultStorageClass, scanThreads, backupSets);
    }

    private static StorageClass readStorageClass(Map<String, Object> defaults) {
        Object value = defaults.get(STORAGE_CLASS_KEY);
        if (value == null) {
            return StorageClass.DEEP_ARCHIVE;
        }
        try {
            return StorageClass.valueOf(value.toString());
        } catch (IllegalArgumentException e) {
            throw new ConfigException("Unknown storage class at '" + DEFAULTS_KEY + "." + STORAGE_CLASS_KEY + "': " + value);
        }
    }

    private static OptionalInt readScanThreads(Map<String, Object> defaults) {
        Object value = defaults.get(SCAN_THREADS_KEY);
        if (value == null) {
            return OptionalInt.empty();
        }
        int scanThreads;
        try {
            scanThreads = Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            throw new ConfigException(
                    "Value at '" + DEFAULTS_KEY + "." + SCAN_THREADS_KEY + "' is not an integer: " + value);
        }
        if (scanThreads < NioLocalVolume.MIN_SCAN_THREADS || scanThreads > NioLocalVolume.MAX_SCAN_THREADS) {
            throw new ConfigException("Value at '" + DEFAULTS_KEY + "." + SCAN_THREADS_KEY + "' must be between "
                    + NioLocalVolume.MIN_SCAN_THREADS + " and " + NioLocalVolume.MAX_SCAN_THREADS + ": " + scanThreads);
        }
        return OptionalInt.of(scanThreads);
    }

    private static BackupSet toBackupSet(String name, Map<String, Object> setMap) {
        String local = requireString(setMap, BACKUP_SETS_KEY + "." + name + "." + LOCAL_KEY, LOCAL_KEY);
        String prefix = requireString(setMap, BACKUP_SETS_KEY + "." + name + "." + PREFIX_KEY, PREFIX_KEY);
        List<String> include = asStringList(setMap.get(INCLUDE_KEY));
        List<String> exclude = asStringList(setMap.get(EXCLUDE_KEY));
        return new BackupSet(Path.of(local), prefix, include, exclude);
    }

    private static String requireString(Map<String, Object> map, String key) {
        return requireString(map, key, key);
    }

    private static String requireString(Map<String, Object> map, String describedKey, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new ConfigException("Missing required config key: " + describedKey);
        }
        return value.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        return ((List<Object>) value).stream().map(Object::toString).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringKeyedMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static Path defaultUserConfigFile() {
        String xdgConfigHome = System.getenv(XDG_CONFIG_HOME_ENV);
        Path base = (xdgConfigHome != null && !xdgConfigHome.isBlank())
                ? Path.of(xdgConfigHome)
                : Path.of(System.getProperty(USER_HOME_PROPERTY), DOT_CONFIG_DIR);
        return base.resolve(APP_CONFIG_DIR).resolve(CONFIG_FILE_NAME);
    }
}
