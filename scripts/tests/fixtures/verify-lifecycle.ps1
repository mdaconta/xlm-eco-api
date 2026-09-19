param($ServerProcess)
$ErrorActionPreference = 'Stop'
$ready = Join-Path $env:XLM_CONFIG_DIR 'fixture-ready.txt'
$deadline = [DateTime]::UtcNow.AddSeconds(10)
while (-not (Test-Path -LiteralPath $ready)) {
    if ($ServerProcess.HasExited) { throw 'Fixture Java exited before readiness.' }
    if ([DateTime]::UtcNow -gt $deadline) { throw 'Fixture readiness timed out.' }
    Start-Sleep -Milliseconds 25
}
if ([int](Get-Content -LiteralPath $ready) -ne $ServerProcess.Id) { throw 'Fixture identity mismatch.' }
if ($ServerProcess.StartTimeUtc.Kind -ne [DateTimeKind]::Utc) { throw 'Creation time must be UTC.' }
if ($ServerProcess.HasExited) { throw 'Expected a live verification-owned server.' }
# Persist only test evidence, not launcher ownership state.
[pscustomobject]@{ Id = $ServerProcess.Id; StartTimeUtc = $ServerProcess.StartTimeUtc.ToString('o') } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $env:XLM_CONFIG_DIR 'callback-evidence.json')
switch ($env:XLM_LIFECYCLE_CASE) {
    'throw' { throw 'Synthetic verification failure after startup.' }
    'native-failure' { & $env:ComSpec /d /c 'exit /b 17'; return }
    'explicit-zero' { exit 0 }
    'abrupt-owner' { while ($true) { Start-Sleep -Seconds 1 } }
}
