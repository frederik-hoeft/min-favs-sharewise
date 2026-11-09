#!/usr/bin/env bash
# Deterministic end-to-end checks of handoff selection without downloading SDKs.
set -Eeuo pipefail

die() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
assert_file() { [[ -f "$1" ]] || die "Missing: $1"; }
assert_absent() { [[ ! -e "$1" ]] || die "Unexpected file: $1"; }
should_fail() { if "$@" >/dev/null 2>&1; then die "Unexpected success: $*"; fi; }

work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
source="$work/source"
mkdir -p "$source/scripts" "$source/src/backend/sharewise/.mvn/wrapper" "$source/src/frontend" \
    "$work/toolchain-content/jdk-test/bin" "$work/toolchain-content/node-test/bin" \
    "$work/toolchain-content/maven-test/bin" "$work/vendor"
cp "$(dirname "${BASH_SOURCE[0]}")/create-offline-handoff.sh" "$source/scripts/"
printf 'backend descriptor\n' > "$source/src/backend/sharewise/pom.xml"
printf 'wrapper descriptor\n' > "$source/src/backend/sharewise/.mvn/wrapper/maven-wrapper.properties"
printf 'frontend descriptor\n' > "$source/src/frontend/package.json"
printf 'frontend lock\n' > "$source/src/frontend/package-lock.json"

cat > "$work/toolchain-content/jdk-test/bin/java" <<'EOF'
#!/usr/bin/env bash
printf 'openjdk version "25.0.1"\n' >&2
EOF
cat > "$work/toolchain-content/jdk-test/bin/javac" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
cat > "$work/toolchain-content/maven-test/bin/mvn" <<'EOF'
#!/usr/bin/env bash
set -eu
if [[ "${1:-}" == -version ]]; then echo 'Apache Maven 3.10.0'; exit; fi
for arg in "$@"; do case "$arg" in -Dmaven.repo.local=*) repo=${arg#*=} ;; esac; done
[[ -n "${repo:-}" ]] || exit 2
if [[ " $* " == *' -o '* ]]; then
    [[ -f "$repo/cached-maven-artifact" ]] || exit 3
else
    mkdir -p "$repo"
    touch "$repo/cached-maven-artifact"
fi
EOF
cat > "$work/toolchain-content/node-test/bin/node" <<'EOF'
#!/usr/bin/env bash
[[ "${1:-}" == --version ]] && { echo 'v24.3.0'; exit; }
exit 2
EOF
cat > "$work/toolchain-content/node-test/bin/npm" <<'EOF'
#!/usr/bin/env bash
set -eu
if [[ "${1:-}" == ci ]]; then
    if [[ " $* " == *' --offline '* ]]; then
        [[ -f "$NPM_CONFIG_CACHE/cached-node-package" ]] || exit 3
    else
        mkdir -p "$NPM_CONFIG_CACHE"
        touch "$NPM_CONFIG_CACHE/cached-node-package"
    fi
fi
EOF
chmod +x "$work"/toolchain-content/*/bin/*
(cd "$work/toolchain-content" && tar -czf "$work/vendor/jdk.tar.gz" jdk-test)
(cd "$work/toolchain-content" && tar -cJf "$work/vendor/node.tar.xz" node-test)
python3 - "$work/toolchain-content/maven-test" "$work/vendor/maven.zip" <<'PY'
from pathlib import Path
from zipfile import ZipFile
import sys
root, target = map(Path, sys.argv[1:])
with ZipFile(target, 'w') as archive:
    for path in root.rglob('*'):
        archive.write(path, Path(root.name) / path.relative_to(root))
PY

pack() {
    local selection="$1"
    local -a args=(--output "$work/$selection/deps.tar.gz" --cache-dir "$work/vendor")
    # Test that omitting --deps retains the original combined behavior.
    if [[ "$selection" != all ]]; then args+=(--deps "$selection"); fi
    if [[ "$2" == yes ]]; then
        args+=(--jdk "$work/vendor/jdk.tar.gz" --maven "$work/vendor/maven.zip")
    fi
    if [[ "$3" == yes ]]; then args+=(--node "$work/vendor/node.tar.xz"); fi
    "$source/scripts/create-offline-handoff.sh" pack "${args[@]}" \
        >/dev/null 2>"$work/$selection.log"
    mkdir -p "$work/$selection/deps"
    tar -xzf "$work/$selection/deps.tar.gz" -C "$work/$selection/deps"
    "$work/$selection/deps/verify-offline.sh" verify "$work/$selection/deps" --source "$source" \
        --toolchains "$work/$selection/toolchains" > /dev/null 2>>"$work/$selection.log"
}

# Explicit local vendor archives make these runs network-independent.
pack all yes yes
pack maven yes no
pack node no yes

assert_file "$work/all/deps/cache/maven/cached-maven-artifact"
assert_file "$work/all/deps/cache/npm/cached-node-package"
[[ $(wc -l < "$work/all/deps/manifest/toolchain-files.txt") == 3 ]] || die 'all toolchains'
assert_file "$work/maven/deps/cache/maven/cached-maven-artifact"
assert_absent "$work/maven/deps/cache/npm"
assert_absent "$work/maven/toolchains/node.tar.xz"
[[ $(wc -l < "$work/maven/deps/manifest/toolchain-files.txt") == 2 ]] || die 'maven toolchains'
assert_file "$work/node/deps/cache/npm/cached-node-package"
assert_absent "$work/node/deps/cache/maven"
assert_absent "$work/node/toolchains/jdk.tar.gz"
assert_absent "$work/node/toolchains/maven.zip"
[[ $(wc -l < "$work/node/deps/manifest/toolchain-files.txt") == 1 ]] || die 'node toolchains'

# Unselected descriptors/projects are not required for partial verification.
cp -a "$source" "$work/node-source"
rm -rf "$work/node-source/src/backend"
"$work/node/deps/verify-offline.sh" verify "$work/node/deps" --source "$work/node-source" \
    --toolchains "$work/node/toolchains" >/dev/null 2>&1
printf 'changed node lock\n' > "$work/node-source/src/frontend/package-lock.json"
should_fail "$work/node/deps/verify-offline.sh" verify "$work/node/deps" --source "$work/node-source" \
    --toolchains "$work/node/toolchains"
cp -a "$source" "$work/maven-source"
rm -rf "$work/maven-source/src/frontend"
"$work/maven/deps/verify-offline.sh" verify "$work/maven/deps" --source "$work/maven-source" \
    --toolchains "$work/maven/toolchains" >/dev/null 2>&1
printf 'changed backend POM\n' > "$work/maven-source/src/backend/sharewise/pom.xml"
should_fail "$work/maven/deps/verify-offline.sh" verify "$work/maven/deps" --source "$work/maven-source" \
    --toolchains "$work/maven/toolchains"

# The checksum manifest must not reference unrelated original archives.
printf '%064d  extraneous.tar.gz\n' 0 >> "$work/maven/deps/manifest/toolchain-archive-sha256.txt"
should_fail "$work/maven/deps/verify-offline.sh" verify "$work/maven/deps" --source "$source" \
    --toolchains "$work/maven/toolchains"

# Selected verification fails closed on missing toolchains or cache entries.
mv "$work/node/toolchains/node.tar.xz" "$work/node/toolchains/node.tar.xz.saved"
should_fail "$work/node/deps/verify-offline.sh" verify "$work/node/deps" --source "$source" \
    --toolchains "$work/node/toolchains"
mv "$work/node/toolchains/node.tar.xz.saved" "$work/node/toolchains/node.tar.xz"
rm "$work/node/deps/cache/npm/cached-node-package"
should_fail "$work/node/deps/verify-offline.sh" verify "$work/node/deps" --source "$source" \
    --toolchains "$work/node/toolchains"

# Older, complete handoffs without the selection file remain verifiable.
rm "$work/all/deps/manifest/dependency-selection.txt"
"$work/all/deps/verify-offline.sh" verify "$work/all/deps" --source "$source" \
    --toolchains "$work/all/toolchains" >/dev/null 2>&1

# Default filenames keep partial variants separate from the original bundle.
"$source/scripts/create-offline-handoff.sh" pack --deps node \
    --node "$work/vendor/node.tar.xz" --cache-dir "$work/vendor" >/dev/null 2>&1
assert_file "$source/.offline-handoff-cache/sharewise-offline-deps-node-linux-x64.tar.gz"

should_fail "$source/scripts/create-offline-handoff.sh" pack --deps invalid
should_fail "$source/scripts/create-offline-handoff.sh" pack --deps node --jdk "$work/vendor/jdk.tar.gz"
should_fail "$source/scripts/create-offline-handoff.sh" pack --deps maven --node "$work/vendor/node.tar.xz"
printf 'PASS: full, Maven-only and Node-only bundles; offline verification; selective manifests; legacy compatibility; invalid options\n'
