# Installation

`glacier-backup` is distributed as native executables (no Java required) and
as a JVM zip. On a UniFi UNAS Pro, use the make-based installer in the
[UNAS Pro guide](unas-pro.md) instead; it does the steps on this page for you.

## Release assets

Every release `vX.Y.Z` on the
[releases page](https://github.com/functorful/glacier-backup/releases)
contains:

| Asset | For |
|---|---|
| `glacier-backup-linux-arm64` | Linux on ARM64 (aarch64), including the UNAS Pro |
| `glacier-backup-linux-amd64` | Linux on x86-64 |
| `glacier-backup-macos-arm64` | macOS on Apple silicon |
| `glacier-backup-X.Y.Z.zip` | JVM distribution for any OS with Java 21 |
| `SHA256SUMS` | SHA-256 checksums of all of the above |

The Linux binaries are built on Oracle Linux 8, so they need glibc 2.28 or
newer (any mainstream distribution from the last several years). They are
glibc builds and will not run on musl-based systems such as Alpine.

Download URLs:

```text
https://github.com/functorful/glacier-backup/releases/download/vX.Y.Z/<asset>
https://github.com/functorful/glacier-backup/releases/latest/download/<asset>
```

The `latest` form works for the three native binaries and `SHA256SUMS`, whose
names do not change. The JVM zip contains the version in its name, so use the
versioned URL for it.

## Native binary on Linux

The examples use `linux-amd64`; substitute `linux-arm64` where appropriate.

```bash
ASSET=glacier-backup-linux-amd64
BASE=https://github.com/functorful/glacier-backup/releases/latest/download

curl -fLO "$BASE/$ASSET"
curl -fLO "$BASE/SHA256SUMS"

# Verify: checks only the files listed in SHA256SUMS that exist locally
sha256sum -c --ignore-missing SHA256SUMS
# glacier-backup-linux-amd64: OK
```

Do not continue if the check prints `FAILED`.

Install it (see [the UTF-8 wrapper](#the-utf-8-locale-and-the-wrapper) below for
why there are two files):

```bash
sudo install -m 755 "$ASSET" /usr/local/bin/glacier-backup.bin

sudo tee /usr/local/bin/glacier-backup >/dev/null <<'EOF'
#!/bin/sh
export LANG=C.UTF-8 LC_ALL=C.UTF-8
exec /usr/local/bin/glacier-backup.bin -Xmx"${GLACIER_BACKUP_XMX:-1g}" "$@"
EOF
sudo chmod 755 /usr/local/bin/glacier-backup

glacier-backup --version
```

Without root, install into `~/.local/bin` instead and adjust the path inside
the wrapper.

## Native binary on macOS (Apple silicon)

```bash
ASSET=glacier-backup-macos-arm64
BASE=https://github.com/functorful/glacier-backup/releases/latest/download

curl -fLO "$BASE/$ASSET"
curl -fLO "$BASE/SHA256SUMS"

# macOS has shasum rather than sha256sum
grep " $ASSET\$" SHA256SUMS | shasum -a 256 -c -
# glacier-backup-macos-arm64: OK

chmod +x "$ASSET"
sudo mkdir -p /usr/local/bin
sudo install -m 755 "$ASSET" /usr/local/bin/glacier-backup
glacier-backup --version
```

**Gatekeeper.** A file downloaded with a web browser is flagged with the
quarantine attribute, and macOS may refuse to run it ("cannot be opened because
the developer cannot be verified"). Downloads made with `curl` normally are
not flagged. If yours is, remove the flag from the file you verified:

```bash
xattr -d com.apple.quarantine /usr/local/bin/glacier-backup
```

macOS terminals run in UTF-8 by default, so the wrapper is rarely needed for
interactive use. For launchd jobs, set the locale in the plist as shown in
[Usage: macOS](usage.md#macos-launchd).

## The UTF-8 locale and the wrapper

The binary maps file names to S3 keys using the process's locale. Without a
UTF-8 locale (typical for non-interactive SSH sessions, cron, and systemd,
which have no `LANG`), the JVM treats the file-name charset as ASCII, and every
file with an accented or otherwise non-ASCII name is reported as *unmappable*
and skipped.

Always run it with:

```bash
export LANG=C.UTF-8 LC_ALL=C.UTF-8     # use en_US.UTF-8 on macOS if C.UTF-8 is unavailable
```

The easiest way to guarantee that is a small wrapper, which is what the
UNAS installer (`make deploy-unas`) sets up. It installs the real executable
as `/usr/local/bin/glacier-backup.bin` and a shell script as
`/usr/local/bin/glacier-backup` that:

1. forces `LANG` and `LC_ALL` to `C.UTF-8`, and
2. starts the binary with `-Xmx1g`, overridable with the `GLACIER_BACKUP_XMX`
   environment variable. This caps the heap so that a runaway scan on a
   memory-constrained machine fails with a clean Java `OutOfMemoryError`
   instead of being killed by the kernel's OOM killer (exit code `137`). Keep
   the value well below the machine's free RAM.

## JVM distribution

For platforms without a native build (Intel Macs, Windows, musl Linux) or if
you prefer a JVM. It needs **Java 21 or newer** on `PATH` (or `JAVA_HOME` set).

```bash
VERSION=1.2.3    # the release version without the leading "v"
BASE=https://github.com/functorful/glacier-backup/releases/download/v$VERSION

curl -fLO "$BASE/glacier-backup-$VERSION.zip"
curl -fLO "$BASE/SHA256SUMS"
sha256sum -c --ignore-missing SHA256SUMS        # macOS: see the shasum form above

unzip -q "glacier-backup-$VERSION.zip" -d ~/opt
~/opt/glacier-backup-$VERSION/bin/glacier-backup --version
```

The archive contains `glacier-backup-X.Y.Z/bin/glacier-backup` (a launcher
script) and the jars under `lib/`. Put `bin/` on your `PATH` or symlink the
launcher. Set the UTF-8 locale as above. Extra JVM flags, for example a heap
limit, go in the standard `JAVA_OPTS` variable:

```bash
JAVA_OPTS="-Xmx2g" ~/opt/glacier-backup-$VERSION/bin/glacier-backup sync photos
```

## Building from source

Requirements: a **JDK 21**, and for the native executable **GraalVM 21** with
`native-image` as the active JDK (the Gradle toolchain pins Java 21 for
compilation, but GraalVM is taken from your environment). Use a UTF-8 locale
for both building and running.

```bash
git clone https://github.com/functorful/glacier-backup.git
cd glacier-backup
export LANG=C.UTF-8 LC_ALL=C.UTF-8

./gradlew build                      # compile + unit tests (make build)
./gradlew :cli:nativeCompile         # native executable (make native, needs GraalVM)
./gradlew :cli:distZip               # JVM zip: cli/build/distributions/
```

Outputs:

| Task | Result |
|---|---|
| `:cli:nativeCompile` | `cli/build/native/nativeCompile/glacier-backup` |
| `:cli:distZip` | `cli/build/distributions/glacier-backup-<version>.zip` |

A native build runs only on the OS and CPU architecture it was built on. Local
builds report `glacier-backup 0.0.0-SNAPSHOT`; release builds are stamped by CI
(`-Pversion=X.Y.Z`). More detail, including tests and CI, is in
[Development](development.md).

## Verify the installation

```console
$ glacier-backup --version
glacier-backup 1.2.3
$ glacier-backup --help
```

Then continue with [Configuration](configuration.md) (config file, AWS setup)
and the first dry run in [Usage](usage.md#first-sync).
