#!/bin/sh
# GitLab runner compatibility probe for a maintained Kaniko executor.
# Builds a disposable image artifact, never pushes to a registry.
set -eu

: "${CI_PROJECT_DIR:?GitLab checkout required}"
RESULT_DIR="$CI_PROJECT_DIR/kaniko-probe-results"
CONTEXT="$CI_PROJECT_DIR/scripts/ci/container-build-probe"
ARCHIVE="$RESULT_DIR/probe.docker.tar"
REPORT="$RESULT_DIR/runner.txt"
EXECUTOR="${KANIKO_EXECUTOR:-/kaniko/executor}"
mkdir -p "$RESULT_DIR"

{
    printf 'GitLab job: %s\n' "${CI_JOB_URL:-unknown}"
    printf 'Commit: %s\n' "${CI_COMMIT_SHA:-unknown}"
    printf 'Runner: %s (version %s, architecture %s)\n' \
        "${CI_RUNNER_DESCRIPTION:-unknown}" \
        "${CI_RUNNER_VERSION:-unknown}" \
        "${CI_RUNNER_EXECUTABLE_ARCH:-unknown}"
    printf 'Kernel: '; uname -a
    printf 'Job UID:GID: %s:%s\n' "$(id -u)" "$(id -g)"
    printf 'User namespace UID map:\n'
    cat /proc/self/uid_map
    printf 'Effective capabilities: '
    grep '^CapEff:' /proc/self/status || true
    "$EXECUTOR" version
} > "$REPORT" 2>&1
cat "$REPORT"

if [ -e /var/run/docker.sock ]; then
    echo 'Unexpected Docker socket mounted in probe job.' >&2
    exit 1
fi

# A no-push build tests RUN/COPY and produces a Docker-archive artifact.
# This deliberately uses no registry credentials or destination that could be pushed.
if ! "$EXECUTOR" \
    --context="dir://$CONTEXT" \
    --dockerfile="$CONTEXT/Dockerfile" \
    --destination="example.invalid/sharewise/runner-probe:disposable" \
    --no-push \
    --tar-path="$ARCHIVE" \
    --digest-file="$RESULT_DIR/digest.txt" \
    --verbosity=info > "$RESULT_DIR/build.log" 2>&1; then
    cat "$RESULT_DIR/build.log"
    echo 'Kaniko image build failed; see job log and build.log artifact.' >&2
    exit 1
fi
cat "$RESULT_DIR/build.log"

test -s "$ARCHIVE"
test -s "$RESULT_DIR/digest.txt"
grep -Eq '^sha256:[0-9a-f]{64}$' "$RESULT_DIR/digest.txt"
tar -tf "$ARCHIVE" | grep -Eq '(^|/)manifest.json$'
{
    printf 'Image digest: %s\n' "$(cat "$RESULT_DIR/digest.txt")"
    printf 'Docker tarball SHA-256: '
    sha256sum "$ARCHIVE"
    printf 'Outcome: PASS (actual runner policy still requires human review)\n'
} | tee -a "$REPORT"
