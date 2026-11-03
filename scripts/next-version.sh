#!/usr/bin/env bash
# Computes the next release version from the existing git tags plus the bump
# keywords found in PR / commit text and PR labels.
#
# Usage:
#   scripts/next-version.sh [--base VER | --tags-file FILE]
#                           [--text-file FILE]... [--label NAME]...
#                           [--labels-file FILE] [--bump KIND]
#                           [--fallback-base VER]
#
#   --base VER           Use VER (X.Y.Z, optional leading "v") as the base
#                        version; no tag lookup is done.
#   --tags-file FILE     Read candidate tags (one per line) from FILE instead
#                        of running `git tag --list`.
#   --text-file FILE     Text to scan for bump keywords (PR title, PR body,
#                        commit messages). May be given several times; "-"
#                        reads stdin. Stdin is NOT read unless "-" is given.
#   --label NAME         A PR label (may be repeated).
#   --labels-file FILE   PR labels, one per line.
#   --bump KIND          Explicit override: major|minor|patch|none. "auto" or
#                        an empty value means "derive from keywords".
#   --fallback-base VER  Base used when no semver tag exists. Defaults to
#                        $NEXT_VERSION_FALLBACK_BASE, else FALLBACK_BASE below.
#
# Output (stdout, one key=value per line, suitable for >> "$GITHUB_OUTPUT"):
#   bump=major|minor|patch|none
#   base=X.Y.Z          version the bump was applied to
#   version=X.Y.Z       next version; EMPTY when bump=none
#   release=true|false  false only when bump=none
#   reason=...          override | default | keywords:<list>
# A human-readable explanation goes to stderr.
#
# Bump rules
#   * Keywords #major, #minor, #patch, #none (case-insensitive) are searched in
#     the supplied text. A keyword must stand alone: "#majority", "foo#major"
#     and "#major-ish" are NOT keywords. HTML comments (<!-- ... -->) are
#     ignored, so template hints never count.
#   * Labels count as keywords when the whole label is one of: major, minor,
#     patch, none, #major..., or bump<sep>major... where <sep> is ":", "/",
#     "-" or whitespace (e.g. "bump:major", "bump: patch", "bump-none").
#   * Precedence: major > minor > patch. If any of those is present, the
#     highest wins and #none is ignored. #none alone means "no release".
#   * With no keyword at all the default bump is MINOR.
#   * --bump (e.g. from workflow_dispatch) overrides all of the above.
#
# Base version
#   Highest tag matching ^v[0-9]+\.[0-9]+\.[0-9]+$ by numeric semver order
#   (v0.10.0 > v0.9.0). Tags such as v1.2.3-rc1 or foo are ignored. With no
#   such tag the fallback base (the last release from before the project moved to GitHub) is used.

set -euo pipefail

# Last release made on GitLab; the first GitHub release is its next bump.
FALLBACK_BASE="${NEXT_VERSION_FALLBACK_BASE:-0.1.18}"

SEMVER_RE='^[0-9]+\.[0-9]+\.[0-9]+$'
TAG_RE='^v[0-9]+\.[0-9]+\.[0-9]+$'

die() {
  echo "next-version.sh: $*" >&2
  exit 2
}

usage() {
  sed -n '2,/^$/{/^$/d;s/^# \{0,1\}//;p}' "${BASH_SOURCE[0]}"
}

# normalize_version VER -> prints X.Y.Z (strips a leading "v"), or fails.
normalize_version() {
  local v="${1#v}"
  [[ $v =~ $SEMVER_RE ]] || return 1
  printf '%s\n' "$v"
}

# highest_semver_tag: reads tags on stdin, prints the highest X.Y.Z (no "v").
highest_semver_tag() {
  local line
  while IFS= read -r line || [[ -n $line ]]; do
    line="${line%$'\r'}"
    if [[ $line =~ $TAG_RE ]]; then printf '%s\n' "${line#v}"; fi
  done | sort -t. -k1,1n -k2,2n -k3,3n | tail -n 1
}

# strip_html_comments: removes <!-- ... --> (possibly multi-line) from $1.
# An unterminated comment swallows the rest of the text.
strip_html_comments() {
  local text="$1" out="" head
  while [[ $text == *'<!--'* ]]; do
    head="${text%%'<!--'*}"
    out+="$head"
    text="${text#*'<!--'}"
    if [[ $text == *'-->'* ]]; then
      text="${text#*'-->'}"
    else
      text=""
    fi
  done
  printf '%s%s' "$out" "$text"
}

# text_has_keyword TEXT KW -> 0 if "#KW" occurs as a standalone keyword.
# Here-string (not a pipe) so `grep -q` closing early cannot SIGPIPE a writer.
text_has_keyword() {
  grep -qiE "(^|[^[:alnum:]_])#${2}(\$|[^[:alnum:]_-])" <<<"$1"
}

# label_keyword LABEL -> prints major|minor|patch|none if the label is a bump
# label, else prints nothing.
label_keyword() {
  local l
  l="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  if [[ $l =~ ^(#|bump[[:space:]]*[:/-][[:space:]]*|bump[[:space:]]+)?(major|minor|patch|none)$ ]]; then
    printf '%s\n' "${BASH_REMATCH[2]}"
  fi
}

# apply_bump BASE KIND -> prints next X.Y.Z
apply_bump() {
  local major minor patch
  IFS=. read -r major minor patch <<<"$1"
  # 10# forces base ten so tags like v0.08.0 are not parsed as octal.
  major=$((10#$major))
  minor=$((10#$minor))
  patch=$((10#$patch))
  case "$2" in
    major) printf '%d.0.0\n' $((major + 1)) ;;
    minor) printf '%d.%d.0\n' "$major" $((minor + 1)) ;;
    patch) printf '%d.%d.%d\n' "$major" "$minor" $((patch + 1)) ;;
    *) return 1 ;;
  esac
}

main() {
  local base_arg="" tags_file="" override="" fallback="$FALLBACK_BASE"
  local -a text_files=() labels=()

  while (($#)); do
    case "$1" in
      --base) (($# >= 2)) || die "--base needs a value"; base_arg="$2"; shift 2 ;;
      --tags-file) (($# >= 2)) || die "--tags-file needs a value"; tags_file="$2"; shift 2 ;;
      --text-file) (($# >= 2)) || die "--text-file needs a value"; text_files+=("$2"); shift 2 ;;
      --label) (($# >= 2)) || die "--label needs a value"; labels+=("$2"); shift 2 ;;
      --labels-file)
        (($# >= 2)) || die "--labels-file needs a value"
        [[ -r $2 ]] || die "cannot read labels file: $2"
        local line
        while IFS= read -r line || [[ -n $line ]]; do
          line="${line%$'\r'}"
          if [[ -n $line ]]; then labels+=("$line"); fi
        done <"$2"
        shift 2
        ;;
      --bump) (($# >= 2)) || die "--bump needs a value"; override="$2"; shift 2 ;;
      --fallback-base) (($# >= 2)) || die "--fallback-base needs a value"; fallback="$2"; shift 2 ;;
      -h | --help) usage; exit 0 ;;
      *) die "unknown argument: $1" ;;
    esac
  done

  # ---- base version -------------------------------------------------------
  local base="" base_source=""
  fallback="$(normalize_version "$fallback")" || die "invalid fallback base: $fallback"
  if [[ -n $base_arg ]]; then
    base="$(normalize_version "$base_arg")" || die "invalid --base (want X.Y.Z): $base_arg"
    base_source="--base"
  else
    if [[ -n $tags_file ]]; then
      [[ -r $tags_file ]] || die "cannot read tags file: $tags_file"
      base="$(highest_semver_tag <"$tags_file")"
    else
      base="$(git tag --list 2>/dev/null | highest_semver_tag || true)"
    fi
    if [[ -n $base ]]; then
      base_source="highest tag"
    else
      base="$fallback"
      base_source="no semver tag, fallback"
    fi
  fi

  # ---- keywords -----------------------------------------------------------
  local text="" f chunk
  for f in "${text_files[@]+"${text_files[@]}"}"; do
    if [[ $f == - ]]; then
      chunk="$(cat)"
    else
      [[ -r $f ]] || die "cannot read text file: $f"
      chunk="$(cat "$f")"
    fi
    text+="${chunk}"$'\n'
  done
  text="$(strip_html_comments "$text")"

  local kw lbl
  local -A found=()
  for kw in major minor patch none; do
    if text_has_keyword "$text" "$kw"; then
      found[$kw]=1
    fi
  done
  for lbl in "${labels[@]+"${labels[@]}"}"; do
    kw="$(label_keyword "$lbl")"
    if [[ -n $kw ]]; then found[$kw]=1; fi
  done

  # ---- decide -------------------------------------------------------------
  local bump reason found_list=""
  for kw in major minor patch none; do
    if [[ -n ${found[$kw]:-} ]]; then found_list+="${found_list:+,}$kw"; fi
  done

  override="$(printf '%s' "$override" | tr '[:upper:]' '[:lower:]')"
  case "$override" in
    "" | auto)
      if [[ -n ${found[major]:-} ]]; then bump="major"
      elif [[ -n ${found[minor]:-} ]]; then bump="minor"
      elif [[ -n ${found[patch]:-} ]]; then bump="patch"
      elif [[ -n ${found[none]:-} ]]; then bump=none
      else bump="minor"
      fi
      if [[ -n $found_list ]]; then reason="keywords:$found_list"; else reason="default"; fi
      ;;
    major | minor | patch | none)
      bump="$override"
      reason="override"
      ;;
    *) die "invalid --bump (want auto|major|minor|patch|none): $override" ;;
  esac

  local version="" release=true
  if [[ $bump == none ]]; then
    release=false
  else
    version="$(apply_bump "$base" "$bump")"
  fi

  echo "next-version: base $base ($base_source), bump $bump ($reason)${version:+ -> $version}" >&2
  printf 'bump=%s\n' "$bump"
  printf 'base=%s\n' "$base"
  printf 'version=%s\n' "$version"
  printf 'release=%s\n' "$release"
  printf 'reason=%s\n' "$reason"
}

main "$@"
