#!/bin/sh
# GitLab runner compatibility probe: no Docker socket, registry writes or DinD.
set -eu

: "${CI_PROJECT_DIR:?GitLab checkout required}"
RESULT_DIR="$CI_PROJECT_DIR/buildkit-probe-results"
CONTEXT="$CI_PROJECT_DIR/scripts/ci/container-build-probe"
ARCHIVE="$RESULT_DIR/probe.oci.tar"
REPORT="$RESULT_DIR/runner.txt"
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
    printf 'BuildKit daemon flags: %s\n' "${BUILDKITD_FLAGS:-unset}"
    buildctl --version
    buildkitd --version
} > "$REPORT"
cat "$REPORT"

if [ "$(id -u)" -eq 0 ]; then
    echo 'Refusing to run as root: this does not prove a rootless runner build.' >&2
    exit 1
fi
if [ -e /var/run/docker.sock ]; then
    echo 'Unexpected Docker socket mounted in probe job.' >&2
    exit 1
fi

# The Alpine base pull requires registry-read network access, not registry credentials.
# A RUN step is intentional: COPY-only/scratch builds do not prove worker execution.
if ! buildctl-daemonless.sh build \
    --progress=plain \
    --frontend dockerfile.v0 \
    --local "context=$CONTEXT" \
    --local "dockerfile=$CONTEXT" \
    --output "type=oci,dest=$ARCHIVE" > "$RESULT_DIR/build.log" 2>&1; then
    cat "$RESULT_DIR/build.log"
    echo 'BuildKit OCI build failed; see job log and build.log artifact.' >&2
    exit 1
fi
cat "$RESULT_DIR/build.log"

# OCI archive checksum and image manifest digest are intentionally distinct.
test -s "$ARCHIVE"
tar -xOf "$ARCHIVE" index.json > "$RESULT_DIR/index.json"
MANIFEST_DIGEST="$(grep -oE 'sha256:[0-9a-f]{64}' "$RESULT_DIR/index.json" | head -n 1)"
test -n "$MANIFEST_DIGEST"
MANIFEST_HEX="${MANIFEST_DIGEST#sha256:}"
tar -tf "$ARCHIVE" | grep -Eq "(^|/)blobs/sha256/$MANIFEST_HEX$"
{
    printf 'OCI image manifest digest: %s\n' "$MANIFEST_DIGEST"
    printf 'OCI tarball SHA-256: '
    sha256sum "$ARCHIVE"
    printf 'Outcome: PASS (actual runner policy still requires human review)\n'
} | tee -a "$REPORT"
