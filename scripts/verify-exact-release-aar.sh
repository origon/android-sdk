#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 5 ]]; then
    echo "Usage: $0 <aar> <aar-sha256> <arm64-sha256> <armv7-sha256> <x86_64-sha256>" >&2
    exit 2
fi

AAR_PATH="$1"
EXPECTED_AAR_SHA256="$2"
ARM64_SHA256="$3"
ARMV7_SHA256="$4"
X86_64_SHA256="$5"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

for value in \
    "$EXPECTED_AAR_SHA256" "$ARM64_SHA256" "$ARMV7_SHA256" "$X86_64_SHA256"; do
    [[ "$value" =~ ^[0-9a-f]{64}$ ]] || {
        echo "ERROR: every expected hash must be one lowercase SHA-256" >&2
        exit 1
    }
done

[[ -f "$AAR_PATH" ]] || {
    echo "ERROR: exact release AAR not found: $AAR_PATH" >&2
    exit 1
}
command -v sha256sum >/dev/null || { echo "ERROR: sha256sum is required" >&2; exit 1; }
command -v unzip >/dev/null || { echo "ERROR: unzip is required" >&2; exit 1; }

actual_aar_sha256="$(sha256sum "$AAR_PATH" | awk '{print $1}')"
[[ "$actual_aar_sha256" == "$EXPECTED_AAR_SHA256" ]] || {
    echo "ERROR: AAR SHA-256 mismatch: expected $EXPECTED_AAR_SHA256, found $actual_aar_sha256" >&2
    exit 1
}

# The existing release verifier owns NDK revision, stripped-symbol, JNI export,
# retired-symbol, and 16 KiB alignment checks. Exact mode adds a byte identity
# check for the container and each ABI input; it does not weaken that census.
"$SCRIPT_DIR/verify-release-aar.sh" "$AAR_PATH"

staging="$(mktemp -d)"
trap 'rm -rf "$staging"' EXIT
mapfile -t native_entries < <(unzip -Z1 "$AAR_PATH" 'jni/*/libsession.so' | LC_ALL=C sort)
expected_entries=(
    jni/arm64-v8a/libsession.so
    jni/armeabi-v7a/libsession.so
    jni/x86_64/libsession.so
)
[[ "${native_entries[*]}" == "${expected_entries[*]}" ]] || {
    echo "ERROR: exact AAR must contain only the three supported libsession.so entries" >&2
    printf 'found: %s\n' "${native_entries[*]:-none}" >&2
    exit 1
}
unzip -q "$AAR_PATH" 'jni/*/libsession.so' -d "$staging"

declare -A expected_so_sha256=(
    [arm64-v8a]="$ARM64_SHA256"
    [armeabi-v7a]="$ARMV7_SHA256"
    [x86_64]="$X86_64_SHA256"
)
for abi in arm64-v8a armeabi-v7a x86_64; do
    library="$staging/jni/$abi/libsession.so"
    actual="$(sha256sum "$library" | awk '{print $1}')"
    [[ "$actual" == "${expected_so_sha256[$abi]}" ]] || {
        echo "ERROR: $abi libsession.so SHA-256 mismatch: expected ${expected_so_sha256[$abi]}, found $actual" >&2
        exit 1
    }
    echo "verified $abi SHA-256 $actual"
done

echo "verified exact AAR SHA-256 $actual_aar_sha256"
