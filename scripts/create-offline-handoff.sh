#!/usr/bin/env bash
# Prepare and verify a self-contained Linux x86-64 ShareWise development handoff.
# Requires: bash, git, tar, python3, wget, and checksum utilities on the preparation host.
set -Eeuo pipefail

usage() {
    cat <<'USAGE'
Usage:
  scripts/create-offline-handoff.sh pack \
    [--output /path/to/sharewise-offline-linux-x64.tar.gz] \
    [--cache-dir /path/to/toolchain-download-cache] \
    [--jdk /path/to/jdk-25-linux-x64.tar.gz] \
    [--node /path/to/node-v24-linux-x64.tar.xz] \
    [--maven /path/to/apache-maven-3.10.0-bin.zip]

  scripts/create-offline-handoff.sh verify /path/to/extracted-bundle

Run 'pack' on a network-connected Linux x86-64 machine. By default, wget
fetches the latest Eclipse Temurin JDK 25 and Node.js 24 patch releases,
and Maven 3.10.0. Their downloaded archives are kept in the Git-ignored
.offline-handoff-cache/toolchains/ directory. The official release metadata
provides checksums that are checked on every cache reuse and download.
Supply --jdk, --node or --maven to override any individual download with a
locally provided archive; verify such archives independently before use.

The command also resolves Maven/npm dependencies and verifies Maven/npm
builds without network access before packaging the source, toolchains,
and caches. The resulting handoff can be verified without network access.
The source worktree must have no tracked modifications. Untracked files
(including the toolchain cache) are not included in the source snapshot.
USAGE
}
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
note() { printf '\n>>> %s\n' "$*" >&2; }

require_host() {
    [[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] ||
        die 'Run on Linux x86_64; caches must be prepared for the handoff target platform.'
    for tool in bash tar python3 sha256sum sha512sum grep sed cp mktemp; do
        command -v "$tool" >/dev/null || die "Missing host tool: $tool"
    done
}

# Only the preparation path invokes wget; verify never accesses the network.
wget_metadata() {
    local url="$1" target="$2"
    command -v wget >/dev/null || die 'wget is required for automatic toolchain downloads.'
    wget -q --https-only --tries=3 --timeout=30 -O "$target" "$url" ||
        die "Unable to obtain release metadata: $url"
    [[ -s "$target" ]] || die "Empty release metadata: $url"
}

# Download to a separate partial file, verify its vendor-published digest,
# then atomically move it into the local cache. Never trust a stale cache file.
cache_download() {
    local url="$1" digest="$2" algorithm="$3" destination="$4" actual partial
    case "$algorithm" in
        sha256) [[ "$digest" =~ ^[a-fA-F0-9]{64}$ ]] || die 'Invalid SHA-256 digest.' ;;
        sha512) [[ "$digest" =~ ^[a-fA-F0-9]{128}$ ]] || die 'Invalid SHA-512 digest.' ;;
        *) die "Unsupported digest algorithm: $algorithm" ;;
    esac
    mkdir -p "$(dirname "$destination")"
    if [[ -f "$destination" ]]; then
        actual=$("${algorithm}sum" "$destination" | cut -d' ' -f1)
        if [[ "${actual,,}" == "${digest,,}" ]]; then
            note "Using cached, verified archive: $(basename "$destination")"
            return
        fi
        note "Cached file failed checksum validation, downloading again: $destination"
        rm -f -- "$destination"
    fi
    partial="${destination}.part"
    # -c resumes interrupted downloads; wget will start anew if ranges are unsupported.
    wget --https-only --tries=3 --timeout=60 --continue -O "$partial" "$url" ||
        die "Toolchain download failed: $url"
    actual=$("${algorithm}sum" "$partial" | cut -d' ' -f1)
    [[ "${actual,,}" == "${digest,,}" ]] || {
        rm -f -- "$partial"
        die "Vendor checksum mismatch for $(basename "$destination")"
    }
    mv -f -- "$partial" "$destination"
}

# Every metadata endpoint is HTTPS. Parse only the expected platform and
# archive shape; never execute metadata or blindly trust arbitrary URLs.
resolve_jdk() {
    local cache="$1" temp="$2" details name checksum url
    wget_metadata 'https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=x64&image_type=jdk&os=linux&vendor=eclipse' "$temp/jdk.json"
    # Capture the parser exit status rather than hiding failures in process substitution.
    details=$(python3 - "$temp/jdk.json" <<'PY_JDK'
import json, re, sys
from urllib.parse import urlsplit
with open(sys.argv[1], encoding='utf-8') as stream:
    releases = json.load(stream)
if not isinstance(releases, list) or not releases:
    raise SystemExit('No Java 25 release in Adoptium metadata')
release = next((x for x in releases if
    x.get('binary', {}).get('architecture') == 'x64' and
    x['binary'].get('os') == 'linux' and
    x['binary'].get('image_type') == 'jdk' and
    x['binary'].get('jvm_impl') == 'hotspot' and
    x['binary'].get('heap_size') == 'normal'), None)
if not release or release.get('version', {}).get('major') != 25:
    raise SystemExit('No matching stable Temurin JDK 25 release')
package = release['binary']['package']
name, checksum, url = (package[k] for k in ('name', 'checksum', 'link'))
if not re.fullmatch(r'OpenJDK25U-jdk_x64_linux_hotspot_[A-Za-z0-9_.+-]+\.tar\.gz', name):
    raise SystemExit('Unexpected Temurin archive name')
if not re.fullmatch(r'[0-9a-fA-F]{64}', checksum):
    raise SystemExit('Invalid Temurin checksum')
parsed = urlsplit(url)
if parsed.scheme != 'https' or parsed.hostname != 'github.com' or not parsed.path.startswith('/adoptium/temurin25-binaries/releases/download/'):
    raise SystemExit('Unexpected Temurin download URL')
print(name, checksum, url, sep='\t')
PY_JDK
    ) || die 'Failed to parse Temurin JDK 25 release metadata.'
    IFS=$'\t' read -r name checksum url <<< "$details"
    [[ -n "$name" && -n "$checksum" && -n "$url" ]] || die 'Could not resolve Temurin release.'
    cache_download "$url" "$checksum" sha256 "$cache/$name"
    printf '%s\n' "$cache/$name"
}

resolve_node() {
    local cache="$1" temp="$2" version name url checksum
    wget_metadata 'https://nodejs.org/dist/index.json' "$temp/node-index.json"
    version=$(python3 - "$temp/node-index.json" <<'PY_NODE'
import json, re, sys
with open(sys.argv[1], encoding='utf-8') as stream:
    releases = json.load(stream)
versions = []
for release in releases:
    version = release.get('version', '')
    if re.fullmatch(r'v24\.\d+\.\d+', version) and 'linux-x64' in release.get('files', []):
        versions.append((tuple(map(int, version[1:].split('.'))), version))
if not versions:
    raise SystemExit('No official Node.js 24 linux-x64 release')
print(max(versions)[1])
PY_NODE
    )
    [[ "$version" =~ ^v24\.[0-9]+\.[0-9]+$ ]] || die 'Invalid Node.js version.'
    name="node-$version-linux-x64.tar.xz"
    url="https://nodejs.org/dist/$version"
    wget_metadata "$url/SHASUMS256.txt" "$temp/node-SHASUMS256.txt"
    checksum=$(python3 - "$temp/node-SHASUMS256.txt" "$name" <<'PY_HASH'
import re, sys
with open(sys.argv[1], encoding='utf-8') as stream:
    hashes = dict((filename.lstrip('*'), digest.lower()) for digest, filename in
        (line.split() for line in stream if line.strip()) if re.fullmatch(r'[0-9a-fA-F]{64}', digest))
checksum = hashes.get(sys.argv[2])
if not checksum:
    raise SystemExit('Node.js archive missing from official SHASUMS256.txt')
print(checksum)
PY_HASH
    )
    cache_download "$url/$name" "$checksum" sha256 "$cache/$name"
    printf '%s\n' "$cache/$name"
}

resolve_maven() {
    local cache="$1" temp="$2" name url checksum
    name='apache-maven-3.10.0-bin.zip'
    url="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.10.0/$name"
    wget_metadata "$url.sha512" "$temp/maven.sha512"
    checksum=$(python3 - "$temp/maven.sha512" <<'PY_MAVEN'
import re, sys
with open(sys.argv[1], encoding='ascii') as stream:
    content = stream.read().strip()
match = re.match(r'^[a-fA-F0-9]{128}(?=\s|$)', content)
if not match:
    raise SystemExit('Invalid Maven SHA-512 metadata')
print(match.group(0).lower())
PY_MAVEN
    )
    cache_download "$url" "$checksum" sha512 "$cache/$name"
    printf '%s\n' "$cache/$name"
}

# Extract only archives with one top-level directory. Never execute an archive
# from an unknown/untrusted origin; check vendor-provided SHA-256 hashes first.
extract_toolchain() {
    local archive="$1" destination="$2" staging="$3"
    mkdir -p "$staging" "$destination"
    case "$archive" in
        *.tar.gz|*.tgz|*.tar.xz|*.txz|*.tar)
            tar -xf "$archive" -C "$staging" ;;
        *.zip)
            python3 - "$archive" "$staging" <<'PY'
import pathlib, sys, zipfile
archive, root = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]).resolve()
with zipfile.ZipFile(archive) as zf:
    for info in zf.infolist():
        path = (root / info.filename).resolve()
        if path != root and root not in path.parents:
            raise SystemExit(f'Unsafe ZIP path: {info.filename}')
        if info.is_dir():
            continue
        mode = (info.external_attr >> 16) & 0o170000
        if mode == 0o120000:
            raise SystemExit(f'Symlink in ZIP not supported: {info.filename}')
    zf.extractall(root)
PY
            ;;
        *) die "Unsupported toolchain archive: $archive" ;;
    esac
    # Ignore optional archive metadata folders, but require exactly one actual root.
    mapfile -t roots < <(find "$staging" -mindepth 1 -maxdepth 1 ! -name '__MACOSX' -print)
    [[ ${#roots[@]} -eq 1 && -d "${roots[0]}" ]] ||
        die "Archive must contain exactly one top-level directory: $archive"
    cp -a "${roots[0]}/." "$destination/"
}

check_toolchains() {
    local root="$1" jdk_version node_version maven_version
    [[ -x "$root/toolchains/jdk/bin/java" && -x "$root/toolchains/jdk/bin/javac" ]] || die 'Invalid JDK archive.'
    [[ -x "$root/toolchains/node/bin/node" && -x "$root/toolchains/node/bin/npm" ]] || die 'Invalid Node archive.'
    [[ -x "$root/toolchains/maven/bin/mvn" ]] || die 'Invalid Maven archive.'
    jdk_version=$("$root/toolchains/jdk/bin/java" -version 2>&1 | sed -n '1p')
    case "$jdk_version" in
        *'version "25.'*|*'version "25"'*|*'version "25+'*) ;;
        *) die "Expected Java 25, got: $jdk_version" ;;
    esac
    node_version=$("$root/toolchains/node/bin/node" --version)
    [[ "$node_version" == v24.* ]] || die "Expected Node 24, got: $node_version"
    maven_version=$(JAVA_HOME="$root/toolchains/jdk" "$root/toolchains/maven/bin/mvn" -version | sed -n '1p')
    [[ "$maven_version" == *'3.10.0'* ]] || die "Expected Maven 3.10.0, got: $maven_version"
    note "Toolchains: $jdk_version, $node_version, $maven_version"
}

# Uses no user-level Maven/npm caches or machine-installed language toolchain.
setup_env() {
    local root="$1"
    export JAVA_HOME="$root/toolchains/jdk"
    export PATH="$root/toolchains/jdk/bin:$root/toolchains/maven/bin:$root/toolchains/node/bin:$PATH"
    export MAVEN_USER_HOME="$root/cache/maven-home"
    export NPM_CONFIG_CACHE="$root/cache/npm"
    export NPM_CONFIG_AUDIT=false NPM_CONFIG_FUND=false
    export NEXT_TELEMETRY_DISABLED=1
    export CI=1
    mkdir -p "$root/cache/maven" "$root/cache/npm" "$root/cache/maven-home"
}

run_maven() {
    local root="$1" mode="$2" source="$3"
    (
        cd "$source/src/backend/favsdemobe"
        "$root/toolchains/maven/bin/mvn" -B -ntp "-Dmaven.repo.local=$root/cache/maven" "$mode" clean verify
    )
}

run_npm() {
    local root="$1" mode="$2" source="$3"
    (
        cd "$source/src/frontend"
        # --offline is strict; --prefer-offline would still allow network access.
        "$root/toolchains/node/bin/npm" ci "$mode" --include=optional --no-audit --no-fund
        "$root/toolchains/node/bin/npm" run lint
        "$root/toolchains/node/bin/npm" run build
    )
}

# Dependency collection from a clean checkout, never from local node_modules/target.
prepare_dependencies() {
    local root="$1" source="$2"
    setup_env "$root"
    note 'Downloading backend dependencies, transitive plugins, and build/test artifacts'
    (
        cd "$source/src/backend/favsdemobe"
        "$root/toolchains/maven/bin/mvn" -B -ntp "-Dmaven.repo.local=$root/cache/maven" dependency:go-offline
        # Resolve actual lifecycle plugin dependencies, not just declared libraries.
        "$root/toolchains/maven/bin/mvn" -B -ntp "-Dmaven.repo.local=$root/cache/maven" clean verify
    )
    note 'Downloading frontend dependencies (Linux x64 optional native packages included)'
    run_npm "$root" --prefer-online "$source"
}

verify_bundle() (
    local root="$1" verify_temp
    root=$(cd "$root" && pwd -P)
    [[ -f "$root/source/src/backend/favsdemobe/pom.xml" ]] || die 'Missing source tree.'
    [[ -f "$root/source/src/frontend/package-lock.json" ]] || die 'Missing frontend lockfile.'
    check_toolchains "$root"
    (cd "$root/source" && sha256sum -c "$root/manifest/lockfiles-sha256.txt") ||
        die 'Dependency manifests differ from the captured handoff source.'
    setup_env "$root"
    verify_temp=$(mktemp -d)
    trap "rm -rf -- '$verify_temp'" EXIT
    note 'Verifying toolchain/source/caches against offline build in a fresh source copy'
    cp -a "$root/source" "$verify_temp/source"
    # No online fallback: Maven -o and npm --offline must both succeed.
    run_maven "$root" -o "$verify_temp/source"
    run_npm "$root" --offline "$verify_temp/source"
    note 'Offline Maven and npm builds passed.'
)

pack_bundle() {
    local jdk='' node='' maven='' output='' cache_dir='' repo temp root archive sha
    repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --jdk|--node|--maven|--output|--cache-dir)
                [[ $# -ge 2 ]] || die "Missing argument for $1"
                case "$1" in
                    --jdk) jdk="$2" ;; --node) node="$2" ;;
                    --maven) maven="$2" ;; --output) output="$2" ;;
                    --cache-dir) cache_dir="$2" ;;
                esac
                shift 2 ;;
            *) die "Unknown argument: $1" ;;
        esac
    done
    for archive in "$jdk" "$node" "$maven"; do
        [[ -z "$archive" || -f "$archive" ]] || die "Archive not found: $archive"
    done
    command -v git >/dev/null || die 'git is required to capture an exact tracked source snapshot.'
    git -C "$repo" rev-parse --show-toplevel >/dev/null || die 'Source must be committed in Git.'
    [[ "$(git -C "$repo" rev-parse --show-toplevel)" == "$repo" ]] || die 'Run this script from its repository checkout.'
    git -C "$repo" diff --quiet && git -C "$repo" diff --cached --quiet ||
        die 'Commit or discard tracked changes before creating a handoff.'
    [[ -n "$output" ]] || output="$(dirname "$repo")/sharewise-offline-linux-x64.tar.gz"
    mkdir -p "$(dirname "$output")"
    output="$(cd "$(dirname "$output")" && pwd -P)/$(basename "$output")"
    temp=$(mktemp -d)
    trap "rm -rf -- '$temp'" EXIT
    root="$temp/bundle"
    mkdir -p "$root/source" "$root/toolchains" "$root/cache" "$root/manifest"
    [[ -n "$cache_dir" ]] || cache_dir="$repo/.offline-handoff-cache/toolchains"
    mkdir -p "$cache_dir"
    cache_dir=$(cd "$cache_dir" && pwd -P)
    # No download takes place if an explicit local archive was provided.
    if [[ -z "$jdk" ]]; then
        note 'Resolving latest GA Eclipse Temurin JDK 25 (Linux x64)'
        jdk=$(resolve_jdk "$cache_dir" "$temp")
    fi
    if [[ -z "$node" ]]; then
        note 'Resolving latest Node.js 24 (Linux x64)'
        node=$(resolve_node "$cache_dir" "$temp")
    fi
    if [[ -z "$maven" ]]; then
        note 'Resolving Maven 3.10.0'
        maven=$(resolve_maven "$cache_dir" "$temp")
    fi
    note 'Archiving committed source (excluding .git and local build artifacts)'
    git -C "$repo" archive --format=tar HEAD | tar -xf - -C "$root/source"
    git -C "$repo" rev-parse HEAD > "$root/manifest/source-commit.txt"
    note 'Extracting verified/downloaded SDKs and build tools'
    extract_toolchain "$jdk" "$root/toolchains/jdk" "$temp/extract-jdk"
    extract_toolchain "$node" "$root/toolchains/node" "$temp/extract-node"
    extract_toolchain "$maven" "$root/toolchains/maven" "$temp/extract-maven"
    chmod +x "$root/toolchains/maven/bin/mvn"
    check_toolchains "$root"
    cp "$repo/scripts/create-offline-handoff.sh" "$root/verify-offline.sh"
    chmod +x "$root/verify-offline.sh"
    { sha256sum "$jdk" | sed "s|  .*|  $(basename "$jdk")|";
      sha256sum "$node" | sed "s|  .*|  $(basename "$node")|";
      sha256sum "$maven" | sed "s|  .*|  $(basename "$maven")|"; } > "$root/manifest/toolchain-archive-sha256.txt"
    ( cd "$repo"; sha256sum src/backend/favsdemobe/pom.xml src/backend/favsdemobe/.mvn/wrapper/maven-wrapper.properties src/frontend/package.json src/frontend/package-lock.json ) > "$root/manifest/lockfiles-sha256.txt"
    cp -a "$root/source" "$temp/online-source"
    prepare_dependencies "$root" "$temp/online-source"
    rm -rf "$temp/online-source"
    verify_bundle "$root"
    cat > "$root/README-OFFLINE.md" <<'README'
# ShareWise Linux x86-64 offline development handoff

Unpack the bundle on Linux x86-64. All commands below use local toolchains
and caches. You do not need a globally installed JDK, Maven, or Node.js.

Verify the bundle with:

    ./verify-offline.sh verify .

The script runs Maven offline (`-o clean verify`) and npm offline (`ci --offline`,
`lint`, and `build`) in a temporary source copy. The actual editable repository
snapshot is in `source/`. The original sources and cached toolchains are not
modified by verification, although the npm and Maven caches may acquire local
metadata.

When working on the source tree, configure:

    export JAVA_HOME="$PWD/toolchains/jdk"
    export PATH="$JAVA_HOME/bin:$PWD/toolchains/maven/bin:$PWD/toolchains/node/bin:$PATH"
    export NPM_CONFIG_CACHE="$PWD/cache/npm"

Use Maven with `-o -Dmaven.repo.local="$PWD/cache/maven"`; use `npm ci --offline`.
No `node_modules` directory or compiled artifacts are part of the handoff.
The bundle contains a Git-tracked source snapshot, **not .git history**.
If Git commit history is required, provide a separate Git bundle.

This archive is for Linux x86-64/glibc hosts. It does not include a container
runtime/image, database server, secrets, or OS-level native dependencies.
Native frontend packages are specific to the platform on which they were cached.
No dependency installation should require the network after verification.
README
    note "Packaging: $output"
    (cd "$root" && tar -czf "$output" .)
    (cd "$(dirname "$output")" && sha256sum "$(basename "$output")") > "$output.sha256"
    sha=$(cat "$output.sha256")
    note "Complete: $sha"
}

main() {
    umask 077
    require_host
    [[ $# -gt 0 ]] || { usage; exit 2; }
    case "$1" in
        pack) shift; pack_bundle "$@" ;;
        verify) shift; [[ $# -eq 1 ]] || die 'verify needs one extracted bundle directory'; verify_bundle "$1" ;;
        -h|--help|help) usage ;;
        *) usage; die "Unsupported action: $1" ;;
    esac
}
main "$@"
