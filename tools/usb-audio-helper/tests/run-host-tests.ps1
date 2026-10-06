$ErrorActionPreference = 'Stop'
$fifoHelperRoot = Split-Path -Parent $PSScriptRoot
$fifoTestBuild = Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Force -Path $fifoTestBuild | Out-Null
$fifoProduction = Join-Path $fifoHelperRoot 'src\com\codaki\usbaudio\PcmFifo.java'
$fifoTests = Join-Path $PSScriptRoot 'com\codaki\usbaudio\PcmFifoTest.java'
& javac --release 8 -encoding UTF-8 -d $fifoTestBuild $fifoProduction $fifoTests
if ($LASTEXITCODE -ne 0) { throw 'PCM FIFO host test compilation failed.' }
& java -classpath $fifoTestBuild com.codaki.usbaudio.PcmFifoTest
if ($LASTEXITCODE -ne 0) { throw 'PCM FIFO host tests failed.' }
