$ErrorActionPreference = 'Stop'
$telecomRoot = Split-Path -Parent $PSScriptRoot
$telecomBuild = Join-Path $PSScriptRoot 'build\telecom'
New-Item -ItemType Directory -Force -Path $telecomBuild | Out-Null
$telecomSources = @(
  (Join-Path $telecomRoot 'src\com\codaki\usbaudio\TelecomCallParser.java'),
  (Join-Path $telecomRoot 'src\com\codaki\usbaudio\TelecomCallProbe.java'),
  (Join-Path $PSScriptRoot 'com\codaki\usbaudio\TelecomCallTest.java')
)
& javac --release 8 -encoding UTF-8 -d $telecomBuild $telecomSources
if ($LASTEXITCODE -ne 0) { throw 'Telecom call host compilation failed.' }
& java -classpath $telecomBuild com.codaki.usbaudio.TelecomCallTest
if ($LASTEXITCODE -ne 0) { throw 'Telecom call host tests failed.' }
