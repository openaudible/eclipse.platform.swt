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

    if [ "$name" == *.jnilib ]; then
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

# Create output JAR
echo "Creating JAR: $OUTPUT_JAR"
mkdir -p "$(dirname "$OUTPUT_JAR")"
(cd "$D3" && zip -q -r "$OUTPUT_JAR" .)

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
