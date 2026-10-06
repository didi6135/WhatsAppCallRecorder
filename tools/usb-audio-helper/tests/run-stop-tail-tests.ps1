$ErrorActionPreference = 'Stop'
$tailHelperRoot = Split-Path -Parent $PSScriptRoot
$tailTestBuild = Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Force -Path $tailTestBuild | Out-Null
$tailProduction = @(Join-Path $tailHelperRoot 'src\com\codaki\usbaudio\PcmFifo.java'; Join-Path $tailHelperRoot 'src\com\codaki\usbaudio\PcmStopTail.java')
$tailTests = Join-Path $PSScriptRoot 'com\codaki\usbaudio\PcmStopTailTest.java'
& javac --release 8 -encoding UTF-8 -d $tailTestBuild $tailProduction $tailTests
if ($LASTEXITCODE -ne 0) { throw 'PCM stop tail host test compilation failed.' }
& java -classpath $tailTestBuild com.codaki.usbaudio.PcmStopTailTest
if ($LASTEXITCODE -ne 0) { throw 'PCM stop tail host tests failed.' }
