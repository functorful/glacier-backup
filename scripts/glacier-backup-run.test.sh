#!/bin/sh
# Off-box smoke tests for scripts/glacier-backup-run.sh.
#
# There is no shell-test framework in this repo, so this is a small
# hand-rolled harness. It drives the runner through its existing env-var
# overrides (GLACIER_BACKUP_LOG / _STATE / _BIN / _WINDOW_SECONDS /
# _MAX_LOG_BYTES / _SETS / _CONFIG / _RUN_ENV) with a stub glacier-backup
# binary, entirely inside a temp directory. POSIX sh; runnable on macOS /bin/sh. Requires a `timeout`
# supporting `-s TERM` on PATH (GNU coreutils; the runner itself depends on
# the same thing on the UNAS, via busybox there).
#
# Exits non-zero if any assertion fails.

set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
RUNNER="$SCRIPT_DIR/glacier-backup-run.sh"

# Set names used by the default test fixture (passed via GLACIER_BACKUP_SETS).
SET_PHOTOS="photos"
SET_DOCUMENTS="documents"
SET_MUSIC="music"
DEFAULT_SETS="$SET_PHOTOS $SET_DOCUMENTS $SET_MUSIC"

GARBAGE_STATE_CONTENT="not-a-real-set"

# Window budgets. Small enough to keep the suite fast, large enough that
# "fast" stub runs (which do real fork/exec work) never race the deadline.
TINY_WINDOW_SECONDS=2
SLOW_SLEEP_SECONDS=4
AMPLE_WINDOW_SECONDS=60

TIMEOUT_EXIT_CODE=124
SIGKILL_EXIT_CODE=137
OOM_HINT_TEXT="SIGKILL — likely OOM-killed, check box memory"

STUB_BIN_NAME="glacier-backup-stub"
LOG_FILE_NAME="glacier-backup.log"
STATE_FILE_NAME="next-first-set"
CALLS_FILE_NAME="calls"

FAILURES=0

fail() {
  echo "FAIL: $1" >&2
  FAILURES=$((FAILURES + 1))
}

assert_eq() {
  # assert_eq <description> <actual> <expected>
  if [ "$2" != "$3" ]; then
    fail "$1: expected [$3], got [$2]"
  fi
}

assert_contains() {
  # assert_contains <description> <file> <needle>
  if ! grep -qF -- "$3" "$2"; then
    fail "$1: expected to find [$3] in $2"
    echo "----- $2 -----" >&2
    cat "$2" >&2
    echo "---------------" >&2
  fi
}

new_tmpdir() {
  mktemp -d "${TMPDIR:-/tmp}/glacier-backup-run-test.XXXXXX"
}

# write_stub_bin <path> <slow-set> <calls-file>
# Writes a stub glacier-backup binary that records the set it was invoked
# with (last argument; one per line, appended to <calls-file>) and, only when invoked with
# <slow-set>, sleeps past the test's tiny window before exiting 0.
write_stub_bin() {
  stub_path=$1
  slow_set=$2
  calls_file=$3
  cat > "$stub_path" <<EOF
#!/bin/sh
set_name=\$(for a in "\$@"; do :; done; echo "\$a")
echo "\$set_name" >> "$calls_file"
if [ "\$set_name" = "$slow_set" ]; then
  sleep $SLOW_SLEEP_SECONDS
fi
exit 0
EOF
  chmod +x "$stub_path"
}

# write_stub_bin_with_exit <path> <failing-set> <exit-code> <calls-file>
# Writes a stub glacier-backup binary that records the set it was invoked
# with (one per line, appended to <calls-file>) and exits with <exit-code>
# when invoked with <failing-set>, or 0 for any other set.
write_stub_bin_with_exit() {
  stub_path=$1
  failing_set=$2
  exit_code=$3
  calls_file=$4
  cat > "$stub_path" <<EOF
#!/bin/sh
set_name=\$(for a in "\$@"; do :; done; echo "\$a")
echo "\$set_name" >> "$calls_file"
if [ "\$set_name" = "$failing_set" ]; then
  exit $exit_code
fi
exit 0
EOF
  chmod +x "$stub_path"
}

# run_runner <log> <state> <bin> <window-seconds>
# Runs the runner with the given overrides. By default the set list is
# $DEFAULT_SETS via GLACIER_BACKUP_SETS and no config / run.env is read; set
# RUNNER_SETS / RUNNER_CONFIG / RUNNER_RUN_ENV before calling to change that
# (RUNNER_SETS="" means "unset", so discovery falls through to the config).
# Sets RUNNER_RC to its exit code
# without letting `set -e` abort the harness.
run_runner() {
  log=$1
  state=$2
  bin=$3
  window=$4
  RUNNER_RC=0
  GLACIER_BACKUP_LOG="$log" \
    GLACIER_BACKUP_STATE="$state" \
    GLACIER_BACKUP_BIN="$bin" \
    GLACIER_BACKUP_WINDOW_SECONDS="$window" \
    GLACIER_BACKUP_MAX_LOG_BYTES=52428800 \
    GLACIER_BACKUP_SETS="${RUNNER_SETS-$DEFAULT_SETS}" \
    GLACIER_BACKUP_CONFIG="${RUNNER_CONFIG-}" \
    GLACIER_BACKUP_RUN_ENV="${RUNNER_RUN_ENV-/nonexistent/run.env}" \
    sh "$RUNNER" >/dev/null 2>&1 || RUNNER_RC=$?
  # Some POSIX shells keep "VAR=x func" assignments after the call returns.
  unset RUNNER_SETS RUNNER_CONFIG RUNNER_RUN_ENV
}

test_state_advances_even_when_first_set_exhausts_window() {
  desc="state advances past an incomplete first set"
  tmp=$(new_tmpdir)
  log="$tmp/$LOG_FILE_NAME"
  state="$tmp/$STATE_FILE_NAME"
  bin="$tmp/$STUB_BIN_NAME"
  calls="$tmp/$CALLS_FILE_NAME"
  : > "$calls"
  # No state file at all: first falls back to $SET_PHOTOS.
  write_stub_bin "$bin" "$SET_PHOTOS" "$calls"

  run_runner "$log" "$state" "$bin" "$TINY_WINDOW_SECONDS"

  assert_eq "$desc: exit code" "$RUNNER_RC" "$TIMEOUT_EXIT_CODE"
  assert_eq "$desc: next state" "$(cat "$state")" "$SET_DOCUMENTS"
  assert_contains "$desc: skip log names every remaining set" "$log" \
    "window exhausted, skipping: $SET_DOCUMENTS $SET_MUSIC"

  rm -rf "$tmp"
}

test_cyclic_order_starts_at_state_and_advances_one() {
  desc="cyclic order from a mid-list state"
  tmp=$(new_tmpdir)
  log="$tmp/$LOG_FILE_NAME"
  state="$tmp/$STATE_FILE_NAME"
  bin="$tmp/$STUB_BIN_NAME"
  calls="$tmp/$CALLS_FILE_NAME"
  : > "$calls"
  echo "$SET_DOCUMENTS" > "$state"
  write_stub_bin "$bin" "" "$calls"

  run_runner "$log" "$state" "$bin" "$AMPLE_WINDOW_SECONDS"

  assert_eq "$desc: exit code" "$RUNNER_RC" "0"
  expected_calls=$(printf '%s\n%s\n%s' \
    "$SET_DOCUMENTS" "$SET_MUSIC" "$SET_PHOTOS")
  assert_eq "$desc: run order" "$(cat "$calls")" "$expected_calls"
  assert_eq "$desc: next state" "$(cat "$state")" "$SET_MUSIC"

  rm -rf "$tmp"
}

test_cyclic_order_wraps_after_last_set() {
  desc="cyclic order wraps past the last set"
  tmp=$(new_tmpdir)
  log="$tmp/$LOG_FILE_NAME"
  state="$tmp/$STATE_FILE_NAME"
  bin="$tmp/$STUB_BIN_NAME"
  calls="$tmp/$CALLS_FILE_NAME"
  : > "$calls"
  echo "$SET_MUSIC" > "$state"
  write_stub_bin "$bin" "" "$calls"

  run_runner "$log" "$state" "$bin" "$AMPLE_WINDOW_SECONDS"

  assert_eq "$desc: exit code" "$RUNNER_RC" "0"
  assert_eq "$desc: next state wraps to first set" "$(cat "$state")" "$SET_PHOTOS"

  rm -rf "$tmp"
}

test_missing_state_falls_back_to_first_set() {
  desc="missing state file falls back to the first set"
  tmp=$(new_tmpdir)
  log="$tmp/$LOG_FILE_NAME"
  state="$tmp/$STATE_FILE_NAME"
  bin="$tmp/$STUB_BIN_NAME"
  calls="$tmp/$CALLS_FILE_NAME"
  : > "$calls"
  # Deliberately do not create $state.
  write_stub_bin "$bin" "" "$calls"

  run_runner "$log" "$state" "$bin" "$AMPLE_WINDOW_SECONDS"

  assert_eq "$desc: exit code" "$RUNNER_RC" "0"
  assert_contains "$desc: run started at first set" "$log" "first set: $SET_PHOTOS"
  assert_eq "$desc: next state" "$(cat "$state")" "$SET_DOCUMENTS"

  rm -rf "$tmp"
}

test_garbage_state_falls_back_to_first_set() {
  desc="garbage state file falls back to the first set"
  tmp=$(new_tmpdir)
  log="$tmp/$LOG_FILE_NAME"
  state="$tmp/$STATE_FILE_NAME"
  bin="$tmp/$STUB_BIN_NAME"
  calls="$tmp/$CALLS_FILE_NAME"
  : > "$calls"
  echo "$GARBAGE_STATE_CONTENT" > "$state"
  write_stub_bin "$bin" "" "$calls"

  run_runner "$log" "$state" "$bin" "$AMPLE_WINDOW_SECONDS"

  assert_eq "$desc: exit code" "$RUNNER_RC" "0"
  assert_contains "$desc: run started at first set" "$log" "first set: $SET_PHOTOS"
  assert_eq "$desc: next state" "$(cat "$state")" "$SET_DOCUMENTS"

  rm -rf "$tmp"
}

test_sigkill_rc_logs_oom_hint() {
  desc="rc=137 logs an OOM hint"
  tmp=$(new_tmpdir)
  log="$tmp/$LOG_FILE_NAME"
  state="$tmp/$STATE_FILE_NAME"
  bin="$tmp/$STUB_BIN_NAME"
  calls="$tmp/$CALLS_FILE_NAME"
  : > "$calls"
  # No state file at all: first falls back to $SET_PHOTOS.
  write_stub_bin_with_exit "$bin" "$SET_PHOTOS" "$SIGKILL_EXIT_CODE" "$calls"

  run_runner "$log" "$state" "$bin" "$AMPLE_WINDOW_SECONDS"

  assert_eq "$desc: exit code" "$RUNNER_RC" "$SIGKILL_EXIT_CODE"
  assert_contains "$desc: rc line" "$log" "$SET_PHOTOS finished rc=$SIGKILL_EXIT_CODE"
  assert_contains "$desc: hint text" "$log" "$OOM_HINT_TEXT"

  rm -rf "$tmp"
}

# Writes a config file with block-style backupSets (plus decoys that the
# set-discovery parser must ignore) to <path>.
write_config() {
  cat > "$1" <<'CONFIG'
bucket: my-glacier-backup-bucket
region: eu-west-1
defaults:
  storageClass: DEEP_ARCHIVE
backupSets:
  # a comment line at set-name indentation must be ignored
  alpha:
    local: /srv/alpha
    prefix: alpha/
    exclude: ["**/.DS_Store"]
  "beta":
    local: /srv/beta
    prefix: beta/
  gamma:   # trailing comment
    local: /srv/gamma
    prefix: gamma/
other:
  delta:
    local: /srv/not-a-set
CONFIG
}

test_sets_are_discovered_from_config_in_file_order() {
  desc="sets come from backupSets keys in the config, in file order"
  tmp=$(new_tmpdir)
  log="$tmp/$LOG_FILE_NAME"
  state="$tmp/$STATE_FILE_NAME"
  bin="$tmp/$STUB_BIN_NAME"
  calls="$tmp/$CALLS_FILE_NAME"
  config="$tmp/config.yml"
  : > "$calls"
  write_config "$config"
  write_stub_bin "$bin" "" "$calls"

  RUNNER_SETS="" RUNNER_CONFIG="$config" \
    run_runner "$log" "$state" "$bin" "$AMPLE_WINDOW_SECONDS"

  assert_eq "$desc: exit code" "$RUNNER_RC" "0"
  assert_eq "$desc: run order" "$(cat "$calls")" "$(printf '%s\n%s\n%s' alpha beta gamma)"
  assert_eq "$desc: next state" "$(cat "$state")" "beta"

  rm -rf "$tmp"
}

test_sets_env_overrides_config() {
  desc="GLACIER_BACKUP_SETS takes priority over the config file"
  tmp=$(new_tmpdir)
  log="$tmp/$LOG_FILE_NAME"
  state="$tmp/$STATE_FILE_NAME"
  bin="$tmp/$STUB_BIN_NAME"
  calls="$tmp/$CALLS_FILE_NAME"
  config="$tmp/config.yml"
  : > "$calls"
  write_config "$config"
  write_stub_bin "$bin" "" "$calls"

  RUNNER_SETS="gamma alpha" RUNNER_CONFIG="$config" \
    run_runner "$log" "$state" "$bin" "$AMPLE_WINDOW_SECONDS"

  assert_eq "$desc: exit code" "$RUNNER_RC" "0"
  assert_eq "$desc: run order" "$(cat "$calls")" "$(printf '%s\n%s' gamma alpha)"

  rm -rf "$tmp"
}

test_run_env_file_can_supply_sets() {
  desc="run.env is sourced and can set GLACIER_BACKUP_SETS"
  tmp=$(new_tmpdir)
  log="$tmp/$LOG_FILE_NAME"
  state="$tmp/$STATE_FILE_NAME"
  bin="$tmp/$STUB_BIN_NAME"
  calls="$tmp/$CALLS_FILE_NAME"
  run_env="$tmp/run.env"
  : > "$calls"
  echo 'GLACIER_BACKUP_SETS="from-env-file other-set"' > "$run_env"
  write_stub_bin "$bin" "" "$calls"

  RUNNER_SETS="" RUNNER_RUN_ENV="$run_env" \
    run_runner "$log" "$state" "$bin" "$AMPLE_WINDOW_SECONDS"

  assert_eq "$desc: exit code" "$RUNNER_RC" "0"
  assert_eq "$desc: run order" "$(cat "$calls")" "$(printf '%s\n%s' from-env-file other-set)"

  rm -rf "$tmp"
}

test_run_env_variables_reach_the_binary_without_export() {
  desc="run.env assignments are exported to the binary (e.g. GLACIER_BACKUP_XMX)"
  tmp=$(new_tmpdir)
  log="$tmp/$LOG_FILE_NAME"
  state="$tmp/$STATE_FILE_NAME"
  bin="$tmp/$STUB_BIN_NAME"
  seen="$tmp/seen-xmx"
  run_env="$tmp/run.env"
  echo 'GLACIER_BACKUP_XMX=2g' > "$run_env"
  printf '#!/bin/sh\necho "${GLACIER_BACKUP_XMX:-unset}" > "%s"\nexit 0\n' "$seen" > "$bin"
  chmod +x "$bin"

  RUNNER_SETS="only-set" RUNNER_RUN_ENV="$run_env" \
    run_runner "$log" "$state" "$bin" "$AMPLE_WINDOW_SECONDS"

  assert_eq "$desc: exit code" "$RUNNER_RC" "0"
  assert_eq "$desc: value seen by binary" "$(cat "$seen")" "2g"

  rm -rf "$tmp"
}

test_no_sets_anywhere_fails_with_usage_error() {
  desc="no sets in env or config exits 2 without running anything"
  tmp=$(new_tmpdir)
  log="$tmp/$LOG_FILE_NAME"
  state="$tmp/$STATE_FILE_NAME"
  bin="$tmp/$STUB_BIN_NAME"
  calls="$tmp/$CALLS_FILE_NAME"
  : > "$calls"
  write_stub_bin "$bin" "" "$calls"

  RUNNER_SETS="" RUNNER_CONFIG="$tmp/missing.yml" \
    run_runner "$log" "$state" "$bin" "$AMPLE_WINDOW_SECONDS"

  assert_eq "$desc: exit code" "$RUNNER_RC" "2"
  assert_eq "$desc: nothing ran" "$(cat "$calls")" ""

  rm -rf "$tmp"
}

test_explicit_config_is_passed_to_the_binary() {
  desc="an explicit GLACIER_BACKUP_CONFIG is passed as --config"
  tmp=$(new_tmpdir)
  log="$tmp/$LOG_FILE_NAME"
  state="$tmp/$STATE_FILE_NAME"
  bin="$tmp/$STUB_BIN_NAME"
  args="$tmp/args"
  config="$tmp/config.yml"
  write_config "$config"
  cat > "$bin" <<STUB
#!/bin/sh
echo "\$*" >> "$args"
exit 0
STUB
  chmod +x "$bin"

  RUNNER_SETS="alpha" RUNNER_CONFIG="$config" \
    run_runner "$log" "$state" "$bin" "$AMPLE_WINDOW_SECONDS"

  assert_eq "$desc: exit code" "$RUNNER_RC" "0"
  assert_eq "$desc: argv" "$(cat "$args")" "--config $config sync alpha"

  rm -rf "$tmp"
}

test_state_advances_even_when_first_set_exhausts_window
test_cyclic_order_starts_at_state_and_advances_one
test_cyclic_order_wraps_after_last_set
test_missing_state_falls_back_to_first_set
test_garbage_state_falls_back_to_first_set
test_sigkill_rc_logs_oom_hint
test_sets_are_discovered_from_config_in_file_order
test_sets_env_overrides_config
test_run_env_file_can_supply_sets
test_run_env_variables_reach_the_binary_without_export
test_no_sets_anywhere_fails_with_usage_error
test_explicit_config_is_passed_to_the_binary

if [ "$FAILURES" -gt 0 ]; then
  echo "$FAILURES assertion(s) failed" >&2
  exit 1
fi

echo "all assertions passed"
