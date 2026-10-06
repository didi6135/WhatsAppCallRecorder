$ErrorActionPreference = 'Stop'
$compatibilityRoot = Split-Path -Parent $PSScriptRoot
$compatibilityBuild = Join-Path $PSScriptRoot 'build\compatibility'
New-Item -ItemType Directory -Force -Path $compatibilityBuild | Out-Null
$compatibilitySources = @(
  (Join-Path $compatibilityRoot 'src\com\codaki\usbaudio\ShellAudioCompatibility.java'),
  (Join-Path $compatibilityRoot 'src\com\codaki\usbaudio\AudioCaptureApi.java'),
  (Join-Path $PSScriptRoot 'com\codaki\usbaudio\ShellAudioCompatibilityTest.java'),
  (Join-Path $PSScriptRoot 'com\codaki\usbaudio\AudioCaptureApiTest.java')
)
& javac --release 8 -encoding UTF-8 -d $compatibilityBuild $compatibilitySources
if ($LASTEXITCODE -ne 0) { throw 'Shell compatibility host compilation failed.' }
& java -classpath $compatibilityBuild com.codaki.usbaudio.ShellAudioCompatibilityTest
if ($LASTEXITCODE -ne 0) { throw 'Shell compatibility host tests failed.' }
& java -classpath $compatibilityBuild com.codaki.usbaudio.AudioCaptureApiTest
if ($LASTEXITCODE -ne 0) { throw 'Audio API preflight host tests failed.' }
