# Usage

Command reference and walkthroughs for everyday operation. It assumes the
tool is [installed](installation.md) and a [config file](configuration.md)
exists.

- [Global options](#global-options)
- [Output format](#output-format)
- [First sync](#first-sync)
- [sync](#sync)
- [up](#up), [ls](#ls), [rm](#rm)
- [Restoring data](#restoring-data) (`restore`, `restore-status`, `get`)
- [Exit codes](#exit-codes)
- [File names and encodings](#file-names-and-encodings)
- [Upload behavior](#upload-behavior)
- [Scheduling](#scheduling): [cron](#cron), [systemd](#systemd), [macOS launchd](#macos-launchd)

## Global options

```text
glacier-backup [--config <path>] [-v | --verbose] [--scan-threads <n>] [-h | --help] [-V | --version] <command> [<args>]
```

Global options go **before** the command.

| Option | Default | Description |
|---|---|---|
| `--config <path>` | search order | Use this config file instead of the [default locations](configuration.md#where-the-config-file-is-found). Must exist. |
| `-v`, `--verbose` | off | Detailed progress: scan, remote listing and planning phases with counts and timings, per-file transfer starts, a scan tick every 10,000 files (all as `DEBUG` lines on stderr), and per-file `UPLOAD`/`DELETE` plan lines for `sync --dry-run`. |
| `--scan-threads <n>` | `defaults.scanThreads`, else `32` | Local-scan worker threads, `1` to `128`. |
| `-h`, `--help` | | Print usage. |
| `-V`, `--version` | | Print the version, for example `glacier-backup 1.2.3`. |

Running with no command prints usage to stderr and exits `2`.

## Output format

Every line the tool prints starts with a local-time timestamp and a level:

```text
[2026-08-27T14:03:21] INFO photos/2024/IMG_0001.jpg ... done
[2026-08-27T14:03:22] ERROR photos/2024/IMG_0002.jpg FAILED (simulated)
```

| Level | Stream | Used for |
|---|---|---|
| `INFO` | stdout | Listings, dry-run summaries, per-file `... done` lines |
| `WARN` | stderr | Warnings, for example unmappable file names or retries |
| `ERROR` | stderr | Per-item failures and errors that abort a run |
| `DEBUG` | stderr | Verbose progress; only with `-v` |

Output produced by the argument parser itself (`--help`, `--version`, usage
errors) is not prefixed. The scheduled runner appends both streams to one log
file, and `grep FAILED` or `grep ERROR` finds problems.

## First sync

Always start with a dry run. It scans the local directory, lists the remote
prefix, prints the plan, and changes nothing:

```console
$ glacier-backup sync photos --dry-run
[2026-08-27T14:03:21] INFO 2 to upload: new file, not present remotely
[2026-08-27T14:03:21] INFO 1 to upload: local and remote sizes differ
[2026-08-27T14:03:21] INFO 3 to upload (241663488 bytes), 0 to delete, 12 skipped
```

Add `-v` to see one line per planned file (on a big backup that is a line per
file, so redirect or pipe it):

```console
$ glacier-backup -v sync photos --dry-run | head
```

When the plan looks right, drop `--dry-run`:

```console
$ glacier-backup sync photos
[2026-08-27T14:05:02] INFO photos/2024/IMG_0001.jpg ... done
```

The first upload of a large library can take days on a home uplink. That is
fine: every run only uploads what is missing, so you can interrupt and
continue (a file that was mid-upload starts again from its beginning).

## sync

```text
glacier-backup sync <set-name> [--dry-run] [--delete] [--include <glob>]... [--exclude <glob>]... [--storage-class <class>]
glacier-backup sync <local-path> <remote-prefix> [same flags]
```

One-way sync from local to S3. The number of arguments decides the mode:

- **One argument** is a backup set name from the config. `local`, `prefix`,
  `include` and `exclude` come from the set.
  `glacier-backup sync photos`
- **Two arguments** are an ad-hoc local directory and a remote prefix; no
  config set is involved.
  `glacier-backup sync /srv/photos photos/`

A single argument that is not a known set is an error (`Unknown backup set`,
exit `2`); so is a set name combined with a prefix. The `bucket` and `region`
always come from the config file.

| Flag | Default | Description |
|---|---|---|
| `--dry-run` | off | Print the plan and counts; upload and delete nothing. |
| `--delete` | off | Also delete remote objects that have no local counterpart. See below. |
| `--include <glob>` | none | Only sync files matching this glob. Repeatable. Added to the set's own `include` list. |
| `--exclude <glob>` | none | Skip files matching this glob. Repeatable. Added to the set's own `exclude` list. |
| `--storage-class <class>` | `defaults.storageClass`, else `DEEP_ARCHIVE` | `STANDARD`, `GLACIER_IR`, `GLACIER` or `DEEP_ARCHIVE` (case-insensitive). |

Glob syntax and how include and exclude combine are in
[Configuration](configuration.md#backup-sets-and-filters). Quote globs so your
shell does not expand them:

```bash
glacier-backup sync /srv/media media/ --exclude '**/*.tmp' --exclude '*.tmp' --dry-run
glacier-backup sync documents --include '**/*.pdf' --include '*.pdf'
glacier-backup sync photos --storage-class GLACIER
```

### What counts as a change

For each local file that passes the filters, `sync` compares it with the remote
object at the same path:

| Situation | Action |
|---|---|
| No remote object | upload: "new file, not present remotely" |
| Sizes differ | upload: "local and remote sizes differ" |
| Same size, local modification time is later than the remote object's last-modified time | upload: "local file modified after remote" |
| Same size, local not newer | skip: "local and remote are in sync" |

Content is not hashed. Because the remote last-modified time is the upload
time, a file is re-uploaded only if it changed after it was last uploaded.
Re-uploading replaces the object, which on `GLACIER`/`DEEP_ARCHIVE` incurs the
[minimum storage duration](configuration.md#storage-classes-and-costs) charge
for the replaced one.

Only regular files are synced: symlinks are not followed, and empty directories
are not represented.

### `--delete` semantics and safety

Without `--delete`, sync is purely additive: files you delete locally stay in
the bucket. That is deliberate, since a local accident (a deleted folder, an
unmounted disk, ransomware) must not silently destroy your backup. **The
scheduled UNAS runner never passes `--delete`.**

With `--delete`, remote objects under the sync's prefix that have **no local
counterpart** and that pass the same include/exclude filter are deleted after
the uploads. Safeguards and caveats:

- Run `sync --dry-run -v --delete` first and read the `DELETE` lines.
- If the local scan had to skip directories that vanished mid-scan, `--delete`
  refuses to plan any deletes for that run (logs
  `refusing to plan remote deletes: scan skipped N vanished directories`,
  still uploads, exits `1`). A directory that merely moved during the scan is
  never treated as deleted.
- An unmounted or empty source directory looks exactly like "everything was
  deleted". Check that the local path is the real data before using `--delete`.
- Objects outside the filter are never deleted, and neither are files you
  excluded after uploading them.
- Deleting archive-class objects early still bills their minimum storage
  duration.

## up

```text
glacier-backup up <local-file>... <remote-prefix> [--storage-class <class>]
```

Uploads individual files. Each file is stored as `<remote-prefix>/<file name>`
(only the file name, not the local directory structure).

```console
$ glacier-backup up ./taxes-2025.pdf ./receipts.zip documents/archive/
[2026-08-27T14:10:02] INFO documents/archive/taxes-2025.pdf ... done
[2026-08-27T14:10:09] INFO documents/archive/receipts.zip ... done
```

A missing local file is reported as a failure for that item; the others still
upload.

## ls

```text
glacier-backup ls [<remote-prefix>] [-l] [--recursive]
```

Lists remote objects; with no prefix, the whole bucket. By default only the
immediate children are shown, with subdirectories collapsed to `name/`;
`--recursive` shows every key. `-l` adds size in bytes, last-modified time,
storage class and restore state:

```console
$ glacier-backup ls -l media/movies/
[2026-08-27T14:03:21] INFO 241663488  2026-08-20T01:12:45Z  DEEP_ARCHIVE  in-progress  media/movies/film.mkv
[2026-08-27T14:03:21] INFO 1048576    2026-08-20T01:12:47Z  DEEP_ARCHIVE  -            media/movies/notes.txt
```

The restore-state column is `-` (not started, or not applicable),
`in-progress`, or `ready-until <timestamp>`.

**Cost note:** `-l` makes one `HEAD` request per object in `GLACIER` or
`DEEP_ARCHIVE`. Use a narrow prefix on large listings, or omit `-l`.

## rm

```text
glacier-backup rm <remote-path>... [--recursive]
```

Without `--recursive`, each path must be the exact key of an existing object;
a prefix fails that item with `Key not found`, so you cannot wipe a folder by
accident. With `--recursive`, every object under each prefix is deleted.
On success `rm` prints nothing and exits `0`.

```bash
glacier-backup rm photos/2019/old-scan.tif
glacier-backup ls photos/2019/ --recursive     # check what is there first
glacier-backup rm photos/2019/ --recursive
```

Deleting archive-class objects early still bills the minimum storage duration.

## Restoring data

Objects in `GLACIER` and `DEEP_ARCHIVE` cannot be read directly. Getting them
back is three steps: ask AWS to thaw them, wait, download.

### 1. Find what you need

```bash
glacier-backup ls photos/2024/ --recursive
```

### 2. `restore`: request the thaw

```text
glacier-backup restore <remote-path>... [--tier bulk|standard|expedited] [--days <n>] [--recursive]
```

| Flag | Default | Description |
|---|---|---|
| `--tier` | `standard` | Retrieval speed/cost: `bulk` (cheapest, slowest), `standard`, `expedited` (fastest, `GLACIER` only; rejected for `DEEP_ARCHIVE`). Case-insensitive. |
| `--days` | `7` | How many days the restored copy stays readable before AWS drops it again. Must be at least `1`. |
| `--recursive` | off | Restore every object under each given prefix instead of one exact key. |

```bash
glacier-backup restore photos/2024/trip/IMG_0001.jpg --tier bulk --days 3
glacier-backup restore photos/2024/trip/ --recursive --tier standard --days 7
```

Objects in `STANDARD` or `GLACIER_IR` do not need a restore and are reported
as failures if you try. `restore` prints nothing on success. The
tier/class combinations are in the
[legality matrix](domain-model.md#tier-x-storage-class-legality-matrix).
Typical AWS timings and the cost trade-off are in
[Storage classes and costs](configuration.md#storage-classes-and-costs).

### 3. `restore-status`: wait

```text
glacier-backup restore-status [<remote-prefix>]
```

```console
$ glacier-backup restore-status photos/2024/trip/
[2026-08-27T20:15:01] INFO photos/2024/trip/IMG_0001.jpg  DEEP_ARCHIVE  in-progress
[2026-08-27T20:15:01] INFO photos/2024/trip/IMG_0002.jpg  DEEP_ARCHIVE  -
```

The last column is `in-progress`, `-` (no restore requested, or the restored
copy has expired) or `ready-until <timestamp>` once the file can be
downloaded. Objects that do not require a restore are omitted. Like `ls -l`,
it issues one `HEAD` request per archived object, so keep the prefix narrow.
Re-run it periodically; `bulk` restores from Deep Archive can take up to about
two days.

### 4. `get`: download

```text
glacier-backup get <remote-path>... <local-dir> [--recursive]
```

```console
$ glacier-backup get photos/2024/trip/IMG_0001.jpg /srv/restored
[2026-08-29T07:41:12] INFO photos/2024/trip/IMG_0001.jpg ... done
$ glacier-backup get photos/2024/trip/ /srv/restored --recursive
```

Files are written to `<local-dir>/<full-remote-key>`, so the example above
creates `/srv/restored/photos/2024/trip/IMG_0001.jpg`. The destination
directories are created as needed. For archived objects, `get` checks the
restore state itself and fails that item with a pointer to `restore-status` if
it is not ready, so trying early is harmless. Objects in `STANDARD` and
`GLACIER_IR` can be fetched immediately.

Download before `ready-until` passes. After that the temporary copy is gone and
you must restore again. Remember that downloading to your home connection
incurs AWS data-transfer-out charges.

Restores can run from any machine that has the binary, a config pointing at the
same bucket, and credentials with `s3:GetObject` and `s3:RestoreObject`; it
does not have to be the machine that did the backup.

## Exit codes

| Code | Meaning |
|---|---|
| `0` | Success: every requested item completed. |
| `1` | Failure: the command ran but one or more items failed (printed as `ERROR` lines; one failing file never stops the rest of a batch), or the whole run aborted on an S3, local-disk or path error. |
| `2` | Usage or configuration error: bad arguments, no/invalid config, missing required key, unknown backup set, or a set name combined with a prefix. Nothing was attempted. |

The scheduled runner adds `124` (time window closed) and passes through `137`
(killed, usually out of memory); see
[the UNAS guide](unas-pro.md#exit-codes-of-the-runner).

## File names and encodings

Non-ASCII file names require a **UTF-8 locale** (`LANG=C.UTF-8 LC_ALL=C.UTF-8`;
see [the wrapper](installation.md#the-utf-8-locale-and-the-wrapper)). Under
a non-UTF-8 locale, accented names are unmappable and skipped.

A file whose on-disk bytes are not valid in the locale's charset (typically a
legacy Latin-1 name on an old share) is **backed up under a sanitized name**:
each undecodable byte sequence becomes U+FFFD (the replacement character). The
sanitized name is stable between runs, so it works as a key. Two warnings can
appear on stderr:

- `WARN backing up file with unmappable name as: <name>`: informational.
- `WARN skipping file with unmappable name: <path>`: the file was skipped,
  because no sanitized form exists or because it collides with another file.

To restore such a file, use the sanitized name from the warning. A better fix
is to rename the files to UTF-8 at the source: the repository provides
[`scripts/fix-filename-encoding.sh`](../scripts/fix-filename-encoding.sh),
which connects to the NAS over SSH. It is a dry run unless you pass `--apply`:

```bash
./scripts/fix-filename-encoding.sh /volume/<real-volume-uuid>/.srv/.unifi-drive/Photos
./scripts/fix-filename-encoding.sh --apply --from CP1252 /srv/media
```

It only renames names that are not valid UTF-8 (converting from `LATIN1` by
default, or the encoding given with `--from`), never touches valid names, and
skips conflicts. Use `UNAS_HOST` and `UNAS_USER` to choose the SSH target.
Renaming changes your source data, so read the dry-run output and have a
backup first. Paths may contain only letters, digits and `_./@+,:=#%-`.

## Upload behavior

- Large files use multipart uploads (16 MiB parts, up to 4 in parallel), tuned
  for slow home uplinks with generous timeouts.
- Each file upload is attempted up to 3 times, with waits of 10 s and 60 s
  between attempts (logged as `WARN retrying put of ...`). A file that
  disappeared after the scan is not retried.
- One failing file never aborts the others; failures are listed at the end and
  the exit code is `1`.
- The remote listing runs in parallel with the local scan.

## Scheduling

On a UniFi UNAS Pro, `make setup-unas` installs a runner script and a cron
entry for you; see [the UNAS guide](unas-pro.md). The sections below are
**examples** for other machines. Adapt paths, set names and times, and always
make sure the job runs with a UTF-8 locale and AWS credentials available.

Run `glacier-backup sync <set> --dry-run` by hand first.

### cron

A crontab entry (`crontab -e`) that syncs two sets at 02:00 on Monday,
Wednesday and Friday and appends all output to a log:

```cron
LANG=C.UTF-8
LC_ALL=C.UTF-8
0 2 * * 1,3,5  /usr/local/bin/glacier-backup sync photos >> "$HOME/glacier-backup.log" 2>&1; /usr/local/bin/glacier-backup sync documents >> "$HOME/glacier-backup.log" 2>&1
```

Cron's environment is minimal: use absolute paths, and keep credentials in
`~/.aws/credentials` rather than your interactive shell's variables. If you use
the wrapper from [Installation](installation.md#native-binary-on-linux), it sets
the locale for you.

### systemd

Two units: a one-shot service and a timer. Install as
`/etc/systemd/system/glacier-backup.service` and
`/etc/systemd/system/glacier-backup.timer`.

```ini
# glacier-backup.service
[Unit]
Description=glacier-backup sync
Wants=network-online.target
After=network-online.target

[Service]
Type=oneshot
User=backup
Environment=LANG=C.UTF-8 LC_ALL=C.UTF-8
# Stop if the run takes longer than 6 hours; the next run continues where this one left off.
TimeoutStartSec=6h
# Several sets: repeat ExecStart. A leading "-" lets later sets run even if one fails.
ExecStart=-/usr/local/bin/glacier-backup.bin sync photos
ExecStart=-/usr/local/bin/glacier-backup.bin sync documents
```

```ini
# glacier-backup.timer
[Unit]
Description=Run glacier-backup on Monday, Wednesday, Friday

[Timer]
OnCalendar=Mon,Wed,Fri 02:00
Persistent=true
RandomizedDelaySec=15min

[Install]
WantedBy=timers.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now glacier-backup.timer
systemctl list-timers glacier-backup.timer
journalctl -u glacier-backup.service -e
```

The units call the binary directly (`glacier-backup.bin`) and set the locale
in `Environment=`, so the wrapper is not required. The `User=` account needs
read access to the data, a config in `~/.config/glacier-backup/` (or pass
`--config`), and credentials in its `~/.aws/credentials`.

### macOS (launchd)

Save as `~/Library/LaunchAgents/com.example.glacier-backup.plist` (change the
label, paths and set name):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>com.example.glacier-backup</string>
  <key>ProgramArguments</key>
  <array>
    <string>/usr/local/bin/glacier-backup</string>
    <string>sync</string>
    <string>documents</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>LANG</key>
    <string>en_US.UTF-8</string>
    <key>LC_ALL</key>
    <string>en_US.UTF-8</string>
  </dict>
  <key>StartCalendarInterval</key>
  <array>
    <dict><key>Weekday</key><integer>1</integer><key>Hour</key><integer>2</integer><key>Minute</key><integer>0</integer></dict>
    <dict><key>Weekday</key><integer>3</integer><key>Hour</key><integer>2</integer><key>Minute</key><integer>0</integer></dict>
    <dict><key>Weekday</key><integer>5</integer><key>Hour</key><integer>2</integer><key>Minute</key><integer>0</integer></dict>
  </array>
  <key>StandardOutPath</key>
  <string>/tmp/glacier-backup.log</string>
  <key>StandardErrorPath</key>
  <string>/tmp/glacier-backup.log</string>
</dict>
</plist>
```

```bash
launchctl bootstrap "gui/$(id -u)" ~/Library/LaunchAgents/com.example.glacier-backup.plist
launchctl print "gui/$(id -u)/com.example.glacier-backup"
```

Credentials come from `~/.aws/credentials` (launchd does not inherit your shell
environment). A Mac that is asleep at the scheduled time runs the job when it
wakes.

### Using the UNAS runner on another Linux host

The runner script
[`scripts/glacier-backup-run.sh`](../scripts/glacier-backup-run.sh) (time
window, set rotation, log rotation) is plain POSIX `sh` and can be used
outside the UNAS, but it is written for a root-owned install: it forces
`HOME=/root`, logs to `/var/log/glacier-backup.log` and keeps its rotation
state in `/etc/glacier-backup/next-first-set`. All of that, and the binary and
config paths, can be overridden in `/etc/glacier-backup/run.env` (sourced as
shell, so use `export HOME=...` there) or in the environment; the variables are
listed in [the UNAS guide](unas-pro.md#runner-settings). Without root, calling
the binary from cron or systemd directly, as above, is simpler.
