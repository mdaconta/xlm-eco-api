# Native PowerShell contract tests with task-local fake Java processes.
$ErrorActionPreference = 'Stop'
$launcher = Join-Path (Split-Path -Parent $PSScriptRoot) 'local-server.ps1'
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('xlm launcher test ' + [guid]::NewGuid().ToString('N'))
$oldPath = $env:PATH
$oldProfile = $env:USERPROFILE
$oldConfig = [Environment]::GetEnvironmentVariable('XLM_CONFIG_DIR', 'Process')
$oldSecrets = [Environment]::GetEnvironmentVariable('XLM_SECRETS_DIR', 'Process')
$oldCapture = $env:XLM_TEST_CAPTURE
$hadConfig = Test-Path Env:XLM_CONFIG_DIR
$hadSecrets = Test-Path Env:XLM_SECRETS_DIR
$hadCapture = Test-Path Env:XLM_TEST_CAPTURE
$PSNativeCommandUseErrorActionPreference = $true
function Assert-True($Value, [string]$Message) { if (-not $Value) { throw "Assertion failed: $Message" } }
function Assert-Rejected([scriptblock]$Body, [string]$Pattern) {
    $rejected = $false
    try { & $Body | Out-Null } catch { $rejected = $_.Exception.Message -match $Pattern }
    Assert-True $rejected "Expected rejection: $Pattern"
}
try {
    $config = Join-Path $fixture 'config with spaces'
    $secrets = Join-Path $fixture 'secrets with spaces'
    $bin = Join-Path $fixture 'fake java'
    foreach ($directory in @($config, $secrets, $bin)) { New-Item -ItemType Directory -Path $directory -Force | Out-Null }
    Set-Content -LiteralPath (Join-Path $config 'server.properties') -Value '# synthetic config'
    Set-Content -LiteralPath (Join-Path $secrets 'admin.token') -Value 'synthetic fixture only; never read by launcher'
    $jar = Join-Path $fixture 'fake server with spaces.jar'
    Set-Content -LiteralPath $jar -Value 'synthetic jar'
    $env:XLM_TEST_CAPTURE = Join-Path $fixture 'invocation.txt'
    Set-Content -LiteralPath (Join-Path $bin 'java.cmd') -Value @'
@echo off
> "%XLM_TEST_CAPTURE%" echo %*
>> "%XLM_TEST_CAPTURE%" echo %XLM_CONFIG_DIR%
>> "%XLM_TEST_CAPTURE%" echo %XLM_SECRETS_DIR%
exit /b 23
'@
    $env:PATH = $bin + ';' + $oldPath
    $argsForScript = @{ConfigDir=$config; SecretsDir=$secrets; JarPath=$jar}
    $beforeToken = (Get-Item -LiteralPath (Join-Path $secrets 'admin.token')).LastWriteTimeUtc
    $output = & $launcher @argsForScript
    Assert-True ($output -match 'startup prerequisites found') 'Check reports prerequisites'
    Assert-True (-not (Test-Path -LiteralPath $env:XLM_TEST_CAPTURE)) 'Check never invokes Java'
    $env:XLM_CONFIG_DIR = 'original config'; Remove-Item Env:XLM_SECRETS_DIR -ErrorAction SilentlyContinue
    & $launcher -Action Run @argsForScript | Out-Null
    Assert-True ($LASTEXITCODE -eq 23) 'Native nonzero exit status preserved'
    Assert-True ($env:XLM_CONFIG_DIR -eq 'original config') 'Existing environment restored'
    Assert-True (-not (Test-Path Env:XLM_SECRETS_DIR)) 'Previously absent environment restored'
    $capture = Get-Content -LiteralPath $env:XLM_TEST_CAPTURE
    Assert-True ($capture[0].Contains('-Djavax.net.ssl.trustStoreType=Windows-ROOT')) 'Windows trust flag'
    Assert-True ($capture[0].Contains('-Djavax.net.ssl.trustStore=NONE')) 'Certificate validation retained'
    Assert-True ($capture[0].Contains('-jar "' + $jar + '"')) 'JAR with spaces remains one argument'
    Assert-True ($capture[1] -eq $config -and $capture[2] -eq $secrets) 'Explicit process environment'
    Assert-True ((Get-Item -LiteralPath (Join-Path $secrets 'admin.token')).LastWriteTimeUtc -eq $beforeToken) 'Secret file unchanged'
    $env:XLM_CONFIG_DIR=$config; $env:XLM_SECRETS_DIR=$secrets
    & $launcher -JarPath $jar | Out-Null
    Assert-Rejected { & $launcher -ConfigDir 'relative' -SecretsDir $secrets -JarPath $jar } 'absolute'
    Assert-Rejected { & $launcher -ConfigDir 'C:relative' -SecretsDir $secrets -JarPath $jar } 'absolute'
    Assert-Rejected { & $launcher -ConfigDir $config -SecretsDir $secrets -JarPath (Join-Path $fixture 'missing.jar') } 'missing'
    $missing = Join-Path $fixture 'missing required file'; New-Item -ItemType Directory -Path $missing | Out-Null
    Assert-Rejected { & $launcher -ConfigDir $missing -SecretsDir $secrets -JarPath $jar } 'server.properties'
    Assert-Rejected { & $launcher -ConfigDir $config -SecretsDir $missing -JarPath $jar } 'admin.token'
    $repo = Join-Path $fixture 'synthetic checkout'; $nested = Join-Path $repo 'nested'
    New-Item -ItemType Directory -Path $nested -Force | Out-Null; Set-Content -LiteralPath (Join-Path $repo '.git') -Value 'gitdir: fixture'
    Assert-Rejected { & $launcher -ConfigDir $nested -SecretsDir $secrets -JarPath $jar } 'outside every Git'
    $linked = Join-Path $fixture 'linked checkout'; New-Item -ItemType Junction -Path $linked -Target $repo | Out-Null
    Assert-Rejected { & $launcher -ConfigDir (Join-Path $linked 'nested') -SecretsDir $secrets -JarPath $jar } 'outside every Git'
    $env:USERPROFILE = Join-Path $fixture 'synthetic profile'
    New-Item -ItemType Directory -Path (Join-Path $env:USERPROFILE '.xlm') -Force | Out-Null
    New-Item -ItemType Junction -Path (Join-Path $env:USERPROFILE '.xlm\config') -Target $config | Out-Null
    New-Item -ItemType Junction -Path (Join-Path $env:USERPROFILE '.xlm\secrets') -Target $secrets | Out-Null
    Remove-Item Env:XLM_CONFIG_DIR,Env:XLM_SECRETS_DIR
    & $launcher -JarPath $jar | Out-Null
    $badBin=Join-Path $fixture 'bad java';New-Item -ItemType Directory -Path $badBin | Out-Null
    Set-Content -LiteralPath (Join-Path $badBin 'java.exe') -Value 'not an executable'
    $env:PATH=$badBin; $env:XLM_CONFIG_DIR='before failure'
    Assert-Rejected { & $launcher -Action Run @argsForScript } 'cannot|failed|valid|application|program'
    Assert-True ($env:XLM_CONFIG_DIR -eq 'before failure') 'Invocation failure restores previous environment'
    Assert-True (-not (Test-Path Env:XLM_SECRETS_DIR)) 'Invocation failure restores absent environment'
    $env:PATH=$missing
    Assert-Rejected { & $launcher @argsForScript } 'java'
    Write-Output 'Local-server launcher tests passed.'
} finally {
    $env:PATH=$oldPath; $env:USERPROFILE=$oldProfile
    if ($hadConfig) { [Environment]::SetEnvironmentVariable('XLM_CONFIG_DIR',$oldConfig,'Process') } else { Remove-Item Env:XLM_CONFIG_DIR -ErrorAction SilentlyContinue }
    if ($hadSecrets) { [Environment]::SetEnvironmentVariable('XLM_SECRETS_DIR',$oldSecrets,'Process') } else { Remove-Item Env:XLM_SECRETS_DIR -ErrorAction SilentlyContinue }
    if ($hadCapture) { [Environment]::SetEnvironmentVariable('XLM_TEST_CAPTURE',$oldCapture,'Process') } else { Remove-Item Env:XLM_TEST_CAPTURE -ErrorAction SilentlyContinue }
    # Validate the one owned fixture path before recursive cleanup; never traverse its junctions.
    $base=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')+'\'
    $target=[IO.Path]::GetFullPath($fixture)
    if (-not $target.StartsWith($base,[StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe test cleanup path.' }
    foreach ($link in @((Join-Path $fixture 'linked checkout'),(Join-Path $fixture 'synthetic profile\.xlm\config'),(Join-Path $fixture 'synthetic profile\.xlm\secrets'))) {
        if (Test-Path -LiteralPath $link) { [IO.Directory]::Delete($link) }
    }
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
}
