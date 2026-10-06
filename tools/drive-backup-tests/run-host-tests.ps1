param(
  [string]$GradleCache = 'C:\g\caches\modules-2\files-2.1',
  [string]$KotlinVersion = '2.0.21'
)
$ErrorActionPreference = 'Stop'
$driveRepo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$driveMain = Join-Path $driveRepo 'android\app\src\main\java\com\didi4164\WhatsAppCallRecorder'
$driveBuild = Join-Path $driveRepo 'artifacts\drive-host-tests'
New-Item -ItemType Directory -Force -Path $driveBuild | Out-Null
$driveSources = @(
  (Join-Path $driveMain 'DriveBackupPolicy.java'),
  (Join-Path $driveMain 'DriveHttpTransport.java'),
  (Join-Path $PSScriptRoot 'DriveBackupPolicyTest.java'),
  (Join-Path $PSScriptRoot 'DriveHttpTransportTest.java'),
  (Join-Path $PSScriptRoot 'stubs\android\content\Context.java'),
  (Join-Path $PSScriptRoot 'stubs\android\util\AtomicFile.java')
)
& javac --release 8 -encoding UTF-8 -d $driveBuild $driveSources
if ($LASTEXITCODE -ne 0) { throw 'Drive host Java compilation failed.' }
& java -classpath $driveBuild com.didi4164.WhatsAppCallRecorder.DriveBackupPolicyTest
if ($LASTEXITCODE -ne 0) { throw 'Drive policy host tests failed.' }
& java -classpath $driveBuild com.didi4164.WhatsAppCallRecorder.DriveHttpTransportTest
if ($LASTEXITCODE -ne 0) { throw 'Drive HTTP transport host tests failed.' }
function Find-DriveTestJar([string]$artifact, [string]$version = '') {
  $drivePath = Join-Path $GradleCache $artifact
  if ($version) { $drivePath = Join-Path $drivePath $version }
  $driveJar = Get-ChildItem -LiteralPath $drivePath -Recurse -Filter '*.jar' | Sort-Object FullName -Descending | Select-Object -First 1
  if (!$driveJar) { throw "Missing cached test dependency: $artifact $version. Resolve Android dependencies first; this script never downloads or builds Android." }
  return $driveJar.FullName
}
$driveStdlib = Find-DriveTestJar 'org.jetbrains.kotlin\kotlin-stdlib' $KotlinVersion
$driveJson = Find-DriveTestJar 'org.json\json'
$driveCompilerJars = @(
  (Find-DriveTestJar 'org.jetbrains.kotlin\kotlin-compiler-embeddable' $KotlinVersion),
  $driveStdlib,
  (Find-DriveTestJar 'org.jetbrains.kotlin\kotlin-script-runtime' $KotlinVersion),
  (Find-DriveTestJar 'org.jetbrains.kotlin\kotlin-reflect'),
  (Find-DriveTestJar 'org.jetbrains.kotlin\kotlin-daemon-embeddable' $KotlinVersion),
  (Find-DriveTestJar 'org.jetbrains.intellij.deps\trove4j'),
  (Find-DriveTestJar 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm'),
  (Find-DriveTestJar 'org.jetbrains\annotations')
)
$driveRuntime = @($driveBuild, $driveStdlib, $driveJson) -join [IO.Path]::PathSeparator
& java -classpath ($driveCompilerJars -join [IO.Path]::PathSeparator) org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 1.8 -classpath $driveRuntime -d $driveBuild (Join-Path $driveMain 'DriveBackupQueue.kt') (Join-Path $driveMain 'DriveBackupErrors.kt') (Join-Path $PSScriptRoot 'DriveBackupQueueHostTest.kt')
if ($LASTEXITCODE -ne 0) { throw 'Drive journal host Kotlin compilation failed.' }
& java -classpath $driveRuntime com.didi4164.WhatsAppCallRecorder.DriveBackupQueueHostTest $driveBuild
if ($LASTEXITCODE -ne 0) { throw 'Drive journal host tests failed.' }
Write-Output 'All Drive host checks passed. Synthetic HTTP and journal tests do not verify OAuth, Android WorkManager or a real Drive upload.'
