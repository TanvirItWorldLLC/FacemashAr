#!/bin/bash
# FaceMeshAR Build Script
# Run from project root: ./build_apk.sh

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

echo "=== FaceMeshAR Build Script ==="
echo "Project: $PROJECT_DIR"

# Check for gradle wrapper
if [ ! -f "./gradlew" ]; then
    echo "Generating Gradle wrapper..."
    gradle wrapper --gradle-version 8.5
fi

# Make wrapper executable
chmod +x ./gradlew

# Download model if missing
if [ ! -f "app/src/main/assets/face_landmarker.task" ]; then
    echo "Model not found, downloading..."
    ./download_model.sh
fi

echo "Building Debug APK..."
./gradlew assembleDebug

echo "Building Release APK..."
./gradlew assembleRelease

echo ""
echo "=== Build Complete ==="
echo "Debug APK:  app/build/outputs/apk/debug/app-debug.apk"
echo "Release APK: app/build/outputs/apk/release/app-release.apk"
echo ""
echo "Install on device:"
echo "  adb install -r app/build/outputs/apk/debug/app-debug.apk"