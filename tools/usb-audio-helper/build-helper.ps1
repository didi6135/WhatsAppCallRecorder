$ErrorActionPreference = 'Stop'
$helperSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$helperRoot = $PSScriptRoot
$helperClasses = Join-Path $helperRoot 'build\classes'
$helperDex = Join-Path $helperRoot 'build\dex'
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Write-HelperJar([string] $source, [string] $target) {
  if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target }
  [IO.Compression.ZipFile]::CreateFromDirectory($source, $target)
}
New-Item -ItemType Directory -Force -Path $helperClasses, $helperDex | Out-Null
$helperSources = @(Get-ChildItem -LiteralPath (Join-Path $helperRoot 'src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
& javac -source 8 -target 8 -encoding UTF-8 -classpath (Join-Path $helperSdk 'platforms\android-36\android.jar') -d $helperClasses $helperSources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
Write-HelperJar $helperClasses (Join-Path $helperRoot 'build\helper-classes.jar')
& (Join-Path $helperSdk 'build-tools\36.0.0\d8.bat') --min-api 36 --lib (Join-Path $helperSdk 'platforms\android-36\android.jar') --output $helperDex (Join-Path $helperRoot 'build\helper-classes.jar')
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
Write-HelperJar $helperDex (Join-Path $helperRoot 'usb-audio-helper.jar')
Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $helperRoot 'usb-audio-helper.jar')
