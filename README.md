# glacier-backup

[![CI](https://github.com/functorful/glacier-backup/actions/workflows/ci.yml/badge.svg)](https://github.com/functorful/glacier-backup/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/functorful/glacier-backup)](https://github.com/functorful/glacier-backup/releases/latest)
[![License: Unlicense](https://img.shields.io/badge/license-Unlicense-blue.svg)](LICENSE)

**Back up your UniFi UNAS Pro to S3 Glacier Deep Archive.**

`glacier-backup` is a command-line tool that syncs local directories to Amazon
S3 using the Glacier storage classes (Deep Archive by default, the cheapest
cloud storage S3 offers), and gives you the whole lifecycle around it:
listing, upload, delete, and an explicit restore workflow
(`restore` -> `restore-status` -> `get`). It ships as a single native binary
(no JVM needed) for Linux amd64/arm64 and macOS arm64, plus a JVM build for
anything that runs Java 21.

It was written first and foremost to back up a **UniFi UNAS Pro** NAS, and
this repository includes everything needed to install it there, schedule it,
and keep it running: see the [UNAS Pro guide](docs/unas-pro.md). It works just
as well on any other Linux or macOS machine.

> glacier-backup is an independent open-source project and is not affiliated
> with or endorsed by Ubiquiti or Amazon.

## Why

- **Cheap long-term storage.** Deep Archive costs on the order of $1 per
  terabyte per month (check [current AWS pricing](https://aws.amazon.com/s3/pricing/)),
  and it is the default storage class.
- **Choose what gets backed up.** Per-folder backup sets with include and
  exclude globs, so caches, recycle bins and thumbnails stay out of the bucket.
- **Built for unattended NAS runs.** The scheduled runner works within a time
  window (6 hours by default), rotates which set goes first so none starves,
  rotates its own log, and reports a meaningful exit code.
- **Additive by default.** `sync` only uploads. Remote objects are deleted
  only if you pass `--delete`, and never when the local scan was incomplete.
- **See before you act.** `--dry-run` prints exactly what a sync would do.
- **An explicit restore workflow.** Thawing archived objects, checking
  progress and downloading are separate, scriptable commands, with tiers and
  retention you control.

<!-- TODO(author): add a sentence or two here on why the built-in UniFi backup did not meet your needs. -->

## Quick start: UniFi UNAS Pro

Full walkthrough with troubleshooting: **[docs/unas-pro.md](docs/unas-pro.md)**.

You need an AWS account, a workstation with `git`, `make`, `curl`, `ssh` and
`scp`, and SSH enabled on the UNAS Pro (enable it in the UniFi console; check
UniFi's documentation for your firmware).

**1. Create the bucket and an IAM user** (one time, from a machine with the
AWS CLI configured). The commands are in
[AWS setup](docs/configuration.md#aws-setup); at the end you have a bucket,
an access key ID and a secret access key.

**2. Clone this repository** on your workstation:

```bash
git clone https://github.com/functorful/glacier-backup.git
cd glacier-backup
```

**3. Find your share paths and write a config.** Each UniFi Drive share lives
under a volume UUID on the NAS:

```bash
ssh root@unas 'ls -d /volume/*/.srv/.unifi-drive/*'
cp examples/unas-pro/config.yml my-config.yml
# edit my-config.yml: bucket, region, and replace <volume-uuid> everywhere
```

**4. Install the binary on the NAS** (downloads the latest linux-arm64
release, verifies its checksum, copies it over SSH):

```bash
make deploy-unas UNAS_HOST=unas
```

**5. Install credentials, config, the runner and the schedule:**

```bash
export AWS_ACCESS_KEY_ID=AKIA...
export AWS_SECRET_ACCESS_KEY=...
make setup-unas CONFIG=my-config.yml UNAS_HOST=unas
```

By default this schedules a run at 02:00 on Monday, Wednesday and Friday
(`SCHEDULE='0 2 * * 1,3,5'`) and ends with a dry-run smoke test.

**6. Check it.** Nothing is uploaded until the first scheduled run, so try it
by hand first:

```bash
ssh root@unas glacier-backup sync photos --dry-run
ssh root@unas tail -f /var/log/glacier-backup.log   # after the first scheduled run
```

After a UniFi firmware update, re-run steps 4 and 5; see
[After firmware updates](docs/unas-pro.md#after-firmware-updates).

## Quick start: any other machine

```bash
# 1. Download a native binary (linux-amd64 shown) and verify it
curl -fLO https://github.com/functorful/glacier-backup/releases/latest/download/glacier-backup-linux-amd64
curl -fLO https://github.com/functorful/glacier-backup/releases/latest/download/SHA256SUMS
sha256sum -c --ignore-missing SHA256SUMS
chmod +x glacier-backup-linux-amd64
sudo install glacier-backup-linux-amd64 /usr/local/bin/glacier-backup

# 2. Credentials (or use ~/.aws/credentials / AWS_PROFILE)
export AWS_ACCESS_KEY_ID=AKIA...
export AWS_SECRET_ACCESS_KEY=...
export LANG=C.UTF-8 LC_ALL=C.UTF-8     # needed for non-ASCII file names

# 3. Minimal config
mkdir -p ~/.config/glacier-backup
cat > ~/.config/glacier-backup/config.yml <<'EOF'
bucket: my-glacier-backup-bucket
region: eu-west-1
EOF

# 4. See what would happen, then do it
glacier-backup sync ~/Documents documents/ --dry-run
glacier-backup sync ~/Documents documents/
```

Assets for macOS arm64 and the JVM zip, the UTF-8 wrapper script, Gatekeeper,
and building from source are covered in [docs/installation.md](docs/installation.md).

## Features

- One-way sync of a directory or a named backup set to an S3 prefix
  (`--delete` is opt-in, `--dry-run` shows the plan).
- Storage classes `STANDARD`, `GLACIER_IR`, `GLACIER` and `DEEP_ARCHIVE`
  (default), configurable globally and per run.
- Include/exclude glob filters in config and on the command line.
- Parallel local scan tuned for spinning-disk arrays and large trees.
- Multipart uploads with automatic retries for flaky or slow uplinks.
- Restore lifecycle with `bulk`, `standard` and `expedited` tiers.
- Unattended runner for the UNAS Pro: time window, set rotation, log rotation,
  cron integration, one-command install.
- Native binaries for Linux amd64/arm64 and macOS arm64, plus a JVM zip.
- Credentials from the standard AWS chain; nothing secret in the config file.

## Command cheat sheet

| Command | Purpose |
|---|---|
| `sync <set\|local-path> [remote-prefix]` | One-way sync of a backup set, or of a directory to a prefix. `--dry-run`, `--delete`, `--include`, `--exclude`, `--storage-class`. |
| `up <file>... <remote-prefix>` | Upload individual files. |
| `ls [prefix] [-l] [--recursive]` | List remote objects (`-l` adds size, date, class, restore state). |
| `rm <path>... [--recursive]` | Delete remote objects. |
| `restore <path>... [--tier T] [--days N] [--recursive]` | Start thawing archived objects. |
| `restore-status [prefix]` | Show restore progress. |
| `get <path>... <local-dir> [--recursive]` | Download objects once restored. |

Global options: `--config <file>`, `-v`/`--verbose`, `--scan-threads <n>`,
`--version`. Exit codes: `0` success, `1` failure (some or all items), `2` usage
or configuration error. Details in [docs/usage.md](docs/usage.md).

## Documentation

| Document | What is in it |
|---|---|
| [UNAS Pro guide](docs/unas-pro.md) | Complete setup, scheduling, upgrades, uninstall and troubleshooting on a UniFi UNAS Pro. |
| [Installation](docs/installation.md) | Native binaries, checksums, the UTF-8 wrapper, macOS Gatekeeper, JVM zip, building from source. |
| [Configuration](docs/configuration.md) | Config file reference, glob filters, credentials, AWS setup (bucket, IAM policy), storage classes and costs. |
| [Usage](docs/usage.md) | Every command and flag, the restore walkthrough, exit codes, scheduling with cron, systemd and launchd. |
| [Development](docs/development.md) | Building, testing, CI, the release process and repository settings. |
| [Architecture](docs/architecture.md) | Module layout and how a command flows from the CLI to S3. |
| [Domain model](docs/domain-model.md) | Domain types, the sync diff rules, the restore state machine. |
| [Contributing](CONTRIBUTING.md) | How to build, test and open a pull request. |

## Releases and versioning

Every merge to `main` publishes a new `vX.Y.Z` GitHub release with binaries
and checksums; the version bump defaults to minor and is controlled with
`#major` / `#minor` / `#patch` / `#none` keywords (see
[Development](docs/development.md#release-process)).

## License

[The Unlicense](LICENSE): public domain, no warranty.
