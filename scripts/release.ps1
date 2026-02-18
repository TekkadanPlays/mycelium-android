# Release script for Ribbit Android
# Usage: .\scripts\release.ps1 <version> <version_code>

param(
    [Parameter(Mandatory=$true)]
    [string]$Version,
    
    [Parameter(Mandatory=$true)]
    [int]$VersionCode
)

Write-Host "🚀 Creating release for Ribbit v$Version (code: $VersionCode)" -ForegroundColor Green

# Update version in build.gradle.kts
$buildFile = "app\build.gradle.kts"
$content = Get-Content $buildFile -Raw
$content = $content -replace "versionCode = \d+", "versionCode = $VersionCode"
$content = $content -replace 'versionName = "[^"]*"', "versionName = `"$Version`""
Set-Content $buildFile $content

# Update obtanium.json
$obtaniumFile = "obtanium.json"
$obtaniumContent = Get-Content $obtaniumFile -Raw
$obtaniumContent = $obtaniumContent -replace '"version": "[^"]*"', "`"version`": `"$Version`""
$obtaniumContent = $obtaniumContent -replace '"versionCode": \d+', "`"versionCode`": $VersionCode"
$obtaniumContent = $obtaniumContent -replace '"releaseDate": "[^"]*"', "`"releaseDate`": `"$(Get-Date -Format 'yyyy-MM-dd')`""
Set-Content $obtaniumFile $obtaniumContent

Write-Host "✅ Updated version information" -ForegroundColor Green

# Build release APK
Write-Host "🔨 Building release APK..." -ForegroundColor Yellow
& .\gradlew.bat clean assembleRelease

if ($LASTEXITCODE -eq 0) {
    Write-Host "✅ Build successful!" -ForegroundColor Green
    Write-Host "📱 APK location: app\build\outputs\apk\release\app-release.apk" -ForegroundColor Cyan
    
    # Create release directory
    if (!(Test-Path "releases")) {
        New-Item -ItemType Directory -Path "releases"
    }
    Copy-Item "app\build\outputs\apk\release\app-release.apk" "releases\ribbit-v$Version.apk"
    
    Write-Host "📦 APK copied to: releases\ribbit-v$Version.apk" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Next steps:" -ForegroundColor Yellow
    Write-Host "1. Test the APK on your device"
    Write-Host "2. Commit and push changes:"
    Write-Host "   git add ."
    Write-Host "   git commit -m `"Release v$Version`""
    Write-Host "   git push"
    Write-Host "3. Create a GitHub release with tag v$Version"
    Write-Host "4. Upload the APK to the GitHub release"
    Write-Host "5. Update Obtanium repository URL if needed"
} else {
    Write-Host "❌ Build failed!" -ForegroundColor Red
    exit 1
}

