param([string]$AndroidSdk = (Join-Path $env:LOCALAPPDATA 'Android\Sdk'), [string]$BuildName = 'probe-build')
$ErrorActionPreference = 'Stop'
$matrixRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if ($BuildName -notmatch '^[A-Za-z0-9_-]+$') { throw 'BuildName must be a single safe directory name.' }
$matrixBuild = Join-Path $matrixRoot ('artifacts\android-device-matrix\' + $BuildName)
$matrixClasses = Join-Path $matrixBuild 'classes'
$matrixDex = Join-Path $matrixBuild 'dex'
if (Test-Path -LiteralPath $matrixBuild) { throw 'Use a fresh owned probe-build directory to prevent stale class evidence.' }
$matrixSources = @(Get-ChildItem -LiteralPath (Join-Path $matrixRoot 'tools\usb-audio-helper\src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
$matrixSources += Join-Path $PSScriptRoot 'src\com\codaki\usbaudio\RuntimeAudioProbe.java'
$matrixSources = @($matrixSources | Sort-Object)
$matrixAndroidJar = Join-Path $AndroidSdk 'platforms\android-36\android.jar'
$matrixD8 = Join-Path $AndroidSdk 'build-tools\36.0.0\d8.bat'
$matrixD8Jar = Join-Path $AndroidSdk 'build-tools\36.0.0\lib\d8.jar'
$matrixJavac = (Get-Command javac -ErrorAction Stop).Source
foreach ($matrixInput in @($matrixAndroidJar, $matrixD8, $matrixD8Jar, $matrixJavac)) {
  if (-not (Test-Path -LiteralPath $matrixInput -PathType Leaf)) { throw ('Missing installed build dependency: ' + $matrixInput) }
}
$matrixSourceHashes = @($matrixSources | ForEach-Object {
  [ordered]@{ path = $_.Substring($matrixRoot.Length + 1).Replace('\', '/'); sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $_).Hash.ToLowerInvariant() }
})
New-Item -ItemType Directory -Force -Path $matrixClasses,$matrixDex | Out-Null
& javac -source 8 -target 8 -encoding UTF-8 -classpath $matrixAndroidJar -d $matrixClasses $matrixSources
if ($LASTEXITCODE -ne 0) { throw 'Runtime probe javac failed.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$matrixClassesJar = Join-Path $matrixBuild 'probe-classes.jar'
[IO.Compression.ZipFile]::CreateFromDirectory($matrixClasses, $matrixClassesJar)
# Use the real application's minimum dex API, not the historical USB-only API36.
& $matrixD8 --min-api 24 --lib $matrixAndroidJar --output $matrixDex $matrixClassesJar
if ($LASTEXITCODE -ne 0) { throw 'Runtime probe d8 failed.' }
$matrixProbeJar = Join-Path $matrixBuild 'runtime-audio-probe.jar'
[IO.Compression.ZipFile]::CreateFromDirectory($matrixDex, $matrixProbeJar)
for ($matrixIndex = 0; $matrixIndex -lt $matrixSources.Count; $matrixIndex++) {
  if ((Get-FileHash -Algorithm SHA256 -LiteralPath $matrixSources[$matrixIndex]).Hash.ToLowerInvariant() -ne $matrixSourceHashes[$matrixIndex].sha256) {
    throw 'A production/probe source changed during compilation; do not use this build as evidence.'
  }
}
$matrixGitHead = & git -C $matrixRoot rev-parse HEAD
if ($LASTEXITCODE -ne 0) { throw 'Could not bind probe build to checkout Git HEAD.' }
$matrixManifest = [ordered]@{
  schema = 1; gitHead = $matrixGitHead.Trim(); createdUtc = [DateTime]::UtcNow.ToString('o')
  scope = 'Production helper methods compiled into a separate test harness; no OEM/user-build or full-app capability claim.'
  javaSourceLevel = 8; javaTargetLevel = 8; minimumDexApi = 24; frameworkCompileApi = 36
  sources = $matrixSourceHashes
  inputs = @($matrixAndroidJar, $matrixD8, $matrixD8Jar, $matrixJavac) | ForEach-Object {
    [ordered]@{ path = $_; sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $_).Hash.ToLowerInvariant() }
  }
  output = [ordered]@{ file = 'runtime-audio-probe.jar'; bytes = (Get-Item -LiteralPath $matrixProbeJar).Length; sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $matrixProbeJar).Hash.ToLowerInvariant() }
}
$matrixManifestJson = $matrixManifest | ConvertTo-Json -Depth 8
[IO.File]::WriteAllText((Join-Path $matrixBuild 'build-manifest.json'), $matrixManifestJson + [Environment]::NewLine, (New-Object Text.UTF8Encoding($false)))
Get-FileHash -Algorithm SHA256 -LiteralPath $matrixProbeJar
