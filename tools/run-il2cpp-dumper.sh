#!/usr/bin/env bash
set -euo pipefail

# Run this on a machine with .NET installed and internet access to download
# the Il2CppDumper release.
#
# It expects the APK research artifacts in /tmp/ala-mobile-research/:
#   - /tmp/ala-mobile-research/native/lib/arm64-v8a/libil2cpp.so
#   - /tmp/ala-mobile-research/il2cpp/assets/bin/Data/Managed/Metadata/global-metadata.dat

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/.."
OUTPUT_DIR="$REPO_ROOT/il2cpp-dumps/v8.0.0"
LIBIL2CPP="/tmp/ala-mobile-research/native/lib/arm64-v8a/libil2cpp.so"
METADATA="/tmp/ala-mobile-research/il2cpp/assets/bin/Data/Managed/Metadata/global-metadata.dat"

mkdir -p "$OUTPUT_DIR"

if [[ ! -f "$LIBIL2CPP" ]]; then
    echo "Missing $LIBIL2CPP; run the APK extraction first." >&2
    exit 1
fi

if [[ ! -f "$METADATA" ]]; then
    echo "Missing $METADATA; run the APK extraction first." >&2
    exit 1
fi

IL2CPP_DUMPER_VERSION="v6.7.46"
IL2CPP_DUMPER_DIR="$REPO_ROOT/.tools/Il2CppDumper-$IL2CPP_DUMPER_VERSION"
IL2CPP_DUMPER_DLL="$IL2CPP_DUMPER_DIR/Il2CppDumper.dll"

if [[ ! -f "$IL2CPP_DUMPER_DLL" ]]; then
    mkdir -p "$REPO_ROOT/.tools"
    curl -L -o "/tmp/il2cppdumper.zip" "https://github.com/Perfare/Il2CppDumper/releases/download/$IL2CPP_DUMPER_VERSION/Il2CppDumper-net6-v6.7.46.zip"
    unzip "/tmp/il2cppdumper.zip" -d "$IL2CPP_DUMPER_DIR"
    rm "/tmp/il2cppdumper.zip"
fi

echo "Running Il2CppDumper..."
dotnet "$IL2CPP_DUMPER_DLL" "$LIBIL2CPP" "$METADATA" "$OUTPUT_DIR"

echo "Dump complete. Output: $OUTPUT_DIR"
echo "Next: extract offsets into app/src/main/kotlin/tools/alamobile/mod/offsets/OffsetTable.kt"
