UNAS_USER ?= root
UNAS_HOST ?= unas
export UNAS_USER UNAS_HOST

# Release to deploy, without a leading "v" (e.g. VERSION=1.2.0). Empty means
# "the latest GitHub release", resolved when fetch-release runs.
VERSION ?=
GITHUB_REPO ?= functorful/glacier-backup
RELEASE_ASSET := glacier-backup-linux-arm64
CHECKSUMS_ASSET := SHA256SUMS
DOWNLOAD_DIR := build/release

.DEFAULT_GOAL := help
.PHONY: help build native test-runner fetch-release deploy-unas setup-unas

help: ## List the available targets
	@echo "Targets:"
	@grep -E '^[a-zA-Z_-]+:.*## ' $(MAKEFILE_LIST) | sed -E 's/^([a-zA-Z_-]+):.*## (.*)/  \1\t\2/' | expand -t 16
	@echo
	@echo "Variables:"
	@echo "  VERSION=X.Y.Z    release to deploy (default: latest GitHub release)"
	@echo "  UNAS_HOST=unas   ssh host of the NAS"
	@echo "  UNAS_USER=root   ssh user on the NAS"
	@echo "  CONFIG=...       config file for setup-unas (see examples/)"

build: ## Compile and run unit tests (./gradlew build)
	./gradlew build

# Local builds print the version from gradle.properties (0.0.0-SNAPSHOT); CI
# stamps release builds via -Pversion. A native build produces a binary for the
# machine it runs on only: the UNAS needs linux/arm64.
native: ## Build a native-image binary for this machine (needs GraalVM)
	./gradlew :cli:nativeCompile

# Off-box smoke test for the runner script - no shell-test framework in this
# repo, so this is a hand-rolled harness (see the test file itself).
test-runner: ## Run the shell tests for scripts/glacier-backup-run.sh
	sh scripts/glacier-backup-run.test.sh

# Downloads the linux-arm64 binary and SHA256SUMS from the GitHub release and
# verifies the checksum. The UNAS is linux/arm64 - a binary built on another
# platform will not run there.
fetch-release: ## Download + verify the linux-arm64 release binary (VERSION=X.Y.Z, default latest)
	@set -eu; \
	version="$(VERSION)"; \
	version="$${version#v}"; \
	if [ -z "$$version" ]; then \
		echo "Resolving latest release of $(GITHUB_REPO)"; \
		url=$$(curl -fsSL -o /dev/null -w '%{url_effective}' "https://github.com/$(GITHUB_REPO)/releases/latest"); \
		version="$${url##*/}"; \
		version="$${version#v}"; \
		case "$$version" in \
			''|latest|*[!0-9A-Za-z.+_-]*) echo "error: could not resolve the latest release (got '$$url')" >&2; exit 1 ;; \
		esac; \
	fi; \
	echo "Fetching glacier-backup v$$version"; \
	base="https://github.com/$(GITHUB_REPO)/releases/download/v$$version"; \
	rm -rf $(DOWNLOAD_DIR); \
	mkdir -p $(DOWNLOAD_DIR); \
	curl -fSL --retry 3 -o $(DOWNLOAD_DIR)/$(RELEASE_ASSET) "$$base/$(RELEASE_ASSET)"; \
	curl -fSL --retry 3 -o $(DOWNLOAD_DIR)/$(CHECKSUMS_ASSET) "$$base/$(CHECKSUMS_ASSET)"; \
	expected=$$(awk -v f="$(RELEASE_ASSET)" '{ n = $$2; sub(/^\*/, "", n); if (n == f) print $$1 }' $(DOWNLOAD_DIR)/$(CHECKSUMS_ASSET)); \
	if [ -z "$$expected" ]; then echo "error: $(RELEASE_ASSET) not listed in $(CHECKSUMS_ASSET)" >&2; exit 1; fi; \
	if command -v sha256sum >/dev/null 2>&1; then \
		actual=$$(sha256sum $(DOWNLOAD_DIR)/$(RELEASE_ASSET) | awk '{ print $$1 }'); \
	else \
		actual=$$(shasum -a 256 $(DOWNLOAD_DIR)/$(RELEASE_ASSET) | awk '{ print $$1 }'); \
	fi; \
	if [ "$$expected" != "$$actual" ]; then \
		echo "error: checksum mismatch for $(RELEASE_ASSET): expected $$expected, got $$actual" >&2; exit 1; \
	fi; \
	echo "Checksum OK ($$actual)"

# The real binary lands at glacier-backup.bin behind a wrapper that forces a
# UTF-8 locale: without it (e.g. non-interactive ssh, which has no LANG) the
# JVM's path charset is ASCII and every accented filename is treated as
# unmappable.
# The wrapper also caps the native-image heap (-Xmx1g, overridable via
# GLACIER_BACKUP_XMX): without a cap the serial GC sizes itself off physical
# RAM and the kernel OOM killer fires first, killing a large scan with a bare
# "Killed" instead of a diagnosable OutOfMemoryError - and taking the box's
# memory with it. The cap must stay well under the NAS's free RAM so a
# runaway scan fails as a clean Java OutOfMemoryError instead of a kernel
# SIGKILL (rc=137); 1g is ample now that the scan no longer retains a full
# absolute Path per normally-named file (see NioLocalVolume).
# Upload to a temp name and mv over the target: mv replaces the directory
# entry even while the old binary is executing (a direct scp onto a running
# executable fails with ETXTBSY).
deploy-unas: fetch-release ## Install the release binary + wrapper on the NAS over ssh (UNAS_HOST, UNAS_USER, VERSION)
	scp $(DOWNLOAD_DIR)/$(RELEASE_ASSET) $(UNAS_USER)@$(UNAS_HOST):/usr/local/bin/.glacier-backup.bin.new
	printf '#!/bin/sh\nexport LANG=C.UTF-8 LC_ALL=C.UTF-8\nexec /usr/local/bin/glacier-backup.bin -Xmx$${GLACIER_BACKUP_XMX:-1g} "$$@"\n' \
		| ssh $(UNAS_USER)@$(UNAS_HOST) 'cat > /usr/local/bin/.glacier-backup.new \
			&& chmod 755 /usr/local/bin/.glacier-backup.new /usr/local/bin/.glacier-backup.bin.new \
			&& mv -f /usr/local/bin/.glacier-backup.bin.new /usr/local/bin/glacier-backup.bin \
			&& mv -f /usr/local/bin/.glacier-backup.new /usr/local/bin/glacier-backup'

# Needs CONFIG=path/to/config.yml plus AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY
# in the environment; optional SCHEDULE. Run `make deploy-unas` first.
setup-unas: ## Install credentials, config, runner and cron on the NAS (CONFIG=..., see scripts/setup-unas.sh)
	bash scripts/setup-unas.sh
