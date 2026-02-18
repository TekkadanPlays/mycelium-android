#!/bin/bash

# Release script for Ribbit Android
# Usage: ./scripts/release.sh <version> <version_code>

if [ $# -ne 2 ]; then
    echo "Usage: $0 <version> <version_code>"
    echo "Example: $0 1.0 1"
    exit 1
fi

VERSION=$1
VERSION_CODE=$2

echo "🚀 Creating release for Ribbit v$VERSION (code: $VERSION_CODE)"

# Update version in build.gradle.kts
sed -i "s/versionCode = [0-9]*/versionCode = $VERSION_CODE/" app/build.gradle.kts
sed -i "s/versionName = \"[^\"]*\"/versionName = \"$VERSION\"/" app/build.gradle.kts

# Update obtanium.json
sed -i "s/\"version\": \"[^\"]*\"/\"version\": \"$VERSION\"/" obtanium.json
sed -i "s/\"versionCode\": [0-9]*/\"versionCode\": $VERSION_CODE/" obtanium.json
sed -i "s/\"releaseDate\": \"[^\"]*\"/\"releaseDate\": \"$(date +%Y-%m-%d)\"/" obtanium.json

echo "✅ Updated version information"

# Build release APK
echo "🔨 Building release APK..."
./gradlew clean assembleRelease

if [ $? -eq 0 ]; then
    echo "✅ Build successful!"
    echo "📱 APK location: app/build/outputs/apk/release/app-release.apk"
    
    # Create release directory
    mkdir -p releases
    cp app/build/outputs/apk/release/app-release.apk "releases/ribbit-v$VERSION.apk"
    
    echo "📦 APK copied to: releases/ribbit-v$VERSION.apk"
    echo ""
    echo "Next steps:"
    echo "1. Test the APK on your device"
    echo "2. Commit and push changes:"
    echo "   git add ."
    echo "   git commit -m \"Release v$VERSION\""
    echo "   git push"
    echo "3. Create a GitHub release with tag v$VERSION"
    echo "4. Upload the APK to the GitHub release"
    echo "5. Update Obtanium repository URL if needed"
else
    echo "❌ Build failed!"
    exit 1
fi

