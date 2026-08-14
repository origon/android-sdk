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
unzip -q "$AAR_PATH" 'jni/*/libsession.so' -d "$staging"

for abi in arm64-v8a armeabi-v7a x86_64; do
    library="$staging/jni/$abi/libsession.so"
    [[ -f "$library" ]] || { echo "ERROR: AAR missing $abi/libsession.so"; exit 1; }

    sections="$($readelf -SW "$library")"
    [[ "$sections" != *".symtab"* ]] || { echo "ERROR: $abi still contains .symtab"; exit 1; }

    symbols="$($nm -D --defined-only "$library")"
    for export_name in \
        restoreActiveChats openChat registerPush unregisterPush \
        sessionLoaderStart directoryLoaderStart loaderNext loaderCancel loaderFree \
        removeCachedSession clearChatCache pruneChatCache clearChatCacheRoot \
        openChatWithIntent; do
        expected="Java_ai_origon_sdk_SessionBridge_${export_name}"
        [[ "$symbols" == *"$expected"* ]] || { echo "ERROR: $abi missing $expected"; exit 1; }
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
    echo "verified $abi: stripped, continuity/cache-first JNI exports present"
done
