#!/usr/bin/env bash
# Tests for scripts/next-version.sh. Plain bash, no framework.
# Usage: bash scripts/next-version.test.sh   (exits non-zero on any failure)

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NV="$SCRIPT_DIR/next-version.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

PASS=0
FAIL=0

check() { # name expected actual
  if [[ $2 == "$3" ]]; then
    PASS=$((PASS + 1))
  else
    FAIL=$((FAIL + 1))
    printf 'FAIL: %s\n  expected: %q\n  actual:   %q\n' "$1" "$2" "$3" >&2
  fi
}

# out KEY -> value of KEY= from $OUTPUT
out() { sed -n "s/^$1=//p" <<<"$OUTPUT" | head -n 1; }

# run_nv ARGS... : runs the script, sets OUTPUT (stdout only) and RC.
run_nv() {
  RC=0
  OUTPUT="$("$NV" "$@" 2>/dev/null)" || RC=$?
}

# expect NAME BUMP VERSION ARGS... : asserts bump/version for a run.
expect() {
  local name="$1" bump="$2" version="$3"
  shift 3
  run_nv "$@"
  check "$name: exit code" 0 "$RC"
  check "$name: bump" "$bump" "$(out bump)"
  check "$name: version" "$version" "$(out version)"
}

# Text helper: writes $1 to a fresh file and prints its path.
n=0
textfile() {
  n=$((n + 1))
  printf '%s' "$1" >"$TMP/text$n"
  printf '%s' "$TMP/text$n"
}

tagsfile() { # tags... -> path
  n=$((n + 1))
  : >"$TMP/tags$n"
  local t
  for t in "$@"; do printf '%s\n' "$t" >>"$TMP/tags$n"; done
  printf '%s' "$TMP/tags$n"
}

B=(--base 1.2.3)

# ---- defaults ----------------------------------------------------------------
expect "no text -> default minor" minor 1.3.0 "${B[@]}"
expect "text without keywords -> minor" minor 1.3.0 "${B[@]}" --text-file "$(textfile 'Fix a thing, see #123')"
expect "empty text file -> minor" minor 1.3.0 "${B[@]}" --text-file "$(textfile '')"

# ---- each keyword ------------------------------------------------------------
expect "#major" major 2.0.0 "${B[@]}" --text-file "$(textfile 'Rework CLI #major')"
expect "#minor" minor 1.3.0 "${B[@]}" --text-file "$(textfile '#minor')"
expect "#patch" patch 1.2.4 "${B[@]}" --text-file "$(textfile 'tiny fix #patch')"
expect "#none alone" none "" "${B[@]}" --text-file "$(textfile 'docs only #none')"
run_nv "${B[@]}" --text-file "$(textfile '#none')"
check "#none: release=false" false "$(out release)"
check "#none: version empty" "" "$(out version)"
run_nv "${B[@]}" --text-file "$(textfile '#patch')"
check "release=true" true "$(out release)"
check "base echoed" 1.2.3 "$(out base)"

# ---- case insensitivity ------------------------------------------------------
expect "#MAJOR" major 2.0.0 "${B[@]}" --text-file "$(textfile '#MAJOR')"
expect "#Patch" patch 1.2.4 "${B[@]}" --text-file "$(textfile '#Patch')"
expect "#NoNe" none "" "${B[@]}" --text-file "$(textfile '#NoNe')"

# ---- position / punctuation --------------------------------------------------
expect "keyword mid-sentence" patch 1.2.4 "${B[@]}" --text-file "$(textfile 'a b #patch c d')"
expect "keyword in parens" major 2.0.0 "${B[@]}" --text-file "$(textfile 'breaking (#major).')"
expect "keyword at end, no newline" patch 1.2.4 "${B[@]}" --text-file "$(textfile 'x
y #patch')"
expect "keyword on later line" major 2.0.0 "${B[@]}" --text-file "$(textfile 'title

body line
#major')"
expect "keyword with trailing comma" patch 1.2.4 "${B[@]}" --text-file "$(textfile '#patch, thanks')"

# ---- things that must NOT match ----------------------------------------------
expect "#majority is not a keyword" minor 1.3.0 "${B[@]}" --text-file "$(textfile 'the #majority agrees')"
expect "#patches is not a keyword" minor 1.3.0 "${B[@]}" --text-file "$(textfile 'apply #patches')"
expect "foo#major (url anchor) ignored" minor 1.3.0 "${B[@]}" --text-file "$(textfile 'see http://x/y#major')"
expect "#major-ish ignored" minor 1.3.0 "${B[@]}" --text-file "$(textfile '#major-ish')"
expect "#none_of ignored" minor 1.3.0 "${B[@]}" --text-file "$(textfile '#none_of_these')"
expect "major without hash ignored" minor 1.3.0 "${B[@]}" --text-file "$(textfile 'major patch none')"
expect "issue refs ignored" minor 1.3.0 "${B[@]}" --text-file "$(textfile 'fixes #1 and #22')"

# ---- HTML comments are ignored -----------------------------------------------
expect "keyword in HTML comment ignored" minor 1.3.0 "${B[@]}" --text-file "$(textfile 'body <!-- use #major for breaking -->')"
expect "multi-line HTML comment ignored" minor 1.3.0 "${B[@]}" --text-file "$(textfile 'a
<!--
#major
#patch
-->
b')"
expect "keyword outside comment still found" patch 1.2.4 "${B[@]}" --text-file "$(textfile '<!-- #major --> real #patch')"
expect "two comments, keyword between" patch 1.2.4 "${B[@]}" --text-file "$(textfile '<!-- #major --> #patch <!-- #minor -->')"
expect "unterminated comment swallows rest" minor 1.3.0 "${B[@]}" --text-file "$(textfile 'x <!-- #major')"

# ---- precedence --------------------------------------------------------------
expect "major beats minor" major 2.0.0 "${B[@]}" --text-file "$(textfile '#minor #major')"
expect "major beats patch" major 2.0.0 "${B[@]}" --text-file "$(textfile '#patch #major')"
expect "minor beats patch" minor 1.3.0 "${B[@]}" --text-file "$(textfile '#patch #minor')"
expect "all three -> major" major 2.0.0 "${B[@]}" --text-file "$(textfile '#patch #minor #major')"
expect "#none + #patch -> patch" patch 1.2.4 "${B[@]}" --text-file "$(textfile '#none #patch')"
expect "#none + #minor -> minor" minor 1.3.0 "${B[@]}" --text-file "$(textfile '#none #minor')"
expect "#none + #major -> major" major 2.0.0 "${B[@]}" --text-file "$(textfile '#major #none')"
expect "#none twice -> none" none "" "${B[@]}" --text-file "$(textfile '#none #none')"

# ---- multiple sources --------------------------------------------------------
expect "keywords across several text files" major 2.0.0 "${B[@]}" \
  --text-file "$(textfile 'title #patch')" --text-file "$(textfile 'body')" --text-file "$(textfile 'commit #major')"
expect "keyword only in one of many files (none)" none "" "${B[@]}" \
  --text-file "$(textfile 'title')" --text-file "$(textfile 'commit #none')"
expect "stdin via --text-file -" patch 1.2.4 "${B[@]}" --text-file - <<<'from stdin #patch'
expect "stdin not read unless requested" minor 1.3.0 "${B[@]}" <<<'#major'
expect "stdin plus file" major 2.0.0 "${B[@]}" --text-file "$(textfile '#patch')" --text-file - <<<'#major'
# A keyword split across two files (title ends '#ma', body starts 'jor') must not match.
expect "files are separated by newline" minor 1.3.0 "${B[@]}" --text-file "$(textfile '#ma')" --text-file "$(textfile 'jor')"

# ---- labels ------------------------------------------------------------------
expect "label major" major 2.0.0 "${B[@]}" --label major
expect "label minor" minor 1.3.0 "${B[@]}" --label minor
expect "label patch" patch 1.2.4 "${B[@]}" --label patch
expect "label none" none "" "${B[@]}" --label none
expect "label bump:major" major 2.0.0 "${B[@]}" --label bump:major
expect "label bump:patch" patch 1.2.4 "${B[@]}" --label bump:patch
expect "label bump: none" none "" "${B[@]}" --label "bump: none"
expect "label bump/minor" minor 1.3.0 "${B[@]}" --label bump/minor
expect "label bump-patch" patch 1.2.4 "${B[@]}" --label bump-patch
expect "label 'bump major'" major 2.0.0 "${B[@]}" --label "bump major"
expect "label #major" major 2.0.0 "${B[@]}" --label '#major'
expect "label MAJOR (case)" major 2.0.0 "${B[@]}" --label MAJOR
expect "label with padding" patch 1.2.4 "${B[@]}" --label "  Bump:Patch  "
expect "unrelated labels ignored" minor 1.3.0 "${B[@]}" --label bug --label documentation --label "majority" --label "bump:huge" --label "not-major"
expect "label + text: highest wins" major 2.0.0 "${B[@]}" --label patch --text-file "$(textfile '#major')"
expect "label none + text patch" patch 1.2.4 "${B[@]}" --label none --text-file "$(textfile '#patch')"
expect "label none + label patch" patch 1.2.4 "${B[@]}" --label none --label patch
expect "label patch + text minor" minor 1.3.0 "${B[@]}" --label patch --text-file "$(textfile '#minor')"
LF="$TMP/labels"
printf 'bug\r\nbump:major\r\n\r\n' >"$LF"
expect "labels-file (CRLF, blank lines)" major 2.0.0 "${B[@]}" --labels-file "$LF"
: >"$LF"
expect "empty labels-file" minor 1.3.0 "${B[@]}" --labels-file "$LF"

# ---- explicit override -------------------------------------------------------
expect "override major beats text" major 2.0.0 "${B[@]}" --bump major --text-file "$(textfile '#patch')"
expect "override patch beats #major" patch 1.2.4 "${B[@]}" --bump patch --text-file "$(textfile '#major')"
expect "override none" none "" "${B[@]}" --bump none --text-file "$(textfile '#major')"
expect "override auto = derive" patch 1.2.4 "${B[@]}" --bump auto --text-file "$(textfile '#patch')"
expect "override empty = derive" patch 1.2.4 "${B[@]}" --bump "" --text-file "$(textfile '#patch')"
expect "override case-insensitive" minor 1.3.0 "${B[@]}" --bump MINOR
run_nv "${B[@]}" --bump major
check "override reason" override "$(out reason)"
run_nv "${B[@]}"
check "default reason" default "$(out reason)"
run_nv "${B[@]}" --text-file "$(textfile '#patch #none')"
check "keywords reason" "keywords:patch,none" "$(out reason)"

# ---- bump arithmetic ---------------------------------------------------------
expect "major resets minor+patch" major 5.0.0 --base 4.9.9 --text-file "$(textfile '#major')"
expect "minor resets patch" minor 4.10.0 --base 4.9.9
expect "patch increments patch" patch 4.9.10 --base 4.9.9 --label patch
expect "multi-digit components" minor 10.200.0 --base 10.199.300
expect "base with leading v" minor 1.3.0 --base v1.2.3
expect "leading-zero component is decimal" patch 0.8.10 --base 0.8.9 --label patch
expect "leading-zero 08 not octal" minor 0.9.0 --base 0.08.0
expect "0.1.18 -> 0.2.0 (first GitHub release)" minor 0.2.0 --base 0.1.18

# ---- base selection from tags ------------------------------------------------
expect "no tags -> fallback 0.1.18 -> v0.2.0" minor 0.2.0 --tags-file "$(tagsfile)"
run_nv --tags-file "$(tagsfile)"
check "fallback base" 0.1.18 "$(out base)"
expect "semver sort: v0.10.0 > v0.9.0" minor 0.11.0 --tags-file "$(tagsfile v0.9.0 v0.10.0 v0.2.0)"
expect "semver sort: v0.10.0 > v0.9.0 (reverse order)" minor 0.11.0 --tags-file "$(tagsfile v0.10.0 v0.9.0)"
expect "semver sort: v1.9.0 < v1.10.0" patch 1.10.1 --tags-file "$(tagsfile v1.10.0 v1.9.9 v1.2.100)" --label patch
expect "semver sort: patch numeric" patch 1.0.11 --tags-file "$(tagsfile v1.0.9 v1.0.10)" --label patch
expect "major dominates" minor 2.1.0 --tags-file "$(tagsfile v1.99.99 v2.0.0 v0.50.0)"
expect "ignores v1.2.3-rc1" minor 0.4.0 --tags-file "$(tagsfile v0.3.0 v1.2.3-rc1)"
expect "ignores foo" minor 0.4.0 --tags-file "$(tagsfile v0.3.0 foo latest)"
expect "ignores tags without v" minor 0.4.0 --tags-file "$(tagsfile v0.3.0 9.9.9)"
expect "ignores v1.2" minor 0.4.0 --tags-file "$(tagsfile v0.3.0 v1.2 v1)"
expect "ignores v1.2.3.4" minor 0.4.0 --tags-file "$(tagsfile v0.3.0 v1.2.3.4)"
expect "ignores v1.2.3+build" minor 0.4.0 --tags-file "$(tagsfile v0.3.0 v1.2.3+build)"
expect "only non-semver tags -> fallback" minor 0.2.0 --tags-file "$(tagsfile foo v1.2.3-rc1 release-1)"
expect "tags with CRLF" minor 0.4.0 --tags-file "$(printf 'v0.3.0\r\n' >"$TMP/crlf"; printf '%s' "$TMP/crlf")"
expect "--base wins over tags" minor 1.3.0 --base 1.2.3 --tags-file "$(tagsfile v9.9.9)"
expect "tags + major" major 1.0.0 --tags-file "$(tagsfile v0.9.5)" --label major

# ---- fallback override -------------------------------------------------------
expect "--fallback-base" minor 3.5.0 --fallback-base 3.4.0 --tags-file "$(tagsfile)"
RC=0
OUTPUT="$(NEXT_VERSION_FALLBACK_BASE=7.7.7 "$NV" --tags-file "$(tagsfile)" 2>/dev/null)" || RC=$?
check "env fallback exit" 0 "$RC"
check "env fallback version" 7.8.0 "$(out version)"
expect "fallback ignored when tag exists" minor 0.4.0 --fallback-base 9.9.9 --tags-file "$(tagsfile v0.3.0)"

# ---- real git repository (default tag lookup) -------------------------------
REPO="$TMP/repo"
mkdir "$REPO"
(
  cd "$REPO"
  git init -q .
  git config user.email t@example.com
  git config user.name t
  git config commit.gpgsign false
  git config tag.gpgsign false
  git commit -q --allow-empty -m init
)
run_nv_in_repo() {
  RC=0
  OUTPUT="$(cd "$REPO" && "$NV" 2>/dev/null)" || RC=$?
}
run_nv_in_repo
check "git: no tags exit" 0 "$RC"
check "git: no tags -> fallback" 0.2.0 "$(out version)"
(cd "$REPO" && git tag v0.9.0 && git tag v0.10.0 && git tag v1.0.0-rc1 && git tag foo && git tag -a v0.2.5 -m annotated)
run_nv_in_repo
check "git: highest semver tag" 0.11.0 "$(out version)"
check "git: base" 0.10.0 "$(out base)"
# Outside a git repo and without --base/--tags-file: falls back, does not crash.
RC=0
OUTPUT="$(cd "$TMP" && GIT_CEILING_DIRECTORIES="$TMP" "$NV" 2>/dev/null)" || RC=$?
check "not a repo: exit" 0 "$RC"
check "not a repo: fallback" 0.2.0 "$(out version)"

# ---- error handling ----------------------------------------------------------
run_nv --base banana
check "invalid --base exit" 2 "$RC"
run_nv --base 1.2
check "short --base exit" 2 "$RC"
run_nv --base 1.2.3-rc1
check "prerelease --base exit" 2 "$RC"
run_nv "${B[@]}" --bump huge
check "invalid --bump exit" 2 "$RC"
run_nv "${B[@]}" --text-file "$TMP/does-not-exist"
check "missing text file exit" 2 "$RC"
run_nv "${B[@]}" --labels-file "$TMP/does-not-exist"
check "missing labels file exit" 2 "$RC"
run_nv --tags-file "$TMP/does-not-exist"
check "missing tags file exit" 2 "$RC"
run_nv --base
check "missing value exit" 2 "$RC"
run_nv --wat
check "unknown flag exit" 2 "$RC"
run_nv --fallback-base nope --tags-file "$(tagsfile)"
check "invalid fallback exit" 2 "$RC"
RC=0
"$NV" --help >/dev/null 2>&1 || RC=$?
check "--help exit" 0 "$RC"

# ---- output format (usable in $GITHUB_OUTPUT) --------------------------------
run_nv "${B[@]}" --label major
check "output lines" "bump=major
base=1.2.3
version=2.0.0
release=true
reason=keywords:major" "$OUTPUT"
# Hostile text must never leak into the output stream.
run_nv "${B[@]}" --text-file "$(textfile $'#patch\n::set-output name=x::y\nversion=666.0.0')"
check "hostile text: version unaffected" 1.2.4 "$(out version)"
check "hostile text: line count" 5 "$(wc -l <<<"$OUTPUT" | tr -d ' ')"

echo
echo "next-version tests: $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]]
