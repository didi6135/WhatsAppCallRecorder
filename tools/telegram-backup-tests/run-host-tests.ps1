param([string]$JsonJar)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (!$JsonJar) {
  $JsonJar = Get-ChildItem (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.json/json/20250517') -Recurse -Filter '*.jar' |
    Select-Object -First 1 -ExpandProperty FullName
}
if (!$JsonJar -or !(Test-Path -LiteralPath $JsonJar)) { throw 'Pass -JsonJar to the resolved org.json:json:20250517 host dependency.' }
$output = Join-Path $repoRoot 'artifacts/telegram-host-classes'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$native = Join-Path $repoRoot 'android/app/src/main/java/com/didi4164/WhatsAppCallRecorder'
$sources = @('RecordingNames.java', 'TelegramBackupFailure.java', 'TelegramBotProtocol.java', 'TelegramWavParts.java', 'TelegramBackupLedger.java', 'TelegramWorkerWakeupPolicy.java') |
  ForEach-Object { Join-Path $native $_ }
$tests = @('TelegramBackupCoreTest.java', 'TelegramBackupLedgerTest.java', 'TelegramWorkerWakeupPolicyTest.java') | ForEach-Object { Join-Path $PSScriptRoot $_ }
& javac -encoding UTF-8 -cp $JsonJar -d $output @sources @tests
if ($LASTEXITCODE -ne 0) { throw 'Telegram host compile failed.' }
& java -cp "$output;$JsonJar" com.didi4164.WhatsAppCallRecorder.TelegramBackupCoreTest
if ($LASTEXITCODE -ne 0) { throw 'Telegram protocol/WAV host checks failed.' }
& java -cp "$output;$JsonJar" com.didi4164.WhatsAppCallRecorder.TelegramBackupLedgerTest
if ($LASTEXITCODE -ne 0) { throw 'Telegram durable ledger host checks failed.' }
& java -cp "$output;$JsonJar" com.didi4164.WhatsAppCallRecorder.TelegramWorkerWakeupPolicyTest
if ($LASTEXITCODE -ne 0) { throw 'Telegram worker wakeup checks failed.' }
