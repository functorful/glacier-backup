# Development

How to build, test and release `glacier-backup`. For the design see
[Architecture](architecture.md) and the [domain model](domain-model.md);
for contributing etiquette see [CONTRIBUTING.md](../CONTRIBUTING.md).

## Prerequisites

- **JDK 21.** The Gradle toolchain pins every module to Java 21. Use the
  checked-in wrapper (`./gradlew`); no separate Gradle install is needed.
- **GraalVM 21 with `native-image`**, only for building the native executable.
  GraalVM comes from your ambient `JAVA_HOME`; the toolchain does not manage it.
  [SDKMAN](https://sdkman.io/) is the easy way (`sdk list java`, pick a `21.x`
  GraalVM Community build, `sdk use java <id>`). Verify with `java -version`
  before `./gradlew :cli:nativeCompile`.
- **Docker**, only for the integration tests (LocalStack through Testcontainers).
- **bash, `shellcheck`** for the script tests and lint (optional locally; CI
  runs them).
- **A UTF-8 locale.** Tests, and the native image build, must run with one;
  otherwise tests with non-ASCII file names fail and a native binary built
  under a non-UTF-8 locale mangles such names. CI sets
  `LANG=C.UTF-8 LC_ALL=C.UTF-8`; do the same locally:

  ```bash
  export LANG=C.UTF-8 LC_ALL=C.UTF-8      # macOS: en_US.UTF-8
  ```

## Building and testing

```bash
./gradlew build                  # compile + unit tests + memory test, all modules (make build)
./gradlew test                   # unit tests only (fast, no Docker)
./gradlew integrationTest        # LocalStack-backed tests (needs Docker)
./gradlew :infrastructure:memoryTest   # the heap-capped scan regression test alone
./gradlew :cli:nativeCompile     # GraalVM native executable for this host (make native)
./gradlew :cli:distZip -Pversion=1.2.3 # JVM zip, cli/build/distributions/

bash scripts/glacier-backup-run.test.sh   # runner script tests (make test-runner)
bash scripts/next-version.test.sh         # release-version script tests
shellcheck --severity=warning scripts/*.sh
```

The native executable lands in `cli/build/native/nativeCompile/glacier-backup`.
It runs only on the OS/architecture it was built on. Local builds report
`glacier-backup 0.0.0-SNAPSHOT`; CI stamps the real version with `-Pversion`.

On linux/aarch64 the build passes `-H:PageSize=65536` so the image starts on
both 4K- and 64K-page kernels; the UNAS Pro's kernel uses 64K pages and a 4K
image fails there with "Failed to create the main Isolate (code 24)". Native
builds also use `-march=compatibility` so binaries run on a broad range of CPUs.

### Test layout

| Module | What is tested | How |
|---|---|---|
| `domain` | Value-object invariants, glob filtering (`PathFilter`), the sync diff (`SyncPlanner`), restore legality (`RestoreRequest`), policy defaults | Plain JUnit 5 + AssertJ; no mocks or fakes |
| `application` | Use-case orchestration, partial-failure collection, progress events, key-prefix math | JUnit 5 + AssertJ against in-memory fakes of the ports (`FakeLocalVolume`, `FakeRemoteStorage`, `RecordingProgressReporter` in `src/testFixtures`) |
| `infrastructure` | Local scanning (`NioLocalVolumeTest`), restore-header parsing, S3 client timeout invariants (unit); `S3RemoteStorageIT` against a real LocalStack container (integration); `NioLocalVolumeMemoryRegressionTest` (memory) | Tags `integration` and `memory` split these out of `test` |
| `cli` | Command parsing, config loading, dry-run rendering, exit codes, the shipped `examples/` configs (`ExampleConfigsTest`) against a fake `AppContext`; `EndToEndIT`: the full CLI against LocalStack (`sync`, `ls`, `rm`, `get`) | `EndToEndIT` is tagged `integration`, everything else runs under `test` |

**Tag convention.** Anything needing Docker is `@Tag("integration")` and lives
next to the module's other tests; `test` excludes that tag and
`integrationTest` runs only it. The memory test is `@Tag("memory")`; it runs in
its own forked JVM with a 320 MB heap cap (`memoryTest`, wired into `check`, so
`./gradlew build` runs it) to guard against a regression that once got the
process OOM-killed on a small NAS during a ~1M-file scan. Do not raise that cap
without re-verifying that the test still fails on the pre-fix code.

**Shell tests.** `scripts/glacier-backup-run.test.sh` drives the runner with a
stub binary entirely inside a temp directory (it needs a `timeout` that
supports `-s TERM`). `scripts/next-version.test.sh` covers the version script.
Both are plain scripts with no framework and exit non-zero on failure.

## Project layout

```text
domain/          pure Java domain model and sync planner
application/     use cases and port interfaces (+ test fixtures)
infrastructure/  S3 adapter (AWS SDK v2) and local filesystem scanner
cli/             picocli/Micronaut commands, config loading, GraalVM native build
scripts/         runner, UNAS installer, filename fixer, release-version script, shell tests
examples/        example configs (verified by tests)
docs/            documentation
Makefile         build / native / test-runner / fetch-release / deploy-unas / setup-unas
.github/         CI and release workflows, PR template, Dependabot
```

## CI

There are two workflows in `.github/workflows/`.

### `ci.yml`: pull requests

Runs on pull requests (`opened`, `synchronize`, `reopened`, `edited`, `labeled`,
`unlabeled`; editing the title/body/labels re-runs it so the release preview
stays accurate) and on manual dispatch. Superseded runs are cancelled.

| Job (check name) | Runner | What it does |
|---|---|---|
| `Unit tests (gradle build)` | ubuntu-latest | `./gradlew build` on Temurin 21: compile, unit tests, memory test |
| `Integration tests` | ubuntu-latest | `./gradlew integrationTest` (Docker/LocalStack) |
| `Shell tests and shellcheck` | ubuntu-latest | both shell test scripts, then `shellcheck --severity=warning scripts/*.sh` |
| `Native smoke build (linux-arm64)` | ubuntu-24.04-arm | builds the native image in `ghcr.io/graalvm/native-image-community:21-ol8` and asserts `--version` |
| `Release preview` | ubuntu-latest | runs `scripts/next-version.sh` for the PR and writes "Merging this would publish vX.Y.Z" to the job summary |

### `release.yml`: every push to `main`

See the next section.

## Release process

**Every merge to `main` publishes a new release**: a git tag `vX.Y.Z` and a
GitHub Release with binaries, a JVM zip and checksums. There is no manual
release step, no version file to edit (`gradle.properties` stays at
`0.0.0-SNAPSHOT`), and no bump commit.

### What a release run does

1. **Compute version.** If the pushed commit already carries a `vX.Y.Z` tag, the
   run does nothing. Otherwise it gathers the text that can carry a bump
   keyword: the merged PR's title, body and labels, the PR's commit messages,
   and the commit messages of this push (`before..sha`). It feeds them to
   `scripts/next-version.sh`. If the result is `none`, the later jobs are skipped
   and nothing is published.
2. **Test.** Unit tests (`./gradlew build`) and integration tests run again on
   `main`.
3. **Build**, in parallel, each smoke-tested with `--version` (which must print
   exactly `glacier-backup X.Y.Z`):
   - `glacier-backup-linux-arm64` (ubuntu-24.04-arm, Oracle Linux 8 container),
   - `glacier-backup-linux-amd64` (ubuntu-latest, Oracle Linux 8 container),
   - `glacier-backup-macos-arm64` (macos-latest, GraalVM set up with
     `graalvm/setup-graalvm`),
   - `glacier-backup-X.Y.Z.zip` (JVM distribution, containing
     `glacier-backup-X.Y.Z/bin/glacier-backup`).

   The Linux images use Oracle Linux 8 (glibc 2.28) so the binaries run on
   devices with an older glibc, such as the UNAS Pro.
4. **Publish.** The `release` job writes `SHA256SUMS` for the four artifacts and
   creates the tag and the release (`gh release create`, generated notes) on
   exactly the tested commit. The tag only exists if every earlier job
   succeeded. Releases are serialised (`concurrency: release`).

Assets per release: `glacier-backup-linux-arm64`, `glacier-backup-linux-amd64`,
`glacier-backup-macos-arm64`, `glacier-backup-X.Y.Z.zip`, `SHA256SUMS`.

### Choosing the bump

Put a keyword anywhere in the PR title, the PR body, a commit message, or add a
label:

| Keyword | Effect | Label equivalents |
|---|---|---|
| `#major` | breaking change: `X.Y.Z` -> `(X+1).0.0` | `major`, `bump:major`, `#major` |
| `#minor` | new feature: `X.Y.Z` -> `X.(Y+1).0`. **The default.** | `minor`, `bump:minor` |
| `#patch` | bug fix: `X.Y.Z` -> `X.Y.(Z+1)` | `patch`, `bump:patch` |
| `#none` | no release (docs, CI, chores) | `none`, `bump:none` |

Rules, exactly as implemented in `scripts/next-version.sh`:

- Keywords are case-insensitive and must stand alone: `#majority`, `foo#major`
  and `#major-ish` are not keywords.
- Text inside HTML comments (`<!-- ... -->`) is ignored, so the hint text in the
  PR template never triggers anything. An unterminated comment hides the rest of
  the text.
- Labels count when the whole label is `major`, `minor`, `patch` or `none`,
  optionally with a `#` prefix or a `bump` prefix separated by `:`, `/`, `-` or
  whitespace (`bump:major`, `bump: patch`, `bump-none`).
- **Precedence:** `major` > `minor` > `patch`. If any of those is present, the
  highest wins and `#none` is ignored. `#none` takes effect only when it is the
  only keyword.
- With no keyword at all, the bump is `minor`.
- A manual override beats everything (see below).

### Base version

The base is the highest tag matching `^v[0-9]+\.[0-9]+\.[0-9]+$`, compared
numerically (`v0.10.0` is higher than `v0.9.0`). Other tags (`v1.2.3-rc1`, `foo`)
are ignored. With no such tag, the base falls back to `0.1.18`, so the first
release of this repository is `v0.2.0` (a minor bump) unless a keyword says
otherwise.

### Manual release or retry

Run the **Release** workflow manually (Actions -> Release -> Run workflow) on
`main`, and choose a `bump` (`auto`, `major`, `minor`, `patch`, `none`). Any
value other than `auto` overrides the keywords. Use it to redo a release whose
run failed with the intended bump, since a re-run of the original push may
compute something different after labels changed. A commit that already has a
release tag is never released twice.

### Previewing a release

The `Release preview` check on every PR states the version that merging would
publish, and why. Put the keyword in the PR title or body (or label it) and the
check updates when you edit.

### Repository settings the maintainer must configure

These cannot be set from the repository's files:

- **Actions -> General -> Workflow permissions.** The release job requests
  `contents: write` for itself, but if the repository or organization restricts
  the default token to read-only in a way that blocks that, set it to
  "Read and write permissions". Without it the tag and release cannot be created.
- **Runners.** The Linux arm64 jobs use the hosted `ubuntu-24.04-arm` runners
  (free for public repositories; private repositories need a plan that includes
  them) and `macos-latest` is Apple silicon.
- **Branch protection on `main`.** Require pull requests and the CI checks by
  their job names: `Unit tests (gradle build)`, `Integration tests`,
  `Shell tests and shellcheck`, `Native smoke build (linux-arm64)`, and
  optionally `Release preview`.
- **Squash merges** are the simplest fit: set the default squash message to
  "Pull request title and description" so keywords in the PR text carry into the
  commit that the release run inspects (the run also reads the PR itself, so
  this is belt and braces).
- **Tag rules.** If you protect `v*` tags, allow the Actions bot to create them.

Dependabot (`.github/dependabot.yml`) opens weekly grouped updates for GitHub
Actions and Gradle dependencies; those PRs default to a minor bump, so add
`#patch` or `#none` as appropriate.

## Makefile reference

| Target | Purpose |
|---|---|
| `make help` | List targets and variables |
| `make build` | `./gradlew build` |
| `make native` | `./gradlew :cli:nativeCompile` |
| `make test-runner` | Shell tests for `scripts/glacier-backup-run.sh` |
| `make fetch-release` | Download and checksum-verify the linux-arm64 release binary into `build/release/` (`VERSION`, `GITHUB_REPO`) |
| `make deploy-unas` | Copy the binary and UTF-8/heap wrapper to the NAS (`UNAS_HOST`, `UNAS_USER`, `VERSION`) |
| `make setup-unas` | Install credentials, config, runner and cron on the NAS (`CONFIG`, `SCHEDULE`, AWS keys) |

The last two are documented in the [UNAS Pro guide](unas-pro.md).
