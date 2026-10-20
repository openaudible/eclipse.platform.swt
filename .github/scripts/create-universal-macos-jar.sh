#!/bin/bash
set -e

# Script to create macOS Universal JAR from x86_64 and aarch64 JARs
# Based on the Java implementation for creating universal binaries

if [ "$#" -ne 3 ]; then
    echo "Usage: $0 <intel-jar> <arm-jar> <output-jar>"
    exit 1
fi

INTEL_JAR="$1"
ARM_JAR="$2"

# Convert output path to absolute path
if [[ "$3" = /* ]]; then
    OUTPUT_JAR="$3"
else
    OUTPUT_JAR="$(pwd)/$3"
fi

echo "Creating Universal Binary JAR"
echo "  Intel JAR: $INTEL_JAR"
echo "  ARM JAR:   $ARM_JAR"
echo "  Output:    $OUTPUT_JAR"

# Create temp directories
TEMP_DIR=$(mktemp -d)
D1="$TEMP_DIR/d1"
D2="$TEMP_DIR/d2"
D3="$TEMP_DIR/d3"

mkdir -p "$D1" "$D2" "$D3"

cleanup() {
    echo "Cleaning up temp directories..."
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

# Unzip both JARs
echo "Extracting Intel JAR..."
unzip -q "$INTEL_JAR" -d "$D1"

echo "Extracting ARM JAR..."
unzip -q "$ARM_JAR" -d "$D2"

# Compare class files (simple SHA check)
echo "Comparing class files..."
for class_file in $(find "$D1" -name "*.class"); do
    rel_path="${class_file#$D1/}"
    class2="$D2/$rel_path"

    if [ -f "$class2" ]; then
        sha1=$(shasum "$class_file" | cut -d' ' -f1)
        sha2=$(shasum "$class2" | cut -d' ' -f1)
        if [ "$sha1" != "$sha2" ]; then
            echo "ERROR: Class files differ: $rel_path"
            echo "  Intel SHA: $sha1"
            echo "  ARM SHA:   $sha2"
            exit 1
        fi
    fi
done
echo "✓ All class files match"

# Files to skip
SKIP_FILES=("fragment.properties" ".api_description")

# Process all files
echo "Creating universal binary..."
for file in "$D1"/*; do
    name=$(basename "$file")

    # Check if should skip
    skip=false
    for skip_name in "${SKIP_FILES[@]}"; do
        if [ "$name" == "$skip_name" ]; then
            skip=true
            break
        fi
    done

    if [ "$skip" = true ]; then
        echo "  Skipping: $name"
        continue
    fi

    a1="$D1/$name"
    a2="$D2/$name"
    a3="$D3/$name"

    if [ ! -e "$a2" ]; then
        echo "  Warning: Missing in ARM JAR: $name"
        continue
    fi

    if [[ "$name" == *.jnilib ]]; then
        echo "  Creating universal binary: $name"
        lipo -create "$a1" "$a2" -output "$a3"
        echo "    Architectures: $(lipo -info "$a3")"
    elif [ -d "$file" ]; then
        echo "  Copying directory: $name"
        cp -r "$a1" "$a3"
    else
        echo "  Copying file: $name"
        cp "$a1" "$a3"
    fi
done

# Add marker file
echo "Universal binary created $(date)" > "$D3/universal.txt"

# Remove signature files to avoid verification errors
echo "Cleaning manifest signatures..."
if [ -d "$D3/META-INF" ]; then
    rm -f "$D3/META-INF"/*.SF
    rm -f "$D3/META-INF"/*.RSA
    rm -f "$D3/META-INF"/*.DSA
    rm -f "$D3/META-INF"/*.EC
    echo "✓ Removed signature files"
fi

# The manifest was copied from the Intel JAR and claims x86_64. Library.isLoadable()
# compares SWT-Arch to the runtime arch and C's static initializer calls
# System.exit(1) on mismatch, so an arch-specific manifest kills SWT on Apple Silicon.
# Unfold continuation lines, drop the arch claims, then refold to the 72-byte limit.
universalize_manifest() {
    local manifest="$1"
    tr -d '\r' < "$manifest" \
        | awk 'NR > 1 && /^ / { buf = buf substr($0, 2); next }
               { if (NR > 1) print buf; buf = $0 }
               END { print buf }' \
        | sed -e '/^SWT-Arch:/d' \
              -e '/^Eclipse-PlatformFilter:/s/ *(osgi\.arch=[^)]*)//' \
              -e '/^Bundle-SymbolicName:/s/macosx\.x86_64/macosx.universal/' \
              -e '/^Automatic-Module-Name:/s/macosx\.x86_64/macosx.universal/' \
        | awk '{ line = $0
                 if (length(line) <= 70) { printf "%s\r\n", line; next }
                 printf "%s\r\n", substr(line, 1, 70); line = substr(line, 71)
                 while (length(line) > 69) { printf " %s\r\n", substr(line, 1, 69); line = substr(line, 70) }
                 printf " %s\r\n", line }' \
        > "$manifest.tmp"
    mv "$manifest.tmp" "$manifest"
}

echo "Rewriting manifest for universal binary..."
universalize_manifest "$D3/META-INF/MANIFEST.MF"
if grep -q -e '^SWT-Arch:' -e 'osgi\.arch=' "$D3/META-INF/MANIFEST.MF"; then
    echo "ERROR: Manifest still declares an architecture"
    exit 1
fi
echo "✓ Removed SWT-Arch and osgi.arch from manifest"

# Create output JAR (META-INF first so JarInputStream finds the manifest)
echo "Creating JAR: $OUTPUT_JAR"
mkdir -p "$(dirname "$OUTPUT_JAR")"
(cd "$D3" && zip -q -r "$OUTPUT_JAR" META-INF && zip -q -r "$OUTPUT_JAR" . -x 'META-INF/*')

# Validate
if [ -f "$OUTPUT_JAR" ]; then
    size=$(stat -f%z "$OUTPUT_JAR" 2>/dev/null || stat -c%s "$OUTPUT_JAR" 2>/dev/null)
    echo "✓ Created universal JAR: $OUTPUT_JAR ($size bytes)"

    # List .jnilib files and their architectures
    echo ""
    echo "Universal binaries in JAR:"
    unzip -l "$OUTPUT_JAR" | grep '\.jnilib$' | awk '{print $4}'
else
    echo "ERROR: Failed to create JAR"
    exit 1
fi

echo "✓ Universal binary creation complete"
