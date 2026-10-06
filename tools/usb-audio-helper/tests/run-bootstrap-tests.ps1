$ErrorActionPreference = 'Stop'
$bootstrapHelperRoot = Split-Path -Parent $PSScriptRoot
$bootstrapTestBuild = Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Force -Path $bootstrapTestBuild | Out-Null
$bootstrapProduction = Join-Path $bootstrapHelperRoot 'src\com\codaki\usbaudio\BootstrapHandoff.java'
$bootstrapTests = Join-Path $PSScriptRoot 'com\codaki\usbaudio\BootstrapHandoffTest.java'
& javac --release 8 -encoding UTF-8 -d $bootstrapTestBuild $bootstrapProduction $bootstrapTests
if ($LASTEXITCODE -ne 0) { throw 'Bootstrap handoff host test compilation failed.' }
& java -classpath $bootstrapTestBuild com.codaki.usbaudio.BootstrapHandoffTest
if ($LASTEXITCODE -ne 0) { throw 'Bootstrap handoff host tests failed.' }
