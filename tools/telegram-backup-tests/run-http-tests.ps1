param([string]$JsonJar)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (!$JsonJar) {
  $JsonJar = Get-ChildItem (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.json/json/20250517') -Recurse -Filter '*.jar' |
    Select-Object -First 1 -ExpandProperty FullName
}
if (!$JsonJar -or !(Test-Path -LiteralPath $JsonJar)) { throw 'Pass the resolved org.json host dependency.' }
$output = Join-Path $repoRoot 'artifacts/telegram-http-host-classes'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$native = Join-Path $repoRoot 'android/app/src/main/java/com/didi4164/WhatsAppCallRecorder'
$sources = @('RecordingNames.java', 'TelegramBackupFailure.java', 'TelegramBotProtocol.java', 'TelegramWavParts.java', 'TelegramBotHttp.java') |
  ForEach-Object { Join-Path $native $_ }
& javac -encoding UTF-8 -cp $JsonJar -d $output @sources (Join-Path $PSScriptRoot 'TelegramBotHttpTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Telegram HTTP host compile failed.' }
& java -cp "$output;$JsonJar" com.didi4164.WhatsAppCallRecorder.TelegramBotHttpTest
if ($LASTEXITCODE -ne 0) { throw 'Telegram fake HTTPS transport checks failed.' }
