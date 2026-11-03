#!/usr/bin/env bash
# Renames files/directories on a remote host (e.g. a NAS) whose names are not
# valid UTF-8, converting them from a legacy encoding (default: latin-1) to
# UTF-8 in place. Names that are already valid UTF-8 are never touched.
#
# Why: glacier-backup cannot map non-UTF-8 names to S3 keys faithfully and
# skips or sanitizes them; fixing the names at the source avoids that.
#
# Dry-run by default; pass --apply to actually rename.
#
# Usage:
#   fix-filename-encoding.sh [--apply] [--from ENCODING] ROOT [ROOT...]
#
#   ROOT             a directory on the remote host to scan recursively
#                    (absolute path); can be given several times. Roots may
#                    also be supplied, space-separated, via the ROOTS env var.
#   --apply          rename for real (default: only print what would change)
#   --from ENCODING  source encoding, as understood by iconv (default: LATIN1;
#                    env FROM does the same). Example: CP1252
#   -h, --help       show this help
#
# Environment:
#   UNAS_HOST  ssh host (default: unas)
#   UNAS_USER  ssh user (default: root)
#
# Examples:
#   ./scripts/fix-filename-encoding.sh /volume/<volume-uuid>/.srv/.unifi-drive/Photos
#   ./scripts/fix-filename-encoding.sh --apply /srv/media /srv/documents
#   FROM=CP1252 ./scripts/fix-filename-encoding.sh /srv/media
set -euo pipefail

usage() {
  sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
}

UNAS_HOST=${UNAS_HOST:-unas}
UNAS_USER=${UNAS_USER:-root}
FROM=${FROM:-LATIN1}
APPLY=0
ROOTS=${ROOTS:-}

while [ $# -gt 0 ]; do
  case "$1" in
    --apply) APPLY=1 ;;
    --from)
      [ $# -ge 2 ] || { echo "error: --from needs an argument" >&2; exit 2; }
      FROM=$2
      shift
      ;;
    -h|--help) usage; exit 0 ;;
    --) shift; break ;;
    -*) echo "error: unknown option: $1" >&2; echo >&2; usage >&2; exit 2 ;;
    *) ROOTS="$ROOTS $1" ;;
  esac
  shift
done
# Anything after -- is a root.
for arg in "$@"; do ROOTS="$ROOTS $arg"; done

ROOTS=${ROOTS# }
if [ -z "${ROOTS// /}" ]; then
  echo "error: at least one ROOT directory is required" >&2
  echo >&2
  usage >&2
  exit 2
fi
# Roots are passed through a remote shell word list: reject whitespace and
# shell metacharacters inside a single root rather than mis-quoting them.
for root in $ROOTS; do
  case "$root" in
    *[!A-Za-z0-9_./@+,:=#%-]*) echo "error: unsupported character in root (letters, digits and _./@+,:=#%- only): $root" >&2; exit 2 ;;
  esac
done
case "$FROM" in
  *[!A-Za-z0-9_.:-]*) echo "error: invalid encoding name: $FROM" >&2; exit 2 ;;
esac

ssh "$UNAS_USER@$UNAS_HOST" "APPLY=$APPLY FROM=$FROM ROOTS='$ROOTS' bash -s" <<'REMOTE'
set -u
# C locale so the [![:ascii:]] glob below sees raw high bytes.
export LC_ALL=C
# -depth: rename children before their (possibly also misnamed) parents.
for root in $ROOTS; do
  [ -d "$root" ] || { echo "missing root, skipping: $root"; continue; }
  echo "== scanning: $root"
  count=0
  find "$root" -depth -print0 | while IFS= read -r -d '' path; do
    count=$((count + 1))
    if [ $((count % 100000)) -eq 0 ]; then echo "   ... $count entries scanned"; fi
    base=${path##*/}
    # Fast path: pure-ASCII names are always valid UTF-8 — no process fork.
    case "$base" in
      *[![:ascii:]]*) ;;
      *) continue ;;
    esac
    dir=${path%/*}
    # Valid UTF-8 names pass through iconv unchanged; invalid ones error out.
    if printf '%s' "$base" | iconv -f UTF-8 -t UTF-8 >/dev/null 2>&1; then
      continue
    fi
    newbase=$(printf '%s' "$base" | iconv -f "$FROM" -t UTF-8) || {
      echo "SKIP (cannot convert from $FROM): $path"
      continue
    }
    target="$dir/$newbase"
    if [ -e "$target" ]; then
      echo "SKIP (target exists): $path -> $newbase"
      continue
    fi
    if [ "$APPLY" = "1" ]; then
      mv -- "$path" "$target" && echo "RENAMED: $path -> $newbase"
    else
      echo "WOULD RENAME: $path -> $newbase"
    fi
  done
  echo "== done: $root"
done
REMOTE

if [ "$APPLY" = "0" ]; then
  echo
  echo "Dry-run only. Re-run with --apply to perform the renames."
fi
