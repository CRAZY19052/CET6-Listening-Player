param(
    [Parameter(Mandatory = $true)]
    [string]$Manifest,
    [Parameter(Mandatory = $true)]
    [string]$Sections,
    [string]$JdkHome = "C:\Android\jdk-17",
    [string]$AndroidSdk = "C:\Android\sdk",
    [string]$GradleHome = "C:\Android\gradle-8.11.1",
    [string]$OutputDirectory = ""
)

$ErrorActionPreference = "Stop"
$project = $PSScriptRoot

if (-not (Test-Path -LiteralPath $Manifest)) {
    throw "Audio manifest not found: $Manifest"
}
if (-not (Test-Path -LiteralPath $Sections)) {
    throw "Chapter markers not found: $Sections"
}

$env:JAVA_HOME = $JdkHome
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk
$env:Path = "$JdkHome\bin;$AndroidSdk\platform-tools;$env:Path"

Write-Host "Preparing private offline assets..."
python "$project\tools\build_catalog.py" `
    --manifest $Manifest `
    --sections $Sections `
    --project $project

$gradle = Join-Path $GradleHome "bin\gradle.bat"
if (-not (Test-Path -LiteralPath $gradle)) {
    $gradle = Join-Path $project "gradlew.bat"
}

Write-Host "Building debug APK..."
Push-Location $project
try {
    & $gradle assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

$apk = Join-Path $project "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path -LiteralPath $apk)) {
    throw "Expected APK was not produced: $apk"
}

$aapt = Join-Path $AndroidSdk "build-tools\35.0.0\aapt.exe"
$badging = & $aapt dump badging $apk
if ($badging -match "android.permission.INTERNET") {
    throw "Privacy check failed: APK requests INTERNET permission."
}

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path (Split-Path -Parent $project) "outputs"
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$finalApk = Join-Path $OutputDirectory "CET6听力舱.apk"
Copy-Item -LiteralPath $apk -Destination $finalApk -Force

$sizeMb = [math]::Round((Get-Item -LiteralPath $finalApk).Length / 1MB, 2)
Write-Host "APK: $finalApk"
Write-Host "Size: $sizeMb MiB"
