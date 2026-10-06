$ErrorActionPreference = 'Stop'
$attributionRoot = Split-Path -Parent $PSScriptRoot
$attributionBuild = Join-Path $PSScriptRoot 'build\attribution-monitor'
New-Item -ItemType Directory -Force -Path $attributionBuild | Out-Null
$attributionSources = @('CallAudioOwnerParser','CallAudioOwnerProbe','TelecomCallParser','TelecomCallProbe','CallAttributionFrame','CallAttributionMonitor') | ForEach-Object { Join-Path $attributionRoot "src\com\codaki\usbaudio\$_.java" }
$attributionSources += Join-Path $PSScriptRoot 'com\codaki\usbaudio\CallAttributionMonitorTest.java'
& javac --release 8 -encoding UTF-8 -d $attributionBuild $attributionSources
if ($LASTEXITCODE -ne 0) { throw 'Attribution monitor compilation failed.' }
& java -classpath $attributionBuild com.codaki.usbaudio.CallAttributionMonitorTest
if ($LASTEXITCODE -ne 0) { throw 'Attribution monitor tests failed.' }
