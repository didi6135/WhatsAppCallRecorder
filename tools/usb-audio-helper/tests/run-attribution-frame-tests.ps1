$ErrorActionPreference = 'Stop'
$attributionFrameRoot = Split-Path -Parent $PSScriptRoot
$attributionFrameBuild = Join-Path $PSScriptRoot 'build\attribution-frame'
New-Item -ItemType Directory -Force -Path $attributionFrameBuild | Out-Null
$attributionFrameSources = @(
  (Join-Path $attributionFrameRoot 'src\com\codaki\usbaudio\CallAttributionFrame.java'),
  (Join-Path $PSScriptRoot 'com\codaki\usbaudio\CallAttributionFrameTest.java')
)
& javac --release 8 -encoding UTF-8 -d $attributionFrameBuild $attributionFrameSources
if ($LASTEXITCODE -ne 0) { throw 'Call attribution frame host compilation failed.' }
& java -classpath $attributionFrameBuild com.codaki.usbaudio.CallAttributionFrameTest
if ($LASTEXITCODE -ne 0) { throw 'Call attribution frame host tests failed.' }
