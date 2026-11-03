# Configuration

Everything `glacier-backup` needs to know lives in one YAML file plus the
standard AWS credentials. This page covers:

- [Where the config file is found](#where-the-config-file-is-found)
- [A complete example](#a-complete-example)
- [Key reference](#key-reference)
- [Backup sets and filters](#backup-sets-and-filters)
- [Credentials](#credentials)
- [AWS setup](#aws-setup) (bucket, lifecycle rule, IAM policy, access key)
- [Storage classes and costs](#storage-classes-and-costs)

## Where the config file is found

The first match wins:

1. `--config <path>`, if given. The file must exist; a missing explicit path
   fails immediately.
2. `$XDG_CONFIG_HOME/glacier-backup/config.yml` when `XDG_CONFIG_HOME` is set
   and non-empty, otherwise `~/.config/glacier-backup/config.yml`.
3. `/etc/glacier-backup/config.yml`.

If none exists, the command exits with code `2` and lists the paths it
searched. The UNAS Pro install uses the third location
(`/etc/glacier-backup/config.yml`).

The file must be a YAML mapping. Unknown keys are silently ignored, so a typo
in an optional key (for example `storageclass`) simply has no effect.

## A complete example

```yaml
# Required: the bucket every command reads and writes (one bucket per config).
bucket: my-glacier-backup-bucket
# Required: the AWS region of that bucket.
region: eu-west-1

defaults:
  # Optional. STANDARD | GLACIER_IR | GLACIER | DEEP_ARCHIVE (default).
  storageClass: DEEP_ARCHIVE
  # Optional. Concurrent local-scan threads, 1-128 (default 32).
  scanThreads: 16

# Optional. Named backup sets, run with `glacier-backup sync <name>`.
backupSets:
  photos:
    local: /srv/photos
    prefix: photos/

  documents:
    local: /srv/documents
    prefix: documents/
    include:
      - "**/*.pdf"
      - "notes/**"
    exclude:
      - ".DS_Store"
      - "**/.DS_Store"
      - "*.tmp"
      - "**/*.tmp"
```

Ready-made starting points: [`examples/config.example.yml`](../examples/config.example.yml)
(generic, with more commented filters) and
[`examples/unas-pro/config.yml`](../examples/unas-pro/config.yml) (UNAS Pro
shares). Both are loaded by the test suite, so they stay valid. Replace the
`bucket` placeholder in them with your own bucket name.

## Key reference

| Key | Type | Required | Default | Notes |
|---|---|---|---|---|
| `bucket` | string | yes | | Name of an existing S3 bucket. Never given on the command line. |
| `region` | string | yes | | AWS region of the bucket, for example `eu-west-1`. |
| `defaults.storageClass` | string | no | `DEEP_ARCHIVE` | One of `STANDARD`, `GLACIER_IR`, `GLACIER`, `DEEP_ARCHIVE`. In the config file the value must be written in upper case exactly like this. `--storage-class` on the command line overrides it and is case-insensitive. |
| `defaults.scanThreads` | integer | no | `32` | Concurrent local-scan worker threads, `1` to `128`. Overridden by `--scan-threads`. See [tuning](#scan-threads). |
| `backupSets` | mapping | no | none | Name -> set definition. Names are what you pass to `sync <name>`. |
| `backupSets.<name>.local` | string | yes, per set | | Local directory the set backs up. It must exist when the sync runs. |
| `backupSets.<name>.prefix` | string | yes, per set | | Remote key prefix. A trailing slash is conventional (`photos/`); it is added or stripped as needed. |
| `backupSets.<name>.include` | list of globs | no | empty | Allow-list. Empty or omitted means "everything". |
| `backupSets.<name>.exclude` | list of globs | no | empty | Deny-list. Exclude wins over include. |

Validation errors (a missing `bucket`, `region`, `local` or `prefix`, an
unknown storage class, a `scanThreads` outside `1..128`, an unreadable file)
exit with code `2` and a one-line message such as
`Missing required config key: backupSets.photos.local`.

Notes for the UNAS runner: set names are discovered by a simple text scan of
the file, not a YAML parser. Use block style with set names indented by exactly
two spaces, no spaces in names, as in the examples. Use only letters, digits,
`.`, `_` and `-` in set names. See [the UNAS guide](unas-pro.md#how-the-scheduled-runner-works).

### Scan threads

The local scan is metadata I/O (one `lstat` per file), so the useful number
tracks how many concurrent seeks your disks can serve, not CPU cores. Large
spinning-disk arrays benefit from many threads; a small NAS with limited RAM
is better off with fewer, which also lowers memory use. The UNAS Pro example
uses `16`. Precedence: `--scan-threads` beats `defaults.scanThreads` beats `32`.

## Backup sets and filters

A backup set ties a local directory to a remote prefix. Every file under
`local` is uploaded as `<prefix><path relative to local>`. For example, with
`local: /srv/photos` and `prefix: photos/`, the file
`/srv/photos/2024/trip/IMG_1.jpg` becomes the object
`photos/2024/trip/IMG_1.jpg`.

Only regular files are backed up. Symbolic links are not followed or stored,
and empty directories leave no trace in the bucket.

### How include and exclude combine

A file is backed up when:

1. it does **not** match any `exclude` glob (exclude always wins), **and**
2. the `include` list is empty, **or** it matches at least one `include` glob.

Filters are matched against files, not directories, using the path relative to
the set's `local` directory with `/` separators. An excluded directory tree is
still walked by the scanner; filters save upload time, not scan time.

When you add `--include` or `--exclude` on the command line while syncing a
set, they are added to the set's own lists (the set's globs first). Because
include globs are OR-ed together, a command-line `--include` can only widen a
set that already has an `include` list; with no `include` list in the set, it
restricts the sync to the matching files.

Filters also decide which **remote** objects `--delete` may remove: only remote
objects whose path passes the same filter and that have no local counterpart.
Objects outside the filter (for example files you later excluded) are left
alone. See [`--delete` semantics](usage.md#--delete-semantics-and-safety).

### Glob syntax

Globs use Java's `java.nio.file` glob syntax (`FileSystem.getPathMatcher("glob:...")`),
which differs from most shells:

| Pattern | Meaning |
|---|---|
| `*` | Any characters **within one path segment**. Never crosses `/`. |
| `**` | Any characters, **including** `/`. |
| `?` | Exactly one character (not `/`). |
| `[abc]`, `[a-z]` | One character from the set or range. |
| `{a,b}` | Either alternative, for example `*.{jpg,png}`. |
| `\` | Escapes the next character. |

The important subtlety: `**/` needs at least one directory in front of it.

| Pattern | `file.tmp` (set root) | `a/file.tmp` | `a/b/file.tmp` |
|---|---|---|---|
| `*.tmp` | match | no | no |
| `**/*.tmp` | no | match | match |
| `{*.tmp,**/*.tmp}` | match | match | match |

So to match a name at every depth, list both the bare and the `**/` form
(or use a single `{a,b}` alternation). More examples:

```yaml
exclude:
  - ".DS_Store"           # at the set root
  - "**/.DS_Store"        # in any subdirectory
  - "tmp/**"              # everything under <local>/tmp/
  - "**/tmp/**"           # everything under any directory named tmp
  - "**/node_modules/**"
  - "**/*.{tmp,swp}"
include:
  - "**/*.pdf"            # PDFs in subdirectories ...
  - "*.pdf"               # ... and at the root
  - "notes/**"            # everything under notes/
```

Check a filter with `glacier-backup -v sync <set> --dry-run`, which prints one
`UPLOAD` line per file that would be sent.

### File names

Names are converted to S3 keys as UTF-8, so the process must run in a UTF-8
locale; see [File names and encodings](usage.md#file-names-and-encodings).

## Credentials

AWS credentials are never read from the config file. `glacier-backup` uses the
AWS SDK for Java v2 **default credentials provider chain**, so anything the
SDK understands works. In order of precedence, the common sources are:

1. Java system properties (`aws.accessKeyId`, `aws.secretAccessKey`), rarely
   used with this tool.
2. Environment variables: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` (and
   `AWS_SESSION_TOKEN` for temporary credentials).
3. Web identity token credentials.
4. The shared credentials and config files: `~/.aws/credentials` and
   `~/.aws/config`, using the `[default]` profile, or the profile named by
   `AWS_PROFILE`. `AWS_SHARED_CREDENTIALS_FILE` and `AWS_CONFIG_FILE` relocate
   the files.
5. Container credentials (ECS) and EC2 instance metadata.

The region always comes from the `region` key in the config file.

```bash
# environment variables
export AWS_ACCESS_KEY_ID=AKIA...
export AWS_SECRET_ACCESS_KEY=...

# or a named profile
export AWS_PROFILE=glacier-backup
```

```ini
# ~/.aws/credentials
[glacier-backup]
aws_access_key_id = AKIA...
aws_secret_access_key = ...
```

On the UNAS Pro, `make setup-unas` writes `/root/.aws/credentials` (mode 600)
for you; the scheduled runner runs as root with `HOME=/root`.

Rotating a key without downtime: create a second key for the IAM user, put it
in place, confirm the next run works, then delete the old key (see
[Create the IAM user and access key](#4-create-the-iam-user-and-access-key)).

## AWS setup

This is the one-time AWS work. You need the [AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)
configured with an administrator-ish profile (it is only used for setup, never
by the NAS). The examples use the bucket `my-glacier-backup-bucket` in
`eu-west-1`; bucket names are globally unique, so pick your own.

### 1. Create the bucket

```bash
export BUCKET=my-glacier-backup-bucket
export REGION=eu-west-1

aws s3api create-bucket \
  --bucket "$BUCKET" \
  --region "$REGION" \
  --create-bucket-configuration LocationConstraint="$REGION"
```

For `us-east-1` omit `--create-bucket-configuration` entirely.

Block all public access (new buckets already do by default; being explicit
costs nothing):

```bash
aws s3api put-public-access-block \
  --bucket "$BUCKET" \
  --public-access-block-configuration \
    BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
```

New buckets are encrypted at rest with S3-managed keys (SSE-S3) by default,
which needs no extra permissions. If you switch the bucket to SSE-KMS, the IAM
user also needs permission to use that KMS key.

### 2. Abort incomplete multipart uploads (recommended)

Large files are uploaded in parts. If a run is stopped mid-file (the UNAS
runner's time window closing, a power cut), the already-uploaded parts stay in
the bucket, invisible but billed, until the multipart upload is aborted. A
lifecycle rule cleans them up automatically:

```bash
aws s3api put-bucket-lifecycle-configuration \
  --bucket "$BUCKET" \
  --lifecycle-configuration '{
    "Rules": [
      {
        "ID": "abort-incomplete-multipart-uploads",
        "Status": "Enabled",
        "Filter": {},
        "AbortIncompleteMultipartUpload": { "DaysAfterInitiation": 7 }
      }
    ]
  }'
```

Note that an interrupted file is re-uploaded from the start on the next run;
partial uploads are not resumed.

### 3. IAM policy (least privilege)

The S3 calls the tool makes, and the permission each needs, taken from the
S3 adapter (`S3RemoteStorage`):

| Operation | Used by | IAM action |
|---|---|---|
| List objects | `sync`, `ls`, `restore --recursive`, `get --recursive`, `rm --recursive`, `restore-status` | `s3:ListBucket` (on the bucket) |
| Upload, including multipart | `sync`, `up` | `s3:PutObject` (on the objects) |
| Clean up a failed multipart upload | `sync`, `up` | `s3:AbortMultipartUpload` |
| Head object (existence, restore state) | `ls -l`, `rm`, `restore`, `restore-status`, `get` | `s3:GetObject` (S3 authorizes `HeadObject` with it) |
| Download | `get` | `s3:GetObject` |
| Delete | `rm`, `sync --delete` | `s3:DeleteObject` |
| Start a restore | `restore` | `s3:RestoreObject` |

Save this as `policy.json`, replacing the bucket name:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ListBucket",
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::my-glacier-backup-bucket"
    },
    {
      "Sid": "ObjectAccess",
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:AbortMultipartUpload",
        "s3:GetObject",
        "s3:DeleteObject",
        "s3:RestoreObject"
      ],
      "Resource": "arn:aws:s3:::my-glacier-backup-bucket/*"
    }
  ]
}
```

**Optional hardening for an unattended machine.** A NAS that only runs the
scheduled `sync` (without `--delete`) needs just `s3:ListBucket`,
`s3:PutObject` and `s3:AbortMultipartUpload`. Giving the NAS those only means
that someone who steals its credentials can add data but cannot delete or read
your backups. Do restores (and deletions) from a laptop with a second IAM user
that has the full policy above. This is derived from the code paths in the
table; verify it with a `sync --dry-run` and a small `up` before relying on it.

### 4. Create the IAM user and access key

```bash
aws iam create-user --user-name glacier-backup

aws iam put-user-policy \
  --user-name glacier-backup \
  --policy-name glacier-backup-s3 \
  --policy-document file://policy.json

aws iam create-access-key --user-name glacier-backup
```

The last command prints `AccessKeyId` and `SecretAccessKey`; the secret is
shown only once, so store it somewhere safe (a password manager). These are the
values for `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`.

Rotation without downtime (two keys can be active at once):

```bash
aws iam create-access-key --user-name glacier-backup          # new key
# install the new key on the machine, confirm a run succeeds, then:
aws iam delete-access-key --user-name glacier-backup --access-key-id AKIA_OLD_KEY_ID
```

### 5. Test it

```bash
export AWS_ACCESS_KEY_ID=...
export AWS_SECRET_ACCESS_KEY=...
glacier-backup --config ./my-config.yml ls
```

An empty bucket prints nothing and exits `0`. `AccessDenied` or
`Unable to load credentials` errors are covered in
[the troubleshooting section](unas-pro.md#troubleshooting).

## Storage classes and costs

`defaults.storageClass` (or `--storage-class`) selects the S3 storage class of
every uploaded object.

| Class | Retrieval | Minimum storage duration | Restore needed? | Use it for |
|---|---|---|---|---|
| `STANDARD` | Immediate | none | no | Data you read often. Most expensive to store. |
| `GLACIER_IR` | Immediate (milliseconds) | 90 days | no | Archives you may need to read occasionally without waiting. |
| `GLACIER` (Flexible Retrieval) | Restore first: expedited about 1-5 minutes, standard about 3-5 hours, bulk about 5-12 hours | 90 days | yes | Backups you may need within hours. |
| `DEEP_ARCHIVE` (default) | Restore first: standard within about 12 hours, bulk within about 48 hours. No expedited tier. | 180 days | yes | True disaster-recovery backups you hope never to read. Cheapest. |

Retrieval times are AWS's published targets, not guarantees, and they can
change; confirm against the
[S3 storage class documentation](https://docs.aws.amazon.com/AmazonS3/latest/userguide/storage-class-intro.html)
and [pricing](https://aws.amazon.com/s3/pricing/).

What to keep in mind:

- **Storage is cheap, retrieval is not.** Deep Archive storage is on the order
  of $1 per TB-month (region dependent), but restoring charges per GB for the
  retrieval, plus normal S3 data-transfer-out charges when you download over
  the internet. Restoring a whole library is meant to be a rare event; restore
  only what you need, and prefer the `bulk` tier when you can wait.
- **Minimum storage duration.** Deleting or overwriting an object before its
  minimum duration (90 days for `GLACIER`, 180 days for `DEEP_ARCHIVE`) still
  bills the remainder. A file that changes often and is re-uploaded on each run
  replaces the stored object each time, so exclude volatile files (databases,
  caches, logs) from archive-class sets, and think twice before `sync --delete`
  or `rm` on archive data.
- **Per-object overhead.** AWS adds a fixed metadata overhead of roughly 40 KB
  to every archived object (part of it billed at the Standard rate). Millions
  of tiny files cost noticeably more than the same bytes in fewer, larger
  files. Consider archiving small-file trees (for example with `tar`) before
  backing them up.
- **Restored copies are temporary.** `restore --days N` makes a readable copy
  for `N` days; after that, the object is archived-only again. You pay for the
  temporary copy at the Standard rate while it exists.
- **Requests cost money too.** `ls -l` and `restore-status` issue one `HEAD`
  request per archived object. On large prefixes, narrow the prefix.

The restore workflow itself is in [Usage](usage.md#restoring-data).
