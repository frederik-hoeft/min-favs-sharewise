#!/usr/bin/env bash
# Prepare and verify reusable offline Linux x86-64 build dependencies.
# Preparation requires bash, tar, Python 3, wget, and checksum utilities.
# Offline verification uses tar, Python 3 (for the Maven ZIP), and checksum tools; no wget.
set -Eeuo pipefail

usage() {
    cat <<'USAGE'
Usage:
  scripts/create-offline-handoff.sh pack \
    [--output /path/to/sharewise-offline-deps-linux-x64.tar.gz] \
    [--cache-dir /path/to/toolchain-download-cache] \
    [--jdk /path/to/jdk-25-linux-x64.tar.gz] \
    [--node /path/to/node-v24-linux-x64.tar.xz] \
    [--maven /path/to/apache-maven-3.10.0-bin.zip] \
    [--deps all|maven|node]

  ./verify-offline.sh verify /path/to/extracted-dependencies \
    --source /path/to/sharewise [--toolchains /path/to/original-archives]

'pack' uses the checkout containing this script to resolve Maven/npm dependencies,
then validates clean offline builds. By default it packages both Maven and npm
caches. --deps maven or --deps node packages and verifies only the selected
cache. Dependency hashes and a verifier are included. Only the toolchains
required by the selection are provided separately AS THEIR ORIGINAL ARCHIVES
in a sibling toolchains/ folder.
No Git repository, source snapshot, node_modules, or build output is included.

Source is supplied independently (Git checkout, ZIP, etc.) when verifying or
working offline. Its Maven/npm dependency manifests must match the packaged
hashes. Ordinary source changes do NOT require a new dependency archive.

By default wget fetches the latest Temurin JDK 25 and Node 24 patch releases,
and Maven 3.10.0. Toolchain archives are cached in the Git-ignored
.offline-handoff-cache/toolchains/ directory and checksum-verified. Use --jdk,
--node or --maven to supply a local archive instead (verify those yourself).

Output defaults to .offline-handoff-cache/sharewise-offline-deps-linux-x64.tar.gz
inside the checkout, with original toolchain archives placed alongside it in
toolchains/. Relative --output paths resolve against the caller's cwd.
Requires a network connection only for pack; verify uses strict offline builds.
The receiving machine needs the dependency tarball and only the toolchain
files listed in its manifest. Selective packs use distinct default filenames.
USAGE
}
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
note() { printf '\n>>> %s\n' "$*" >&2; }

# Older handoffs had no selection manifest and always contained both caches.
read_dependency_selection() {
    local root="$1" selection='all'
    if [[ -f "$root/manifest/dependency-selection.txt" ]]; then
        selection=$(<"$root/manifest/dependency-selection.txt")
    fi
    case "$selection" in
        all|maven|node) printf '%s\n' "$selection" ;;
        *) die "Invalid dependency selection in bundle: $selection" ;;
    esac
}
uses_maven() { [[ "$1" != node ]]; }
uses_node() { [[ "$1" != maven ]]; }

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
    local tools="$1" selection="$2" jdk_version node_version maven_version
    if uses_maven "$selection"; then
        [[ -x "$tools/jdk/bin/java" && -x "$tools/jdk/bin/javac" ]] || die 'Invalid JDK archive.'
        [[ -x "$tools/maven/bin/mvn" ]] || die 'Invalid Maven archive.'
        jdk_version=$("$tools/jdk/bin/java" -version 2>&1 | sed -n '1p')
        case "$jdk_version" in
            *'version "25.'*|*'version "25"'*|*'version "25+'*) ;;
            *) die "Expected Java 25, got: $jdk_version" ;;
        esac
        maven_version=$(JAVA_HOME="$tools/jdk" "$tools/maven/bin/mvn" -version | sed -n '1p')
        [[ "$maven_version" == *'3.10.0'* ]] || die "Expected Maven 3.10.0, got: $maven_version"
        note "Backend toolchains: $jdk_version, $maven_version"
    fi
    if uses_node "$selection"; then
        [[ -x "$tools/node/bin/node" && -x "$tools/node/bin/npm" ]] || die 'Invalid Node archive.'
        node_version=$("$tools/node/bin/node" --version)
        [[ "$node_version" == v24.* ]] || die "Expected Node 24, got: $node_version"
        note "Frontend toolchain: $node_version"
    fi
}

# The handoff only references the vendor archives required by its caches.
# The order is stable: JDK, Node, Maven for the legacy 'all' case.
write_toolchain_manifest() {
    local root="$1" selection="$2" jdk="$3" node="$4" maven="$5" archive
    local -a archives=()
    mkdir -p "$root/manifest"
    : > "$root/manifest/toolchain-files.txt"
    : > "$root/manifest/toolchain-archive-sha256.txt"
    if uses_maven "$selection"; then archives+=("$jdk"); fi
    if uses_node "$selection"; then archives+=("$node"); fi
    if uses_maven "$selection"; then archives+=("$maven"); fi
    for archive in "${archives[@]}"; do
        basename -- "$archive" >> "$root/manifest/toolchain-files.txt"
        (cd "$(dirname "$archive")" && sha256sum "$(basename "$archive")") >> "$root/manifest/toolchain-archive-sha256.txt"
    done
}

check_archive_name() {
    local name="$1"
    [[ "$name" =~ ^[A-Za-z0-9][A-Za-z0-9._+-]*$ && "$name" != *..* ]] ||
        die "Unsafe original toolchain filename in manifest: $name"
}

# Verify and extract only the original archives needed for this bundle.
extract_verified_toolchains() {
    local bundle="$1" archives="$2" tools="$3" staging="$4" selection="$5" archive index=0
    local -a names=() checksums=()
    [[ -f "$bundle/manifest/toolchain-files.txt" && -f "$bundle/manifest/toolchain-archive-sha256.txt" ]] ||
        die 'Original toolchain metadata is missing from the dependency bundle.'
    mapfile -t names < "$bundle/manifest/toolchain-files.txt"
    case "$selection" in
        all) [[ ${#names[@]} -eq 3 ]] || die 'Expected JDK, Node and Maven archive filenames.' ;;
        maven) [[ ${#names[@]} -eq 2 ]] || die 'Expected JDK and Maven archive filenames.' ;;
        node) [[ ${#names[@]} -eq 1 ]] || die 'Expected Node archive filename.' ;;
    esac
    mapfile -t checksums < "$bundle/manifest/toolchain-archive-sha256.txt"
    [[ ${#checksums[@]} -eq ${#names[@]} ]] || die 'Toolchain checksum manifest length differs from the selected archive list.'
    for index in "${!names[@]}"; do
        archive="${names[index]}"
        check_archive_name "$archive"
        # GNU sha256sum format: 64 hex digits, two spaces, then filename.
        [[ "${checksums[index]:0:64}" =~ ^[a-fA-F0-9]{64}$ && \
           "${checksums[index]:64:2}" == '  ' && \
           "${checksums[index]:66}" == "$archive" ]] ||
            die "Toolchain checksum entry does not match required archive: $archive"
        [[ -f "$archives/$archive" ]] || die "Missing original toolchain archive: $archives/$archive"
    done
    index=0
    if uses_maven "$selection"; then
        case "${names[index]}" in *.tar.gz|*.tgz) ;; *) die 'Expected a gzipped JDK archive.' ;; esac
        ((index+=1))
    fi
    if uses_node "$selection"; then
        case "${names[index]}" in *.tar.xz|*.txz) ;; *) die 'Expected an xz-compressed Node.js archive.' ;; esac
        ((index+=1))
    fi
    if uses_maven "$selection"; then
        case "${names[index]}" in *.zip) ;; *) die 'Expected a Maven ZIP archive.' ;; esac
    fi
    note 'Checking SHA-256 of required original toolchain archives'
    (cd "$archives" && sha256sum -c "$bundle/manifest/toolchain-archive-sha256.txt") ||
        die 'A toolchain archive is corrupted or does not match the dependency bundle.'
    index=0
    if uses_maven "$selection"; then
        extract_toolchain "$archives/${names[index]}" "$tools/jdk" "$staging/jdk"
        ((index+=1))
    fi
    if uses_node "$selection"; then
        extract_toolchain "$archives/${names[index]}" "$tools/node" "$staging/node"
        ((index+=1))
    fi
    if uses_maven "$selection"; then
        extract_toolchain "$archives/${names[index]}" "$tools/maven" "$staging/maven"
        chmod +x "$tools/maven/bin/mvn"
    fi
    check_toolchains "$tools" "$selection"
}

# Only the selected cache and matching language tools enter the build environment.
setup_env() {
    local bundle="$1" tools="$2" selection="$3"
    if uses_maven "$selection"; then
        export JAVA_HOME="$tools/jdk"
        export MAVEN_USER_HOME="$bundle/cache/maven-home"
        export PATH="$tools/jdk/bin:$tools/maven/bin:$PATH"
        mkdir -p "$bundle/cache/maven" "$bundle/cache/maven-home"
    fi
    if uses_node "$selection"; then
        export PATH="$tools/node/bin:$PATH"
        export NPM_CONFIG_CACHE="$bundle/cache/npm"
        export NPM_CONFIG_AUDIT=false NPM_CONFIG_FUND=false
        export NEXT_TELEMETRY_DISABLED=1
        mkdir -p "$bundle/cache/npm"
    fi
    export CI=1
}

run_maven() {
    local bundle="$1" tools="$2" mode="$3" source="$4"
    (
        cd "$source/src/backend/sharewise"
        "$tools/maven/bin/mvn" -B -ntp "-Dmaven.repo.local=$bundle/cache/maven" "$mode" clean verify
    )
}

run_npm() {
    local tools="$1" mode="$2" source="$3"
    (
        cd "$source/src/frontend"
        # --offline is strict; --prefer-offline would still allow network access.
        "$tools/node/bin/npm" ci "$mode" --include=optional --no-audit --no-fund
        "$tools/node/bin/npm" run lint
        "$tools/node/bin/npm" run build
    )
}

# Copy only projects selected for the clean build; never package sources.
copy_project_sources() {
    local source="$1" destination="$2" selection="$3"
    local -a projects=()
    if uses_maven "$selection"; then
        [[ -f "$source/src/backend/sharewise/pom.xml" ]] || die "Missing backend project in: $source"
        projects+=(src/backend/sharewise)
    fi
    if uses_node "$selection"; then
        [[ -f "$source/src/frontend/package-lock.json" ]] || die "Missing frontend project in: $source"
        projects+=(src/frontend)
    fi
    mkdir -p "$destination"
    tar -C "$source" \
        --exclude='.git' --exclude='node_modules' --exclude='.next' \
        --exclude='target' --exclude='out' --exclude='build' \
        --exclude='coverage' --exclude='.env' --exclude='.env.*' \
        --exclude='*.tsbuildinfo' --exclude='next-env.d.ts' \
        -cf - "${projects[@]}" | tar -xf - -C "$destination"
}

# Fingerprint only the dependency descriptors relevant to the selected cache.
write_dependency_manifest() {
    local source="$1" root="$2" selection="$3"
    local -a descriptors=()
    if uses_maven "$selection"; then
        descriptors+=(src/backend/sharewise/pom.xml src/backend/sharewise/.mvn/wrapper/maven-wrapper.properties)
    fi
    if uses_node "$selection"; then
        descriptors+=(src/frontend/package.json src/frontend/package-lock.json)
    fi
    (cd "$source" && sha256sum "${descriptors[@]}") > "$root/manifest/dependencies.sha256"
}

check_dependency_manifest() {
    local source="$1" root="$2"
    [[ -f "$root/manifest/dependencies.sha256" ]] || die 'Dependency manifest not found in bundle.'
    (cd "$source" && sha256sum -c "$root/manifest/dependencies.sha256") ||
        die 'Source dependency descriptors differ from this bundle. Regenerate the bundle from the updated repository.'
}

prepare_dependencies() {
    local bundle="$1" tools="$2" source="$3" selection="$4"
    setup_env "$bundle" "$tools" "$selection"
    if uses_maven "$selection"; then
        note 'Downloading backend dependencies, transitive plugins, and build/test artifacts'
        (
            cd "$source/src/backend/sharewise"
            "$tools/maven/bin/mvn" -B -ntp "-Dmaven.repo.local=$bundle/cache/maven" dependency:go-offline
            "$tools/maven/bin/mvn" -B -ntp "-Dmaven.repo.local=$bundle/cache/maven" clean verify
        )
    fi
    if uses_node "$selection"; then
        note 'Downloading frontend dependencies (Linux x64 optional native packages included)'
        run_npm "$tools" --prefer-online "$source"
    fi
}

# Unpack only required toolchains and run the selected strict offline checks.
verify_bundle() (
    local bundle="$1" source="$2" archives="$3" verify_temp tools selection
    bundle=$(cd "$bundle" && pwd -P) || die "Cannot locate dependency bundle directory: $bundle"
    source=$(cd "$source" && pwd -P) || die "Cannot locate source directory: $source"
    archives=$(cd "$archives" && pwd -P) || die "Cannot locate toolchain archive directory: $archives"
    selection=$(read_dependency_selection "$bundle")
    check_dependency_manifest "$source" "$bundle"
    verify_temp=$(mktemp -d)
    trap 'rm -rf -- "$verify_temp"' EXIT
    tools="$verify_temp/tools"
    extract_verified_toolchains "$bundle" "$archives" "$tools" "$verify_temp/extract" "$selection"
    setup_env "$bundle" "$tools" "$selection"
    note "Verifying $selection dependencies against a clean offline build of the supplied source"
    copy_project_sources "$source" "$verify_temp/source" "$selection"
    if uses_maven "$selection"; then run_maven "$bundle" "$tools" -o "$verify_temp/source"; fi
    if uses_node "$selection"; then run_npm "$tools" --offline "$verify_temp/source"; fi
    note "Offline $selection build checks passed."
)

# Copy vendor archives without recompression to the output sidecar folder.
# A normal default run already caches them there, so no duplicate copy occurs.
publish_toolchain_archive() {
    local archive="$1" destination="$2" source_digest target_digest partial
    source_digest=$(sha256sum "$archive" | cut -d' ' -f1)
    if [[ -e "$destination" ]]; then
        [[ "$archive" -ef "$destination" ]] && return
        target_digest=$(sha256sum "$destination" | cut -d' ' -f1)
        [[ "$source_digest" == "$target_digest" ]] && return
    fi
    partial="$destination.tmp.$$"
    cp -- "$archive" "$partial" || die "Cannot copy original toolchain archive: $archive"
    target_digest=$(sha256sum "$partial" | cut -d' ' -f1)
    [[ "$source_digest" == "$target_digest" ]] || die "Copied toolchain archive failed SHA-256: $archive"
    mv -f -- "$partial" "$destination"
}

pack_bundle() {
    local jdk='' node='' maven='' output='' cache_dir='' selection='all' repo temp root archive sha tools sidecars
    repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --jdk|--node|--maven|--output|--cache-dir|--deps)
                [[ $# -ge 2 ]] || die "Missing argument for $1"
                case "$1" in
                    --jdk) jdk="$2" ;; --node) node="$2" ;;
                    --maven) maven="$2" ;;
                    --output) [[ -n "$2" ]] || die '--output requires a nonempty path'; output="$2" ;;
                    --cache-dir) cache_dir="$2" ;;
                    --deps)
                        case "$2" in all|maven|node) selection="$2" ;;
                            *) die '--deps must be all, maven or node.' ;; esac ;;
                esac
                shift 2 ;;
            *) die "Unknown argument: $1" ;;
        esac
    done
    if uses_maven "$selection"; then
        [[ -f "$repo/src/backend/sharewise/pom.xml" ]] || die 'Missing backend project.'
        [[ -z "$jdk" || -f "$jdk" ]] || die "Archive not found: $jdk"
        [[ -z "$maven" || -f "$maven" ]] || die "Archive not found: $maven"
    else
        [[ -z "$jdk" && -z "$maven" ]] || die '--deps node does not use --jdk or --maven.'
    fi
    if uses_node "$selection"; then
        [[ -f "$repo/src/frontend/package-lock.json" ]] || die 'Missing frontend project.'
        [[ -z "$node" || -f "$node" ]] || die "Archive not found: $node"
    else
        [[ -z "$node" ]] || die '--deps maven does not use --node.'
    fi
    if [[ -z "$output" ]]; then
        if [[ "$selection" == all ]]; then
            output="$repo/.offline-handoff-cache/sharewise-offline-deps-linux-x64.tar.gz"
        else
            output="$repo/.offline-handoff-cache/sharewise-offline-deps-$selection-linux-x64.tar.gz"
        fi
    fi
    output=$(resolve_output_path "$output")
    temp=$(mktemp -d)
    # Store the resolved temporary path in the trap: locals are gone when EXIT runs.
    trap "rm -rf -- '$temp'" EXIT
    root="$temp/bundle"
    mkdir -p "$root/cache" "$root/manifest"
    printf '%s\n' "$selection" > "$root/manifest/dependency-selection.txt"
    [[ -n "$cache_dir" ]] || cache_dir="$repo/.offline-handoff-cache/toolchains"
    mkdir -p "$cache_dir"
    cache_dir=$(cd "$cache_dir" && pwd -P)
    if uses_maven "$selection"; then
        if [[ -z "$jdk" ]]; then
            note 'Resolving latest GA Eclipse Temurin JDK 25 (Linux x64)'
            jdk=$(resolve_jdk "$cache_dir" "$temp")
        fi
        if [[ -z "$maven" ]]; then
            note 'Resolving Maven 3.10.0'
            maven=$(resolve_maven "$cache_dir" "$temp")
        fi
    fi
    if uses_node "$selection" && [[ -z "$node" ]]; then
        note 'Resolving latest Node.js 24 (Linux x64)'
        node=$(resolve_node "$cache_dir" "$temp")
    fi
    # The archive contains only caches and metadata, never extracted SDKs.
    write_toolchain_manifest "$root" "$selection" "$jdk" "$node" "$maven"
    write_dependency_manifest "$repo" "$root" "$selection"
    tools="$temp/tools"
    note 'Extracting required SDKs for dependency preparation (not for packaging)'
    if uses_maven "$selection"; then
        extract_toolchain "$jdk" "$tools/jdk" "$temp/extract-jdk"
        extract_toolchain "$maven" "$tools/maven" "$temp/extract-maven"
        chmod +x "$tools/maven/bin/mvn"
    fi
    if uses_node "$selection"; then
        extract_toolchain "$node" "$tools/node" "$temp/extract-node"
    fi
    check_toolchains "$tools" "$selection"
    cp "$repo/scripts/create-offline-handoff.sh" "$root/verify-offline.sh"
    chmod +x "$root/verify-offline.sh"
    note 'Preparing dependencies from a temporary source copy (sources are not packaged)'
    copy_project_sources "$repo" "$temp/online-source" "$selection"
    prepare_dependencies "$root" "$tools" "$temp/online-source" "$selection"
    rm -rf "$temp/online-source" "$tools" "$temp/extract-jdk" "$temp/extract-node" "$temp/extract-maven"
    # Publish only the original toolchains required by this bundle.
    sidecars="$(dirname "$output")/toolchains"
    mkdir -p "$sidecars"
    if uses_maven "$selection"; then
        publish_toolchain_archive "$jdk" "$sidecars/$(basename "$jdk")"
        publish_toolchain_archive "$maven" "$sidecars/$(basename "$maven")"
    fi
    if uses_node "$selection"; then
        publish_toolchain_archive "$node" "$sidecars/$(basename "$node")"
    fi
    verify_bundle "$root" "$repo" "$sidecars"
    cat > "$root/README-OFFLINE.md" <<README
# ShareWise Linux x86-64 offline dependencies

Dependency selection: **$selection** (also recorded in manifest/dependency-selection.txt).
This tarball contains only the selected Maven/npm cache, manifest files, and
verify-offline.sh. No source, node_modules, build output or extracted SDKs are
included. Toolchains are separately supplied as original vendor archives.

Transfer only the toolchains listed in manifest/toolchain-files.txt, with their
original filenames, to a sibling toolchains/ directory. Exact SHA-256 hashes
are in manifest/toolchain-archive-sha256.txt. Backend builds require Temurin
JDK 25 and Maven 3.10.0; frontend builds require Node.js 24 and npm.

Bring the separately supplied ShareWise source checkout or source ZIP.
Dependency descriptor checksums cover only the selected project(s).
For example, from the directory containing the dependency archive:

    mkdir -p deps
    tar -xzf $(basename "$output") -C deps
    deps/verify-offline.sh verify deps --source /path/to/sharewise

Use --toolchains /path/to/archives if the original SDK archives are elsewhere.
The verifier checks archive SHA-256 hashes and selected dependency descriptors,
then builds the selected project(s) with strict offline resolution in a clean
source copy. It requires Linux x86-64, bash, tar, sha256sum and basic Unix utilities,
plus Python 3 when verifying Maven (to extract its ZIP), but no installed SDK. It never downloads.

PostgreSQL, container images and system packages are separate dependencies.
README
    note "Packaging $selection dependencies: $output"
    (cd "$root" && tar -czf "$output" .)
    (cd "$(dirname "$output")" && sha256sum "$(basename "$output")") > "$output.sha256"
    sha=$(cat "$output.sha256")
    note "Complete: $sha"
}

verify_args() {
    [[ $# -ge 1 ]] || die 'verify requires the extracted bundle path and --source /path/to/repo.'
    local root="$1" source='' archives=''
    shift
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --source)
                [[ $# -ge 2 && -n "$2" ]] || die '--source requires a directory.'
                source="$2"; shift 2 ;;
            --toolchains)
                [[ $# -ge 2 && -n "$2" ]] || die '--toolchains requires a directory.'
                archives="$2"; shift 2 ;;
            *) die "Unknown verify argument: $1" ;;
        esac
    done
    [[ -n "$source" ]] || die 'verify requires --source /path/to/repo (sources are not bundled).'
    if [[ -z "$archives" ]]; then
        if [[ -d "$root/toolchains" ]]; then
            archives="$root/toolchains"
        else
            archives="$(dirname "$root")/toolchains"
        fi
    fi
    if uses_maven "$(read_dependency_selection "$root")"; then
        command -v python3 >/dev/null || die 'Python 3 is needed to unpack the original Maven ZIP.'
    fi
    verify_bundle "$root" "$source" "$archives"
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
