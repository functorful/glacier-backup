#!/usr/bin/env bash
# One-shot (re-runnable) setup of glacier-backup on a UniFi UNAS Pro (or any
# Linux box reachable over ssh as root): AWS credentials, config, the sync
# runner script and a cron entry, finishing with a dry-run smoke test.
#
# Prerequisite: the glacier-backup binary must already be installed at
# /usr/local/bin/glacier-backup on the target (see `make deploy-unas`).
#
# Usage:
#   export AWS_ACCESS_KEY_ID=...        # never printed, never on a command line
#   export AWS_SECRET_ACCESS_KEY=...
#   CONFIG=path/to/config.yml ./scripts/setup-unas.sh
#
# Environment:
#   CONFIG                 (required) local config file to install as
#                          /etc/glacier-backup/config.yml on the target;
#                          see examples/unas-pro/config.yml
#   AWS_ACCESS_KEY_ID      (required) credentials written to /root/.aws/credentials
#   AWS_SECRET_ACCESS_KEY  (required)
#   UNAS_HOST              ssh host of the target            (default: unas)
#   UNAS_USER              ssh user; must be root, because   (default: root)
#                          credentials, config and cron are installed system-wide
#   SCHEDULE               5-field cron expression for the   (default: 0 2 * * 1,3,5)
#                          sync runner
#
# Re-running is safe: files are overwritten in place and the cron entry is
# replaced, not duplicated.
set -euo pipefail

UNAS_HOST=${UNAS_HOST:-unas}
UNAS_USER=${UNAS_USER:-root}
SCHEDULE=${SCHEDULE:-0 2 * * 1,3,5}
CONFIG=${CONFIG:-}
REMOTE="$UNAS_USER@$UNAS_HOST"
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)

BIN_PATH=/usr/local/bin/glacier-backup
RUNNER_PATH=/usr/local/bin/glacier-backup-run
CONFIG_DIR=/etc/glacier-backup
CRON_FILE=/etc/cron.d/glacier-backup

die() {
  echo "error: $*" >&2
  exit 1
}

# --- Validate inputs (before touching the target) ----------------------------

[ -n "$CONFIG" ] || die "CONFIG is required: the local config file to install.
  Example: CONFIG=examples/unas-pro/config.yml ./scripts/setup-unas.sh"
[ -f "$CONFIG" ] || die "CONFIG file not found: $CONFIG"
if grep -q '<volume-uuid>' "$CONFIG"; then
  die "$CONFIG still contains the <volume-uuid> placeholder; fill in your real volume paths first"
fi

[ -n "${AWS_ACCESS_KEY_ID:-}" ] || die "AWS_ACCESS_KEY_ID is required (export it; it is not echoed)"
[ -n "${AWS_SECRET_ACCESS_KEY:-}" ] || die "AWS_SECRET_ACCESS_KEY is required (export it; it is not echoed)"

# Cron expression: exactly five fields of plain cron characters. It is spliced
# into a cron file, so refuse anything that could add lines or commands.
if ! [[ "$SCHEDULE" =~ ^[-0-9A-Za-z*/,]+\ [-0-9A-Za-z*/,]+\ [-0-9A-Za-z*/,]+\ [-0-9A-Za-z*/,]+\ [-0-9A-Za-z*/,]+$ ]]; then
  die "SCHEDULE must be a 5-field cron expression, e.g. '0 2 * * 1,3,5' (got: $SCHEDULE)"
fi

# Backup set names in config order: top-level keys under `backupSets:`. Same
# simple awk scan as scripts/glacier-backup-run.sh (block style, 2-space
# indented keys); see the limitations documented there.
config_sets() {
  awk -v q="'" '
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
  ' "$1"
}
FIRST_SET=$(config_sets "$CONFIG" | head -n 1)
if [ -n "$FIRST_SET" ] && ! [[ "$FIRST_SET" =~ ^[A-Za-z0-9._-]+$ ]]; then
  die "backup set name '$FIRST_SET' contains characters other than letters, digits, '.', '_' and '-'"
fi
[ -n "$FIRST_SET" ] || echo "warning: no backupSets found in $CONFIG; the smoke test will be skipped" >&2

# --- Install -------------------------------------------------------------------

echo "==> Checking $REMOTE and $BIN_PATH"
ssh -n "$REMOTE" "test -x $BIN_PATH" \
  || die "$BIN_PATH not found or not executable on $UNAS_HOST (run 'make deploy-unas' first), or ssh to $REMOTE failed"

echo "==> Writing /root/.aws/credentials on $UNAS_HOST"
ssh -n "$REMOTE" "mkdir -p /root/.aws $CONFIG_DIR && chmod 700 /root/.aws"
# printf is a shell builtin and the secrets travel over ssh's stdin, so they
# never appear in any process list on either machine.
printf '[default]\naws_access_key_id = %s\naws_secret_access_key = %s\n' \
  "$AWS_ACCESS_KEY_ID" "$AWS_SECRET_ACCESS_KEY" \
  | ssh "$REMOTE" 'umask 077 && cat > /root/.aws/credentials.new && chmod 600 /root/.aws/credentials.new && mv -f /root/.aws/credentials.new /root/.aws/credentials'

echo "==> Writing $CONFIG_DIR/config.yml (from $CONFIG)"
ssh "$REMOTE" "cat > $CONFIG_DIR/config.yml.new && chmod 644 $CONFIG_DIR/config.yml.new && mv -f $CONFIG_DIR/config.yml.new $CONFIG_DIR/config.yml" \
  < "$CONFIG"

echo "==> Writing $RUNNER_PATH (time-windowed run, rotating start)"
ssh "$REMOTE" "cat > $RUNNER_PATH.new && chmod 755 $RUNNER_PATH.new && mv -f $RUNNER_PATH.new $RUNNER_PATH" \
  < "$SCRIPT_DIR/glacier-backup-run.sh"

echo "==> Installing cron entry ($SCHEDULE)"
CRON_COMMAND_LINE="$SCHEDULE $RUNNER_PATH"
if ssh -n "$REMOTE" 'test -d /etc/cron.d'; then
  # /etc/cron.d entries carry a user field.
  printf '%s root %s\n' "$SCHEDULE" "$RUNNER_PATH" \
    | ssh "$REMOTE" "cat > $CRON_FILE && chmod 644 $CRON_FILE"
else
  # No /etc/cron.d: edit root's crontab, replacing any previous entry.
  printf '%s\n' "$CRON_COMMAND_LINE" \
    | ssh "$REMOTE" '{ crontab -l 2>/dev/null | grep -v glacier-backup-run || true; cat; } | crontab -'
fi

if [ -n "$FIRST_SET" ]; then
  echo "==> Smoke test: dry-run of set '$FIRST_SET' (nothing is uploaded)"
  if smoke_output=$(ssh -n "$REMOTE" "$BIN_PATH sync $FIRST_SET --dry-run" 2>&1); then
    printf '%s\n' "$smoke_output" | tail -5
  else
    printf '%s\n' "$smoke_output" | tail -20 >&2
    die "dry-run failed; check the credentials, bucket/region and the local path of set '$FIRST_SET'"
  fi
fi

echo "==> Done. Logs will appear at /var/log/glacier-backup.log on $UNAS_HOST."
echo "    NOTE: firmware updates may wipe /usr/local/bin and /etc - re-run"
echo "    'make deploy-unas' and this script afterwards."
