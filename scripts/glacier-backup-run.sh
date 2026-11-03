#!/bin/sh
# Scheduled sync runner, installed to /usr/local/bin/glacier-backup-run by
# scripts/setup-unas.sh and meant to be started from cron. Syncs every backup
# set in turn, stopping gracefully when the time window (default 6 hours)
# closes. SIGTERM mid-transfer is safe: sync is additive and idempotent, and
# you should configure the bucket to abort incomplete multipart uploads after
# a few days.
#
# Which sets run (first match wins):
#   1. GLACIER_BACKUP_SETS   space-separated set names, e.g. "photos music"
#   2. the top-level keys under `backupSets:` in the config file
#      (GLACIER_BACKUP_CONFIG, default /etc/glacier-backup/config.yml),
#      in file order
#
# Config parsing is a deliberately simple awk scan, not a YAML parser: it only
# understands block-style `backupSets:` with set names indented by exactly two
# spaces (optionally quoted), no spaces in the names, and no flow style
# (`backupSets: {a: ..., b: ...}`). If your config is more exotic, set
# GLACIER_BACKUP_SETS instead.
#
# Overrides can also live in an optional env file (default
# /etc/glacier-backup/run.env, path via GLACIER_BACKUP_RUN_ENV), sourced as
# shell before anything else, e.g.:
#   GLACIER_BACKUP_SETS="photos documents"
#   GLACIER_BACKUP_WINDOW_SECONDS=10800
#
# Exit status: 0 on success; 124 if the window closed before every set ran;
# otherwise the worst non-zero exit status of any per-set sync; 2 if no sets
# could be determined.
export HOME=/root
# UTF-8 locale so accented filenames map cleanly; without it the JVM's
# path charset is ASCII and every non-ASCII name is skipped as unmappable.
export LANG=C.UTF-8 LC_ALL=C.UTF-8
# Set names are expanded unquoted below; make sure they can never glob.
set -f

RUN_ENV=${GLACIER_BACKUP_RUN_ENV:-/etc/glacier-backup/run.env}
if [ -r "$RUN_ENV" ]; then
  # set -a exports every assignment, so variables read by child processes
  # (e.g. GLACIER_BACKUP_XMX, read by the wrapper) work without `export`.
  set -a
  # shellcheck disable=SC1090
  . "$RUN_ENV"
  set +a
fi

# Env overrides also let the script be smoke-tested off-box.
LOG=${GLACIER_BACKUP_LOG:-/var/log/glacier-backup.log}
STATE=${GLACIER_BACKUP_STATE:-/etc/glacier-backup/next-first-set}
BIN=${GLACIER_BACKUP_BIN:-/usr/local/bin/glacier-backup}
CONFIG=${GLACIER_BACKUP_CONFIG:-/etc/glacier-backup/config.yml}
WINDOW_SECONDS=${GLACIER_BACKUP_WINDOW_SECONDS:-21600}
MAX_LOG_BYTES=${GLACIER_BACKUP_MAX_LOG_BYTES:-52428800}

# Set discovery: explicit list wins, otherwise read the config.
SETS=${GLACIER_BACKUP_SETS:-}
if [ -z "$SETS" ] && [ -r "$CONFIG" ]; then
  SETS=$(awk -v q="'" '
    /^backupSets:[ \t]*(#.*)?$/ { in_sets = 1; next }
    in_sets && /^[^ \t#]/ { in_sets = 0 }
    in_sets && /^  [^ \t#][^:]*:/ {
      key = $0
      sub(/^  /, "", key)
      sub(/:.*/, "", key)
      gsub(/["\t ]/, "", key)
      gsub(q, "", key)
      if (key != "") print key
    }
  ' "$CONFIG" | tr '\n' ' ')
fi
# Normalize to single-space-separated, no leading/trailing space.
set -- $SETS
SETS="$*"
if [ -z "$SETS" ]; then
  echo "[$(date '+%Y-%m-%dT%H:%M:%S')] no backup sets: set GLACIER_BACKUP_SETS or define backupSets in $CONFIG" >&2
  echo "[$(date '+%Y-%m-%dT%H:%M:%S')] no backup sets found (GLACIER_BACKUP_SETS empty, none in $CONFIG)" >> "$LOG" 2>/dev/null
  exit 2
fi

# Size-based rotation before appending: /var/log has no logrotate coverage on
# the UNAS, and per-file upload lines grow the log without bound. Checked
# once per run, so each generation (.log + .log.1) is bounded by
# MAX_LOG_BYTES plus one run's output. wc -c instead of stat: busybox-safe.
if [ -f "$LOG" ] && [ "$(wc -c < "$LOG")" -gt "$MAX_LOG_BYTES" ]; then
  mv -f "$LOG" "$LOG.1"
fi

# Global time window (default 6h). timeout wraps each glacier-backup
# invocation directly so SIGTERM reaches the actual process — wrapping the
# whole loop only kills the shell and orphans a running sync past the window.
START=$(date +%s)
DEADLINE=$((START + WINDOW_SECONDS))

# Rotation: start at the set recorded by the previous run. A fixed order
# starves the late sets whenever the window closes early night after night
# (the last sets in the list would never run). Each set gets
# the full remaining window on its turn; an unknown or missing state falls
# back to the first set.
first=$(cat "$STATE" 2>/dev/null || true)
case " $SETS " in
  *" $first "*) ;;
  *) first=${SETS%% *} ;;
esac

# Advance the rotation unconditionally, before anything runs: the state
# file's sole meaning is "the set that goes first on the next run". Writing
# it here (rather than as an end-of-run step) means a run that never
# finishes — crashed, killed, or window-exhausted mid-set — still hands the
# next run a fresh starting set instead of pinning it to the same one
# forever.
next=""
take=""
for set in $SETS; do
  if [ -n "$take" ]; then
    next=$set
    break
  fi
  if [ "$set" = "$first" ]; then
    take=1
  fi
done
if [ -z "$next" ]; then
  next=${SETS%% *}
fi
echo "$next" > "$STATE"

before=""
after=""
seen=""
for set in $SETS; do
  if [ "$set" = "$first" ]; then
    seen=1
  fi
  if [ -n "$seen" ]; then
    after="$after $set"
  else
    before="$before $set"
  fi
done
order="$after $before"

echo "[$(date '+%Y-%m-%dT%H:%M:%S')] sync run start (first set: $first)" >> "$LOG"
rc=0
# Normalize to single-space-separated (no leading/trailing space) so the
# skipped-sets log line below is consistently formatted on every iteration.
set -- $order
remaining="$*"
for set in $order; do
  now=$(date +%s)
  left=$((DEADLINE - now))
  if [ "$left" -le 0 ]; then
    echo "[$(date '+%Y-%m-%dT%H:%M:%S')] window exhausted, skipping: $remaining" >> "$LOG"
    if [ "$rc" -lt 124 ]; then
      rc=124
    fi
    break
  fi
  echo "[$(date '+%Y-%m-%dT%H:%M:%S')] syncing $set (budget ${left}s)" >> "$LOG"
  # Pass --config through only when explicitly overridden; otherwise the
  # binary uses its own default search path.
  if [ -n "${GLACIER_BACKUP_CONFIG:-}" ]; then
    timeout -s TERM "$left" "$BIN" --config "$CONFIG" sync "$set" >> "$LOG" 2>&1
  else
    timeout -s TERM "$left" "$BIN" sync "$set" >> "$LOG" 2>&1
  fi
  s=$?
  if [ "$s" -ne 0 ]; then
    hint=""
    # rc=137 is 128+SIGKILL: the kernel OOM killer, not the JVM, ended the
    # process — flag it explicitly since a bare "rc=137" line reads as an
    # ordinary failure and sends whoever's triaging looking at the wrong
    # layer first.
    if [ "$s" -eq 137 ]; then
      hint=" (SIGKILL — likely OOM-killed, check box memory)"
    fi
    echo "[$(date '+%Y-%m-%dT%H:%M:%S')] $set finished rc=$s$hint" >> "$LOG"
    # Keep the worst per-set status: a later clean set must not mask an
    # earlier failure (the old '|| rc=$?' did exactly that).
    if [ "$s" -gt "$rc" ]; then
      rc=$s
    fi
  fi
  # $set just ran: drop it from the head of $remaining for the next
  # iteration's skipped-sets log line.
  set -- $remaining
  shift
  remaining="$*"
done
echo "[$(date '+%Y-%m-%dT%H:%M:%S')] sync run end rc=$rc" >> "$LOG"
exit "$rc"
