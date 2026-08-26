#!/usr/bin/env bash
set -euo pipefail

AAR_PATH="${1:-sdk/build/outputs/aar/sdk-release.aar}"
NDK_ROOT="${ANDROID_NDK_HOME:-${ANDROID_HOME:-}/ndk/27.2.12479018}"

if [[ ! -f "$AAR_PATH" ]]; then
    echo "ERROR: release AAR not found: $AAR_PATH"
    exit 1
fi
if [[ ! -d "$NDK_ROOT" ]]; then
    echo "ERROR: NDK 27.2.12479018 not found; set ANDROID_NDK_HOME"
    exit 1
fi
ndk_revision="$(awk -F ' = ' '$1 == "Pkg.Revision" { print $2 }' "$NDK_ROOT/source.properties")"
if [[ "$ndk_revision" != "27.2.12479018" ]]; then
    echo "ERROR: expected NDK 27.2.12479018, found ${ndk_revision:-unknown}"
    exit 1
fi

case "$(uname -s)" in
    Linux*) host_tag="linux-x86_64" ;;
    Darwin*) host_tag="darwin-x86_64" ;;
    *) echo "ERROR: unsupported host"; exit 1 ;;
esac

tool_bin="$NDK_ROOT/toolchains/llvm/prebuilt/$host_tag/bin"
readelf="$tool_bin/llvm-readelf"
nm="$tool_bin/llvm-nm"
for tool in "$readelf" "$nm"; do
    [[ -x "$tool" ]] || { echo "ERROR: missing tool: $tool"; exit 1; }
done

staging="$(mktemp -d)"
trap 'rm -rf "$staging"' EXIT
unzip -q "$AAR_PATH" 'classes.jar' 'jni/*/libsession.so' -d "$staging"

command -v javap >/dev/null || { echo "ERROR: javap is required"; exit 1; }
public_api="$(javap -classpath "$staging/classes.jar" ai.origon.sdk.OrigonClient)"
[[ "$public_api" == *"public final void sendDtmf(java.lang.String, char);"* ]] || {
    echo "ERROR: public AAR API is missing OrigonClient.sendDtmf(String, char)"
    exit 1
}
for unsupported in receiveDtmf onDtmf dtmfReceived; do
    [[ "$public_api" != *"$unsupported"* ]] || {
        echo "ERROR: public AAR exposes unsupported DTMF receive API $unsupported"
        exit 1
    }
done

bridge_source="sdk/src/main/kotlin/ai/origon/sdk/SessionBridge.kt"
[[ -f "$bridge_source" ]] || { echo "ERROR: missing JNI producer: $bridge_source"; exit 1; }
bridge_exports="$(sed -nE 's/.*external fun ([A-Za-z0-9_]+).*/\1/p' "$bridge_source")"
[[ "$bridge_exports" == *"sendDtmf"* ]] || {
    echo "ERROR: JNI producer is missing sendDtmf"
    exit 1
}

for abi in arm64-v8a armeabi-v7a x86_64; do
    library="$staging/jni/$abi/libsession.so"
    [[ -f "$library" ]] || { echo "ERROR: AAR missing $abi/libsession.so"; exit 1; }

    sections="$($readelf -SW "$library")"
    [[ "$sections" != *".symtab"* ]] || { echo "ERROR: $abi still contains .symtab"; exit 1; }

    symbols="$($nm -D --defined-only "$library")"
    while IFS= read -r export_name; do
        [[ -n "$export_name" ]] || continue
        expected="Java_ai_origon_sdk_SessionBridge_${export_name}"
        [[ "$symbols" == *"$expected"* ]] || { echo "ERROR: $abi missing $expected"; exit 1; }
    done <<< "$bridge_exports"
    for retired_name in getSessions getSession openChatWithIntent; do
        retired="Java_ai_origon_sdk_SessionBridge_${retired_name}"
        [[ "$symbols" != *"$retired"* ]] || {
            echo "ERROR: $abi still exports retired $retired"
            exit 1
        }
    done

    if [[ "$abi" != "armeabi-v7a" ]]; then
        alignments="$("$readelf" -lW "$library" | awk '$1 == "LOAD" { print $NF }')"
        [[ -n "$alignments" ]] || { echo "ERROR: $abi has no PT_LOAD segments"; exit 1; }
        while read -r alignment; do
            [[ "$alignment" == "0x4000" ]] || {
                echo "ERROR: $abi PT_LOAD alignment is $alignment, expected 0x4000"
                exit 1
            }
        done <<< "$alignments"
    fi
    echo "verified $abi: stripped, continuity/cache-first/DTMF JNI exports present"
done
