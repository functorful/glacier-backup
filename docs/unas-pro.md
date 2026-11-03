# Back up a UniFi UNAS Pro to S3 Glacier Deep Archive

A complete, step-by-step guide to installing `glacier-backup` on a UniFi
UNAS Pro, scheduling it, and keeping it running. The same installer works on
other Linux boxes you can reach over SSH as root, but this page is written for
the UNAS Pro.

What you will end up with:

- a native `glacier-backup` binary on the NAS (with a wrapper that sets a UTF-8
  locale and a memory cap),
- AWS credentials and your config on the NAS,
- a scheduled runner (cron) that backs up your shares to a Deep Archive bucket
  within a time window, rotating which share goes first,
- a log at `/var/log/glacier-backup.log`.

Contents: [Prerequisites](#prerequisites) -
[1. AWS](#1-set-up-aws) - [2. Find your shares](#2-find-your-share-paths) -
[3. Config](#3-write-the-config) - [4. Deploy](#4-install-the-binary) -
[5. Setup](#5-install-credentials-config-and-schedule) -
[6. Verify](#6-verify) - [Runner](#how-the-scheduled-runner-works) -
[Logs](#logs-and-monitoring) - [Memory](#memory) -
[Firmware updates](#after-firmware-updates) - [Upgrading](#upgrading) -
[Restoring](#restoring-data) - [Uninstall](#uninstalling) -
[Troubleshooting](#troubleshooting)

## Prerequisites

**On your workstation** (Linux, macOS, or WSL): `git`, `make`, `bash`, `curl`,
`awk`, `ssh` and `scp`, and either `sha256sum` or `shasum`. The installer runs
from a clone of this repository and talks to the NAS over SSH.

**On the NAS:** SSH access as `root`. Enable SSH in the UniFi console (check
UniFi's documentation for your firmware for where the setting lives and how the
root login works). The installer needs root because it writes to
`/usr/local/bin`, `/etc` and `/root`, and installs a cron job. The UNAS Pro is
a linux/arm64 device, and the installer downloads the matching
`glacier-backup-linux-arm64` release.

**Make SSH convenient.** The installer makes many SSH connections. Use key
authentication (`ssh-copy-id root@<nas-address>`) to avoid a password prompt each
time, and add a host alias so the default `UNAS_HOST=unas` works:

```text
# ~/.ssh/config
Host unas
    HostName 192.168.1.20
    User root
```

Otherwise pass `UNAS_HOST=<address>` to every `make` command below.

**An AWS account**, with the AWS CLI on your workstation for the one-time setup.

Clone the repository (it contains the Makefile, the scripts and the example
configs):

```bash
git clone https://github.com/functorful/glacier-backup.git
cd glacier-backup
```

## 1. Set up AWS

Follow [Configuration: AWS setup](configuration.md#aws-setup). It takes about
five minutes and ends with:

- a private S3 bucket (for example `my-glacier-backup-bucket` in `eu-west-1`),
- a lifecycle rule that aborts incomplete multipart uploads (important here:
  the runner's time window can interrupt a large file),
- an IAM user `glacier-backup` with a least-privilege policy,
- an access key ID and secret access key.

Keep the access key handy for step 5.

## 2. Find your share paths

UniFi Drive shares live under a volume UUID on the NAS. List them:

```bash
ssh root@unas 'ls -d /volume/*/.srv/.unifi-drive/*'
```

Each line is one share, for example:

```text
/volume/0a1b2c3d-1111-2222-3333-444455556666/.srv/.unifi-drive/Photos
/volume/0a1b2c3d-1111-2222-3333-444455556666/.srv/.unifi-drive/Documents
/volume/0a1b2c3d-1111-2222-3333-444455556666/.srv/.unifi-drive/Media
```

The UUID is the same for all shares on the same storage pool. Decide which
shares to back up; each becomes a *backup set*.

## 3. Write the config

Start from the shipped example and edit it on your workstation:

```bash
cp examples/unas-pro/config.yml my-config.yml
```

Change three things:

1. `bucket` and `region` to match step 1.
2. Replace every `<volume-uuid>` with your real UUID. (The installer refuses to
   continue while the placeholder is still in the file.)
3. Keep one entry under `backupSets` for each share you want, and delete the
   rest.

```yaml
bucket: my-glacier-backup-bucket
region: eu-west-1

defaults:
  storageClass: DEEP_ARCHIVE
  scanThreads: 16        # the UNAS is a small machine; fewer threads use less memory

backupSets:
  photos:
    local: /volume/0a1b2c3d-1111-2222-3333-444455556666/.srv/.unifi-drive/Photos
    prefix: photos/
    exclude:
      - ".DS_Store"
      - "**/.DS_Store"
      - "@eaDir/**"
      - "**/@eaDir/**"
      - "#recycle/**"
      - "**/#recycle/**"
  documents:
    local: /volume/0a1b2c3d-1111-2222-3333-444455556666/.srv/.unifi-drive/Documents
    prefix: documents/
```

Things to know:

- **Set order matters.** The runner cycles through the sets in file order (the
  rotation is explained [below](#how-the-scheduled-runner-works)).
- **Set names:** letters, digits, `.`, `_`, `-` only, written as block-style
  keys indented by exactly two spaces. The runner finds the names with a
  simple text scan, not a YAML parser.
- **Filters:** see [glob syntax](configuration.md#glob-syntax). Remember to list
  both a bare and a `**/` form of each pattern.
- Every key is documented in [Configuration](configuration.md#key-reference).
- Prefer `DEEP_ARCHIVE` for real backups. Restores from it take up to roughly
  two days; if you want faster access, use `GLACIER` or `GLACIER_IR` and see
  [Storage classes and costs](configuration.md#storage-classes-and-costs).

## 4. Install the binary

```bash
make deploy-unas
```

This downloads the latest release's `glacier-backup-linux-arm64` and
`SHA256SUMS` into `build/release/`, verifies the checksum, and copies the
binary to the NAS. It never builds locally, since a binary built on your
workstation would not run on the NAS.

Variables:

| Variable | Default | Meaning |
|---|---|---|
| `UNAS_HOST` | `unas` | SSH host of the NAS |
| `UNAS_USER` | `root` | SSH user |
| `VERSION` | latest release | Release to install, without the leading `v` (for example `VERSION=1.2.0`) |
| `GITHUB_REPO` | `functorful/glacier-backup` | Where to download from |

What ends up on the NAS:

| Path | What |
|---|---|
| `/usr/local/bin/glacier-backup.bin` | the real native executable |
| `/usr/local/bin/glacier-backup` | a wrapper script that forces `LANG`/`LC_ALL` to `C.UTF-8` and starts the binary with `-Xmx1g` (see [Memory](#memory)) |

Both files are uploaded under temporary names and moved into place, so
upgrading while a backup is running does not fail with "text file busy".

Check it:

```bash
ssh root@unas glacier-backup --version
```

## 5. Install credentials, config and schedule

Export the access key from step 1 (it is passed to the NAS over SSH stdin and
never appears on a command line or in a process list). Typing it silently
keeps it out of your shell history:

```bash
read -rp  'AWS access key ID: '     AWS_ACCESS_KEY_ID;     export AWS_ACCESS_KEY_ID
read -rsp 'AWS secret access key: ' AWS_SECRET_ACCESS_KEY; export AWS_SECRET_ACCESS_KEY; echo
```

Then run the setup:

```bash
make setup-unas CONFIG=my-config.yml
```

or call the script directly, which is identical:

```bash
CONFIG=my-config.yml ./scripts/setup-unas.sh
```

Environment and variables:

| Variable | Required | Default | Meaning |
|---|---|---|---|
| `CONFIG` | yes | | Local config file to install on the NAS |
| `AWS_ACCESS_KEY_ID` | yes | | Written to `/root/.aws/credentials` |
| `AWS_SECRET_ACCESS_KEY` | yes | | Written to `/root/.aws/credentials` |
| `UNAS_HOST` | no | `unas` | SSH host |
| `UNAS_USER` | no | `root` | SSH user; must be root, since it installs system-wide files |
| `SCHEDULE` | no | `0 2 * * 1,3,5` | 5-field cron expression (here: 02:00 on Monday, Wednesday, Friday) |

```bash
# example: every night at 01:30
make setup-unas CONFIG=my-config.yml SCHEDULE='30 1 * * *'
```

The script checks its inputs first and stops early if the config still has the
`<volume-uuid>` placeholder, a credential variable is empty, or `SCHEDULE` is
not five cron fields. Then it:

1. checks that `/usr/local/bin/glacier-backup` exists on the NAS (run
   `make deploy-unas` first),
2. writes `/root/.aws/credentials` (directory mode 700, file mode 600),
3. writes your config to `/etc/glacier-backup/config.yml`,
4. installs the runner as `/usr/local/bin/glacier-backup-run`,
5. installs the schedule as `/etc/cron.d/glacier-backup` (or, if the NAS has no
   `/etc/cron.d`, as a line in root's crontab; an existing entry is replaced,
   never duplicated),
6. runs a smoke test: `glacier-backup sync <first set> --dry-run`, and prints
   its last lines.

It is safe to re-run at any time, for example to change the schedule or
config.

## 6. Verify

The smoke test already ran a dry run for the first set. Try the others and look
at the bucket:

```bash
ssh root@unas glacier-backup sync documents --dry-run
ssh root@unas glacier-backup ls
```

If the dry run's counts are plausible, start the first real run now instead of
waiting for the schedule. On a big library this takes much longer than an SSH
session should be tied up, so run it in the background and follow the log:

```bash
ssh root@unas 'nohup /usr/local/bin/glacier-backup-run >/dev/null 2>&1 &'
ssh root@unas tail -f /var/log/glacier-backup.log
```

(Ctrl-C stops the `tail` only.) The runner sends everything to the log file, so
the terminal stays silent. Later, check what arrived:

```bash
ssh root@unas glacier-backup ls -l photos/
```

A large first upload takes many runs. That is normal: the runner stops when its
time window closes, and each following run skips what is already uploaded.

## How the scheduled runner works

`/usr/local/bin/glacier-backup-run` (from
[`scripts/glacier-backup-run.sh`](../scripts/glacier-backup-run.sh)) is what
cron starts. On each run it:

1. **Reads its settings** from the environment and the optional env file
   `/etc/glacier-backup/run.env`.
2. **Finds the sets** to back up: the `GLACIER_BACKUP_SETS` list if given,
   otherwise the set names under `backupSets:` in
   `/etc/glacier-backup/config.yml`, in file order.
3. **Rotates its log** if `/var/log/glacier-backup.log` is over 50 MB.
4. **Opens a time window** (default 6 hours). Sets run one after another, each
   with the *remaining* window as its time budget; `timeout` stops a sync with
   `SIGTERM` when the window closes. That is safe: sync is additive and
   idempotent, and the next run simply continues.
5. **Rotates the starting set.** The file `/etc/glacier-backup/next-first-set`
   holds the name of the set that goes first on the next run. It is advanced one
   set (round-robin) *before* anything syncs, so even a run that crashes or
   exhausts the whole window on its first set hands the next run a different
   starting set. Without this, a huge first set would starve the rest every
   night. If the window closes before every set had a turn, the skipped sets
   are logged. Delete the state file to start over from the first set.
6. **Runs `glacier-backup sync <set>`** for each set (never with `--delete`),
   appending all output to the log.

The runner forces `HOME=/root` and a UTF-8 locale. It uses the same
`/etc/glacier-backup/config.yml` and `/root/.aws/credentials` that setup
installed.

### Runner settings

Override these in `/etc/glacier-backup/run.env` (it is sourced as a shell
script, so `NAME=value` lines; use `export NAME=value` for anything the
`glacier-backup` process must see) or in the environment:

| Variable | Default | Meaning |
|---|---|---|
| `GLACIER_BACKUP_SETS` | sets found in the config | Space-separated set names to run, in order |
| `GLACIER_BACKUP_CONFIG` | `/etc/glacier-backup/config.yml` | Config file to read set names from; when set explicitly it is also passed to the binary as `--config` |
| `GLACIER_BACKUP_RUN_ENV` | `/etc/glacier-backup/run.env` | Location of the env file (set in the environment, not inside the file) |
| `GLACIER_BACKUP_LOG` | `/var/log/glacier-backup.log` | Log file |
| `GLACIER_BACKUP_STATE` | `/etc/glacier-backup/next-first-set` | Rotation state file |
| `GLACIER_BACKUP_BIN` | `/usr/local/bin/glacier-backup` | The binary (the wrapper) to run |
| `GLACIER_BACKUP_WINDOW_SECONDS` | `21600` (6 h) | Total time window per run |
| `GLACIER_BACKUP_MAX_LOG_BYTES` | `52428800` (50 MB) | Rotate the log when it is bigger than this at the start of a run |
| `GLACIER_BACKUP_XMX` | `1g` | Heap cap, read by the wrapper. See [Memory](#memory) |

Example `/etc/glacier-backup/run.env`: three-hour window, only two sets, more
heap:

```sh
GLACIER_BACKUP_WINDOW_SECONDS=10800
GLACIER_BACKUP_SETS="photos documents"
GLACIER_BACKUP_XMX=2g
```

Create it on the NAS (`ssh root@unas 'cat > /etc/glacier-backup/run.env'` and
paste). The setup script never touches this file.

If the config uses unusual YAML (flow style, tabs, other indentation), the
runner cannot discover the set names; set `GLACIER_BACKUP_SETS` instead.

### Exit codes of the runner

| Code | Meaning |
|---|---|
| `0` | Every set synced without errors |
| `1` | At least one sync reported failed items |
| `2` | No backup sets could be determined (nothing ran), or a sync was invoked incorrectly |
| `124` | The time window closed before every set finished or had a turn |
| `137` | A sync was killed (SIGKILL), most likely by the kernel's out-of-memory killer |

When several sets finish differently, the runner reports the numerically
highest non-zero code, so a later clean set never hides an earlier failure.
Cron does not act on the code; read it from the log (below).

## Logs and monitoring

Everything lands in `/var/log/glacier-backup.log`: the runner's own lines in
`[timestamp] message` form, plus the tool's `[timestamp] LEVEL message` lines.
The runner keeps the log in check itself: when it exceeds 50 MB at the start of
a run, it moves it to `/var/log/glacier-backup.log.1`, replacing the previous
`.1`, so at most two generations exist.

An illustrative excerpt:

```text
[2026-08-27T02:00:01] sync run start (first set: photos)
[2026-08-27T02:00:01] syncing photos (budget 21600s)
[2026-08-27T02:00:09] INFO photos/2024/trip/IMG_0001.jpg ... done
[2026-08-27T08:00:01] syncing documents (budget 12s)
[2026-08-27T08:00:13] documents finished rc=124
[2026-08-27T08:00:13] window exhausted, skipping: media
[2026-08-27T08:00:13] sync run end rc=124
```

Useful commands:

```bash
ssh root@unas tail -n 50 /var/log/glacier-backup.log
ssh root@unas "grep -E 'FAILED|ERROR|rc=' /var/log/glacier-backup.log | tail -n 30"
ssh root@unas "grep 'sync run' /var/log/glacier-backup.log | tail"
```

A healthy night ends with `sync run end rc=0`. Check periodically; the NAS does
not alert you on its own.

## Memory

The wrapper starts the native binary with a Java heap cap of **1 GB**
(`-Xmx1g`). Without a cap the garbage collector sizes itself from physical RAM,
and a very large scan could make the kernel's OOM killer terminate the process
with a bare `Killed` (exit `137`), taking memory away from the rest of the NAS.
With a cap, the worst case is a clean Java `OutOfMemoryError` in the log.

- Change the cap for the scheduled runner with
  `GLACIER_BACKUP_XMX=2g` in `/etc/glacier-backup/run.env` (variables in
  that file are exported automatically); for a manual
  run, prefix the command:
  `ssh root@unas GLACIER_BACKUP_XMX=2g glacier-backup sync photos`.
- Keep the cap **well below the NAS's free RAM** (remember UniFi's own
  services). If exit `137` shows up, lower it or `scanThreads`.
- A lower `defaults.scanThreads` (the example uses 16) reduces memory used by
  the scan, at the cost of a slower scan.

## After firmware updates

UniFi firmware updates may wipe `/usr/local/bin` and `/etc`, which removes the
binary, the wrapper, the runner, your config, the cron entry and the rotation
state. After every firmware update, check and, if needed, redeploy:

```bash
ssh root@unas 'ls -l /usr/local/bin/glacier-backup* /etc/glacier-backup /etc/cron.d/glacier-backup'
```

If anything is missing, re-run both steps from your clone:

```bash
make deploy-unas
make setup-unas CONFIG=my-config.yml     # with AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY exported
```

Keep `my-config.yml` and the access key somewhere safe (a password manager and
a backup of your clone) so this takes a minute. If you maintain
`/etc/glacier-backup/run.env`, recreate it too.

## Upgrading

```bash
git pull                       # also updates the runner script and Makefile
make deploy-unas               # latest release; or: make deploy-unas VERSION=1.2.0
```

`deploy-unas` replaces the binary and wrapper in place. Re-run
`make setup-unas CONFIG=my-config.yml` only when the release notes mention a
runner or setup change, or to change the config or schedule. Releases are
listed at <https://github.com/functorful/glacier-backup/releases>.

## Restoring data

Restoring is a three-step flow (`restore`, `restore-status`, `get`) described in
[Usage: Restoring data](usage.md#restoring-data). You can run the commands on
the NAS through SSH, or on any other machine with the binary, a config for the
same bucket, and credentials that include `s3:GetObject` and `s3:RestoreObject`
(see the [IAM policy](configuration.md#3-iam-policy-least-privilege)).
Restoring on a laptop avoids needing broad credentials on the NAS.

```bash
ssh root@unas glacier-backup restore photos/2024/trip/ --recursive --tier bulk --days 7
ssh root@unas glacier-backup restore-status photos/2024/trip/
ssh root@unas glacier-backup get photos/2024/trip/ /path/with/enough/free/space --recursive
```

`get` writes to `<local-dir>/<full-remote-key>`, so choose a staging directory
with enough free space and move the files into place afterwards.

## Uninstalling

On the NAS, remove the schedule, runner, binary, config and state:

```bash
ssh root@unas '
  rm -f /etc/cron.d/glacier-backup
  crontab -l 2>/dev/null | grep -v glacier-backup-run | crontab - 2>/dev/null || true
  rm -f /usr/local/bin/glacier-backup /usr/local/bin/glacier-backup.bin /usr/local/bin/glacier-backup-run
  rm -rf /etc/glacier-backup
  rm -f /var/log/glacier-backup.log /var/log/glacier-backup.log.1
'
```

`/root/.aws/credentials` is shared with anything else that uses the AWS CLI on
the NAS; delete it (or just its `[default]` section) if nothing else needs it.
On the AWS side, delete or deactivate the access key
(`aws iam delete-access-key --user-name glacier-backup --access-key-id ...`).

Your backups stay in the bucket until you delete them. Deleting archive-class
objects before their minimum storage duration still bills the remainder.

## Troubleshooting

First, run the failing thing by hand so the error is on your screen:

```bash
ssh root@unas glacier-backup -v sync photos --dry-run
```

| Symptom | Cause and fix |
|---|---|
| `make setup-unas` stops with "still contains the `<volume-uuid>` placeholder" | Fill in your volume UUID in the config ([step 2](#2-find-your-share-paths)). |
| `... not found or not executable on unas (run 'make deploy-unas' first)` | Run `make deploy-unas`, and check `UNAS_HOST`/`UNAS_USER` and that SSH works. |
| SSH asks for a password many times | Set up key authentication and a `Host unas` alias ([Prerequisites](#prerequisites)). |
| `WARN skipping file with unmappable name` or accented files missing | Non-UTF-8 locale or legacy file names. See [Encoding problems](#encoding-problems). |
| Log line `rc=137 (SIGKILL — likely OOM-killed, check box memory)` | The kernel's OOM killer ended the process. Lower `GLACIER_BACKUP_XMX` (the cap must fit comfortably in free RAM) and/or `scanThreads`; check what else is using memory on the NAS. |
| `java.lang.OutOfMemoryError` in the log | The heap cap was too small for the scan. Raise `GLACIER_BACKUP_XMX` if the NAS has free RAM; see [Memory](#memory). |
| `rc=124` or `window exhausted, skipping: ...` | The 6-hour window closed. Normal during the first backups of a large library. The next run continues; to speed up, raise `GLACIER_BACKUP_WINDOW_SECONDS` or schedule runs more often. Check the uplink speed too. |
| `Unable to load credentials ...` | `/root/.aws/credentials` is missing or empty. Re-run `make setup-unas`, which rewrites it. |
| `AccessDenied` / 403, `InvalidAccessKeyId`, `SignatureDoesNotMatch` | Wrong or deactivated key, or the IAM policy lacks an action ([policy](configuration.md#3-iam-policy-least-privilege)); also check the policy's bucket name. |
| `NoSuchBucket`, or a redirect/`PermanentRedirect` error | `bucket` or `region` in the config is wrong. |
| `Failed to create the main Isolate (code 24)` | A native image built with 4K memory pages on a 64K-page kernel. Use the official `glacier-backup-linux-arm64` release asset, which is built to run on the UNAS Pro. |
| `no backup sets: set GLACIER_BACKUP_SETS or define backupSets in ...` (runner exit `2`) | The config is missing or its `backupSets:` is not block style with two-space-indented names. Fix it or set `GLACIER_BACKUP_SETS` in `run.env`. |
| Nothing runs on schedule | Check `/etc/cron.d/glacier-backup` exists (a firmware update may have removed it), that cron is running on the NAS, and that the schedule is in the NAS's time zone. Run `ssh root@unas /usr/local/bin/glacier-backup-run` once by hand and read the log. |
| `Unknown backup set` (exit `2`) | The name is not under `backupSets:` in the config used. |

### Encoding problems

Symptoms: `WARN skipping file with unmappable name: ...`, or files with
accented characters never show up in the bucket.

1. Make sure the process runs in a UTF-8 locale. The wrapper and the runner both
   do this; a bare `glacier-backup.bin` run over non-interactive SSH does not.
   Always run `glacier-backup` (the wrapper), not `glacier-backup.bin`.
2. If the warnings remain, the names on disk are not valid UTF-8 (usually
   legacy Latin-1 names from old shares or software). They are still backed up
   under a sanitized name (`WARN backing up file with unmappable name as: ...`),
   but it is much cleaner to convert them. From your workstation:

   ```bash
   # dry run: prints "WOULD RENAME: old -> new", changes nothing
   ./scripts/fix-filename-encoding.sh /volume/0a1b2c3d-1111-2222-3333-444455556666/.srv/.unifi-drive/Photos

   # looks right? apply it (default source encoding is LATIN1; use --from CP1252 for Windows names)
   ./scripts/fix-filename-encoding.sh --apply /volume/0a1b2c3d-1111-2222-3333-444455556666/.srv/.unifi-drive/Photos
   ```

   It connects to `UNAS_HOST`/`UNAS_USER` (defaults `unas`/`root`), renames only
   names that are not valid UTF-8, skips a rename if the target exists, and never
   touches valid names. It modifies your source files, so review the dry run
   first. Paths may contain only letters, digits and `_./@+,:=#%-`.

More on file names: [Usage: File names and encodings](usage.md#file-names-and-encodings).
