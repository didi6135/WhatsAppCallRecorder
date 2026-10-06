$ErrorActionPreference = 'Stop'
$contextHelperRoot = Split-Path -Parent $PSScriptRoot
$contextTestBuild = Join-Path $PSScriptRoot 'build'
$contextAndroidJar = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platforms\android-36\android.jar'
New-Item -ItemType Directory -Force -Path $contextTestBuild | Out-Null
$contextProduction = Join-Path $contextHelperRoot 'src\com\codaki\usbaudio\ShellAudioContext.java'
$contextTest = Join-Path $PSScriptRoot 'com\codaki\usbaudio\ShellApplicationPolicyTest.java'
& javac --release 8 -encoding UTF-8 -classpath $contextAndroidJar -d $contextTestBuild $contextProduction $contextTest
if ($LASTEXITCODE -ne 0) { throw 'Shell context test compilation failed.' }
& java -classpath ($contextTestBuild + ';' + $contextAndroidJar) com.codaki.usbaudio.ShellApplicationPolicyTest
if ($LASTEXITCODE -ne 0) { throw 'Shell context host tests failed.' }
