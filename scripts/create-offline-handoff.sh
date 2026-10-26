#!/usr/bin/env bash
# Prepare and verify reusable offline Linux x86-64 build dependencies.
# Preparation requires bash, tar, Python 3, wget, and checksum utilities.
# Verification needs only basic POSIX userland tools (not Python or wget).
set -Eeuo pipefail

usage() {
    cat <<'USAGE'
Usage:
  scripts/create-offline-handoff.sh pack \
    [--output /path/to/sharewise-offline-deps-linux-x64.tar.gz] \
    [--cache-dir /path/to/toolchain-download-cache] \
    [--jdk /path/to/jdk-25-linux-x64.tar.gz] \
    [--node /path/to/node-v24-linux-x64.tar.xz] \
    [--maven /path/to/apache-maven-3.10.0-bin.zip]

  ./verify-offline.sh verify /path/to/extracted-bundle --source /path/to/sharewise

'pack' uses the checkout containing this script to resolve Maven/npm dependencies,
then validates clean offline builds. It packages ONLY the JDK, Maven, Node/npm,
complete dependency caches, dependency-manifest hashes, and a verifier. No Git
repository, source snapshot, node_modules, or build output is included.

Source is supplied independently (Git checkout, ZIP, etc.) when verifying or
working offline. Its Maven/npm dependency manifests must match the packaged
hashes. Ordinary source changes do NOT require a new dependency archive.

By default wget fetches the latest Temurin JDK 25 and Node 24 patch releases,
and Maven 3.10.0. Toolchain archives are cached in the Git-ignored
.offline-handoff-cache/toolchains/ directory and checksum-verified. Use --jdk,
--node or --maven to supply a local archive instead (verify those yourself).

Output defaults to .offline-handoff-cache/sharewise-offline-deps-linux-x64.tar.gz
inside the checkout. Relative --output paths resolve against the caller's cwd.
Requires a network connection only for pack; verify uses strict offline builds.
USAGE
}
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
note() { printf '\n>>> %s\n' "$*" >&2; }

require_host() {
    [[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] ||
        die 'Run on Linux x86_64; caches must be prepared for the handoff target platform.'
    for tool in bash tar sha256sum grep sed cp mktemp; do
        command -v "$tool" >/dev/null || die "Missing host tool: $tool"
    done
}

require_pack_tools() {
    for tool in python3 wget sha512sum; do
        command -v "$tool" >/dev/null || die "Missing preparation tool: $tool"
    done
}

# Resolve a writable output location before downloading dependencies or compiling.
# Normalize the root directory without introducing a double leading slash.
resolve_output_path() {
    local requested="$1" directory filename probe
    [[ -n "$requested" && "$requested" != */ ]] ||
        die 'Output must be a nonempty file path (not a directory).'
    directory=$(dirname -- "$requested")
    filename=$(basename -- "$requested")
    mkdir -p -- "$directory" || die "Cannot create output directory: $directory"
    directory=$(cd -- "$directory" && pwd -P) || die "Cannot resolve output directory: $directory"
    probe=$(mktemp "$directory/.sharewise-write-check.XXXXXXXX") ||
        die "Output directory is not writable: $directory"
    rm -f -- "$probe"
    [[ ! -d "$directory/$filename" ]] || die "Output path is a directory: $directory/$filename"
    printf '%s/%s\n' "${directory%/}" "$filename"
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

# Copy just the backend/frontend projects into temporary build space. Always use
# the CURRENT working tree (including local source edits), never a git archive
# that silently omits changes since the last commit. No source enters the bundle.
copy_project_sources() {
    local source="$1" destination="$2"
    [[ -f "$source/src/backend/favsdemobe/pom.xml" ]] || die "Missing backend project in: $source"
    [[ -f "$source/src/frontend/package-lock.json" ]] || die "Missing frontend project in: $source"
    mkdir -p "$destination"
    tar -C "$source" \
        --exclude='.git' --exclude='node_modules' --exclude='.next' \
        --exclude='target' --exclude='out' --exclude='build' \
        --exclude='coverage' --exclude='.env' --exclude='.env.*' \
        --exclude='*.tsbuildinfo' --exclude='next-env.d.ts' \
        -cf - src/backend/favsdemobe src/frontend | tar -xf - -C "$destination"
}

# Store a small dependency fingerprint instead of embedding the repository.
# A changed POM, Maven wrapper, package.json or lockfile invalidates the bundle.
write_dependency_manifest() {
    local source="$1" root="$2"
    (cd "$source" && sha256sum \
        src/backend/favsdemobe/pom.xml \
        src/backend/favsdemobe/.mvn/wrapper/maven-wrapper.properties \
        src/frontend/package.json \
        src/frontend/package-lock.json) > "$root/manifest/dependencies.sha256"
}

check_dependency_manifest() {
    local source="$1" root="$2"
    [[ -f "$root/manifest/dependencies.sha256" ]] || die 'Dependency manifest not found in bundle.'
    (cd "$source" && sha256sum -c "$root/manifest/dependencies.sha256") ||
        die 'Source dependency descriptors differ from this bundle. Regenerate the bundle from the updated repository.'
}

prepare_dependencies() {
    local root="$1" source="$2"
    setup_env "$root"
    note 'Downloading backend dependencies, transitive plugins, and build/test artifacts'
    (
        cd "$source/src/backend/favsdemobe"
        "$root/toolchains/maven/bin/mvn" -B -ntp "-Dmaven.repo.local=$root/cache/maven" dependency:go-offline
        "$root/toolchains/maven/bin/mvn" -B -ntp "-Dmaven.repo.local=$root/cache/maven" clean verify
    )
    note 'Downloading frontend dependencies (Linux x64 optional native packages included)'
    run_npm "$root" --prefer-online "$source"
}

# The verification command accepts a separately supplied source tree. It does
# not need git history and does not modify the caller's checkout.
verify_bundle() (
    local root="$1" source="$2" verify_temp
    root=$(cd "$root" && pwd -P) || die "Cannot locate bundle directory: $root"
    source=$(cd "$source" && pwd -P) || die "Cannot locate source directory: $source"
    check_toolchains "$root"
    check_dependency_manifest "$source" "$root"
    setup_env "$root"
    verify_temp=$(mktemp -d)
    trap 'rm -rf -- "$verify_temp"' EXIT
    note 'Verifying bundled caches against a clean offline build of the supplied source'
    copy_project_sources "$source" "$verify_temp/source"
    run_maven "$root" -o "$verify_temp/source"
    run_npm "$root" --offline "$verify_temp/source"
    note 'Offline Maven and npm builds passed.'
)

pack_bundle() {
    local jdk='' node='' maven='' output='' cache_dir='' repo temp root archive sha
    repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
    [[ -f "$repo/src/backend/favsdemobe/pom.xml" ]] || die 'Run pack from the project checkout containing this script.'
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --jdk|--node|--maven|--output|--cache-dir)
                [[ $# -ge 2 ]] || die "Missing argument for $1"
                case "$1" in
                    --jdk) jdk="$2" ;; --node) node="$2" ;;
                    --maven) maven="$2" ;;
                    --output) [[ -n "$2" ]] || die '--output requires a nonempty path'; output="$2" ;;
                    --cache-dir) cache_dir="$2" ;;
                esac
                shift 2 ;;
            *) die "Unknown argument: $1" ;;
        esac
    done
    for archive in "$jdk" "$node" "$maven"; do
        [[ -z "$archive" || -f "$archive" ]] || die "Archive not found: $archive"
    done
    [[ -n "$output" ]] || output="$repo/.offline-handoff-cache/sharewise-offline-deps-linux-x64.tar.gz"
    output=$(resolve_output_path "$output")
    temp=$(mktemp -d)
    # Store the resolved temporary path in the trap: locals are gone when EXIT runs.
    trap "rm -rf -- '$temp'" EXIT
    root="$temp/bundle"
    mkdir -p "$root/toolchains" "$root/cache" "$root/manifest"
    [[ -n "$cache_dir" ]] || cache_dir="$repo/.offline-handoff-cache/toolchains"
    mkdir -p "$cache_dir"
    cache_dir=$(cd "$cache_dir" && pwd -P)
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
    write_dependency_manifest "$repo" "$root"
    note 'Preparing dependencies from a temporary source copy (sources are not packaged)'
    copy_project_sources "$repo" "$temp/online-source"
    prepare_dependencies "$root" "$temp/online-source"
    rm -rf "$temp/online-source"
    verify_bundle "$root" "$repo"
    cat > "$root/README-OFFLINE.md" <<'README'
# ShareWise Linux x86-64 offline dependency bundle

This archive intentionally contains NO application source or Git history.
Obtain the matching ShareWise source separately (checkout or source ZIP).
Source edits do not invalidate this bundle unless dependency descriptors change.

To verify after unpacking on Linux x86-64:

    ./verify-offline.sh verify . --source /path/to/sharewise

The verifier compares the source's Maven/npm dependency descriptors against
manifest/dependencies.sha256, then runs Maven (-o clean verify) and npm
(ci --offline, lint, build) from a fresh temporary copy of the supplied source.
It does not modify the supplied checkout and never downloads dependencies.

To develop against a separate source checkout, from the extracted bundle:

    export JAVA_HOME="$PWD/toolchains/jdk"
    export PATH="$JAVA_HOME/bin:$PWD/toolchains/maven/bin:$PWD/toolchains/node/bin:$PATH"
    export NPM_CONFIG_CACHE="$PWD/cache/npm"

From the backend directory, run Maven with:

    mvn -o -Dmaven.repo.local="/path/to/bundle/cache/maven" clean verify

From the frontend directory, run:

    npm ci --offline --include=optional --no-audit --no-fund
    npm run lint && npm run build

Use the absolute bundle path for the Maven cache argument. Never rely on the
receiving machine's global package caches for reproducible verification.

Contents: toolchains/jdk, toolchains/maven, toolchains/node, cache/maven,
cache/npm, manifest/dependencies.sha256, manifest/toolchain-archive-sha256.txt,
and verify-offline.sh. No source/, .git/, node_modules/ or build output.

The archives are for Linux x86-64/glibc. Containers or database integration tests
may additionally require OS-level tools, images, PostgreSQL and credentials.
README
    note "Packaging dependency-only bundle: $output"
    (cd "$root" && tar -czf "$output" .)
    (cd "$(dirname "$output")" && sha256sum "$(basename "$output")") > "$output.sha256"
    sha=$(cat "$output.sha256")
    note "Complete: $sha"
}

verify_args() {
    [[ $# -ge 1 ]] || die 'verify requires the extracted bundle path and --source /path/to/repo.'
    local root="$1" source=''
    shift
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --source)
                [[ $# -ge 2 && -n "$2" ]] || die '--source requires a directory.'
                source="$2"; shift 2 ;;
            *) die "Unknown verify argument: $1" ;;
        esac
    done
    [[ -n "$source" ]] || die 'verify requires --source /path/to/repo (sources are not bundled).'
    verify_bundle "$root" "$source"
}

main() {
    umask 077
    require_host
    [[ $# -gt 0 ]] || { usage; exit 2; }
    case "$1" in
        pack) shift; require_pack_tools; pack_bundle "$@" ;;
        verify) shift; verify_args "$@" ;;
        -h|--help|help) usage ;;
        *) usage; die "Unsupported action: $1" ;;
    esac
}
main "$@"
