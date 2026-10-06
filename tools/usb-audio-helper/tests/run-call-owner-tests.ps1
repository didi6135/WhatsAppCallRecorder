$ErrorActionPreference = 'Stop'
$callOwnerRoot = Split-Path -Parent $PSScriptRoot
$callOwnerBuild = Join-Path $PSScriptRoot 'build\call-owner'
New-Item -ItemType Directory -Force -Path $callOwnerBuild | Out-Null
$callOwnerSources = @(
  (Join-Path $callOwnerRoot 'src\com\codaki\usbaudio\CallAudioOwnerParser.java'),
  (Join-Path $callOwnerRoot 'src\com\codaki\usbaudio\CallAudioOwnerProbe.java'),
  (Join-Path $callOwnerRoot 'src\com\codaki\usbaudio\CallAudioOwnerMonitor.java'),
  (Join-Path $PSScriptRoot 'com\codaki\usbaudio\CallAudioOwnerTest.java')
)
& javac --release 8 -encoding UTF-8 -d $callOwnerBuild $callOwnerSources
if ($LASTEXITCODE -ne 0) { throw 'Call audio owner host compilation failed.' }
& java -classpath $callOwnerBuild com.codaki.usbaudio.CallAudioOwnerTest
if ($LASTEXITCODE -ne 0) { throw 'Call audio owner host tests failed.' }
