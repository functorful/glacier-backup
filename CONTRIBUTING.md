# Contributing to glacier-backup

Thanks for helping out. Bug reports, documentation fixes and pull requests are
all welcome. The project is released under the [Unlicense](LICENSE).

## Build and test

You need JDK 21 (and Docker for the integration tests). Use a UTF-8 locale;
tests with non-ASCII file names fail without one.

```bash
export LANG=C.UTF-8 LC_ALL=C.UTF-8

./gradlew build                  # compile + unit tests + memory test
./gradlew integrationTest        # needs Docker (LocalStack)
bash scripts/glacier-backup-run.test.sh
bash scripts/next-version.test.sh
shellcheck --severity=warning scripts/*.sh
```

The native executable (`./gradlew :cli:nativeCompile`) needs GraalVM 21 and is
only required if you change native-image related build settings. Details,
including the test layout, are in [docs/development.md](docs/development.md).

## Pull requests

1. Fork, create a branch, and keep the change focused.
2. Add or update tests. The domain and application layers are tested without
   mocks or AWS; see [docs/architecture.md](docs/architecture.md) for where
   things belong.
3. Update the documentation under `docs/` (and the README) when behavior,
   flags, defaults or config keys change. Keep it in step with the code.
4. Open the PR and fill in the [pull request template](.github/pull_request_template.md).
5. Make sure the CI checks pass.

## Releases and version bump keywords

Every merge to `main` publishes a new release automatically. The default bump is
**minor**. Control it by putting a keyword in the PR title, the PR body, a commit
message, or by adding a label:

| Keyword | Use for |
|---|---|
| `#major` | breaking changes |
| `#minor` | new features (the default when no keyword is present) |
| `#patch` | bug fixes only |
| `#none` | no release: docs, CI, chores |

The highest of `#major` > `#minor` > `#patch` wins; `#none` only counts when it
is the only keyword. Labels `major`, `minor`, `patch`, `none` and `bump:<kind>`
work too. Text inside HTML comments is ignored. The **Release preview** check on
your PR shows which version merging would publish. The full rules are in
[docs/development.md](docs/development.md#release-process).

Documentation-only and CI-only changes should normally carry `#none`.

## Reporting issues

Please include the output of `glacier-backup --version`, your OS and
architecture, the command you ran, and the relevant (redacted) log lines. Never
paste AWS access keys or secrets.
