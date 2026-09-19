$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$launcher = Join-Path (Split-Path -Parent $PSScriptRoot) 'local-server.ps1'
$helper = Join-Path (Split-Path -Parent $PSScriptRoot) 'OwnedServerProcess.cs'
$callback = Join-Path $PSScriptRoot 'fixtures\verify-lifecycle.ps1'
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('xlm owned lifecycle ' + [guid]::NewGuid().ToString('N'))
$caseName = 'setup'
$passed = 0
$oldCase = [Environment]::GetEnvironmentVariable('XLM_LIFECYCLE_CASE', 'Process')
$hadCase = Test-Path Env:XLM_LIFECYCLE_CASE
$oldConfig = [Environment]::GetEnvironmentVariable('XLM_CONFIG_DIR', 'Process')
$oldSecrets = [Environment]::GetEnvironmentVariable('XLM_SECRETS_DIR', 'Process')
$hadConfig = Test-Path Env:XLM_CONFIG_DIR
$hadSecrets = Test-Path Env:XLM_SECRETS_DIR
$unrelated = $null
$persistentOwner = $null
$abruptOwner = $null
$abruptJava = $null
function Assert-True($Value, [string]$Message) { if (-not $Value) { throw "${caseName}: $Message" } }
function Passed([string]$Name) { $script:passed++; Write-Output "PASS $Name" }
function Wait-File([string]$Path) {
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    while (-not (Test-Path -LiteralPath $Path)) {
        if ([DateTime]::UtcNow -gt $deadline) { throw "Timed out waiting for fixture evidence: $caseName" }
        Start-Sleep -Milliseconds 25
    }
}
function New-Case([string]$Name) {
    $directory = Join-Path $fixture $Name
    $config = Join-Path $directory 'config'
    $secrets = Join-Path $directory 'secrets'
    New-Item -ItemType Directory -Path $config,$secrets -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $config 'server.properties') -Value '# synthetic fixture'
    Set-Content -LiteralPath (Join-Path $secrets 'admin.token') -Value 'synthetic-token-not-read-by-launcher'
    $jar = Join-Path $directory 'server fixture.jar'
    Copy-Item -LiteralPath $script:builtJar -Destination $jar
    return @{ ConfigDir=$config; SecretsDir=$secrets; JarPath=$jar }
}
function Assert-Released($Arguments) {
    # An exclusive read/write open proves that Windows no longer has the JAR locked.
    $exclusive = [IO.File]::Open($Arguments.JarPath, 'Open', 'ReadWrite', 'None')
    $exclusive.Dispose()
    Remove-Item -LiteralPath $Arguments.JarPath
    Assert-True (-not (Test-Path -LiteralPath $Arguments.JarPath)) 'JAR could not be deleted after cleanup.'
    $unexpected = @(Get-ChildItem -LiteralPath $Arguments.ConfigDir -Force | Where-Object Name -NotIn @('server.properties','fixture-ready.txt','callback-evidence.json'))
    Assert-True ($unexpected.Count -eq 0) 'Unexpected launcher PID/state file remains.'
}
function Shell-Command($Arguments, [string]$Action) {
    $q = { param($text) "'" + $text.Replace("'", "''") + "'" }
    $command = '& ' + (& $q $launcher) + ' -Action ' + $Action +
        ' -ConfigDir ' + (& $q $Arguments.ConfigDir) + ' -SecretsDir ' + (& $q $Arguments.SecretsDir) +
        ' -JarPath ' + (& $q $Arguments.JarPath)
    if ($Action -eq 'Verify') { $command += ' -VerificationScript ' + (& $q $callback) }
    return [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
}
try {
    New-Item -ItemType Directory -Path $fixture -Force | Out-Null
    $classes = Join-Path $fixture 'classes'; New-Item -ItemType Directory -Path $classes | Out-Null
    & javac -d $classes (Join-Path $PSScriptRoot 'fixtures\LifecycleServer.java')
    Assert-True ($LASTEXITCODE -eq 0) 'Fixture compilation failed.'
    $builtJar = Join-Path $fixture 'fixture template.jar'
    & jar --create --file $builtJar --main-class LifecycleServer -C $classes .
    Assert-True ($LASTEXITCODE -eq 0) 'Fixture JAR packaging failed.'
    if (-not ('Xlm.Launcher.OwnedServerProcess' -as [type])) { Add-Type -Path $helper }
    $java = (Get-Command java.exe -CommandType Application | Select-Object -First 1).Source
    $shell = (Get-Process -Id $PID).Path

    # The unrelated JVM has a separate retained native process handle and job.
    $otherArgs = New-Case 'unrelated'
    $env:XLM_CONFIG_DIR = $otherArgs.ConfigDir
    $unrelated = [Xlm.Launcher.OwnedServerProcess]::Start($java, @('-jar',$otherArgs.JarPath), $fixture)
    Wait-File (Join-Path $otherArgs.ConfigDir 'fixture-ready.txt')

    foreach ($caseName in @('success','throw','native-failure','explicit-zero')) {
        $arguments = New-Case $caseName
        $env:XLM_LIFECYCLE_CASE = $caseName
        $env:XLM_CONFIG_DIR = 'previous configuration'
        Remove-Item Env:XLM_SECRETS_DIR -ErrorAction SilentlyContinue
        $failed = $false
        try { & $launcher -Action Verify -VerificationScript $callback @arguments | Out-Null }
        catch {
            $failed = $true
            if ($caseName -eq 'throw') { Assert-True ($_.Exception.Message -match 'Synthetic verification failure') 'Original callback failure was lost.' }
            elseif ($caseName -eq 'native-failure') { Assert-True ($_.Exception.Message -match 'native exit code 17') 'Native failure was not preserved.' }
            else { throw }
        }
        Assert-True ($failed -eq ($caseName -in @('throw','native-failure'))) 'Unexpected callback outcome.'
        Assert-True ($env:XLM_CONFIG_DIR -eq 'previous configuration') 'Existing environment was not restored.'
        Assert-True (-not (Test-Path Env:XLM_SECRETS_DIR)) 'Absent environment was not restored.'
        Assert-True (-not $unrelated.View.HasExited) 'Cleanup terminated the unrelated JVM.'
        Assert-Released $arguments
        Passed "verify-$caseName-cleanup-environment-and-unrelated-process"
    }

    $caseName = 'repeat-cycles'
    $arguments = New-Case $caseName; $env:XLM_LIFECYCLE_CASE = 'success'
    foreach ($cycle in 1..3) {
        if ($cycle -gt 1) { Copy-Item -LiteralPath $builtJar -Destination $arguments.JarPath }
        Remove-Item -LiteralPath (Join-Path $arguments.ConfigDir 'fixture-ready.txt') -ErrorAction SilentlyContinue
        & $launcher -Action Verify -VerificationScript $callback @arguments | Out-Null
        Assert-Released $arguments
    }
    Passed 'three-repeated-cycles-no-stale-state-and-deletable-jar'

    $caseName = 'persistent-run'
    $arguments = New-Case $caseName
    $persistentOwner = [Xlm.Launcher.OwnedServerProcess]::Start($shell, @('-NoProfile','-EncodedCommand',(Shell-Command $arguments 'Run')), $fixture)
    Wait-File (Join-Path $arguments.ConfigDir 'fixture-ready.txt')
    Start-Sleep -Milliseconds 200
    Assert-True (-not $persistentOwner.View.HasExited) 'Developer Run returned before its server stopped.'
    $locked = $false
    try { $check=[IO.File]::Open($arguments.JarPath,'Open','ReadWrite','None');$check.Dispose() } catch { $locked=$true }
    Assert-True $locked 'Live fixture did not retain its JAR.'
    $persistentOwner.Dispose(); $persistentOwner=$null
    Assert-Released $arguments
    Passed 'persistent-run-remains-until-owning-test-stops-it'

    $caseName = 'abrupt-owner'
    $arguments = New-Case $caseName; $env:XLM_LIFECYCLE_CASE = $caseName
    # No outer job for this PowerShell process: the inner verifier must own Java.
    $abruptOwner = Start-Process -FilePath $shell -ArgumentList @('-NoProfile','-EncodedCommand',(Shell-Command $arguments 'Verify')) -WindowStyle Hidden -PassThru
    $null = $abruptOwner.Handle
    $evidence = Join-Path $arguments.ConfigDir 'callback-evidence.json'; Wait-File $evidence
    $identity = Get-Content -Raw -LiteralPath $evidence | ConvertFrom-Json
    $abruptJava = Get-Process -Id $identity.Id
    $null = $abruptJava.Handle
    Assert-True ($abruptJava.StartTime.ToUniversalTime() -eq ([DateTime]$identity.StartTimeUtc).ToUniversalTime()) ('Fixture Java identity mismatch: actual=' + $abruptJava.StartTime.ToUniversalTime().ToString('o') + ' expected=' + ([DateTime]$identity.StartTimeUtc).ToUniversalTime().ToString('o'))
    $abruptOwner.Kill(); Assert-True ($abruptOwner.WaitForExit(10000)) 'Owned verifier did not exit.'
    Assert-True ($abruptJava.WaitForExit(10000)) 'Java survived abrupt verifier termination.'
    Assert-True (-not $unrelated.View.HasExited) 'Abrupt cleanup affected unrelated JVM.'
    Assert-Released $arguments
    Passed 'abrupt-verifier-termination-kills-only-its-job'

    $caseName = 'missing-callback'
    $arguments = New-Case $caseName; $failed=$false
    try { & $launcher -Action Verify @arguments | Out-Null } catch { $failed=$true }
    Assert-True $failed 'Verify accepted a missing callback.'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $arguments.ConfigDir 'fixture-ready.txt'))) 'Java started without a callback.'
    Passed 'missing-callback-fails-before-java-start'
    Write-Output "Lifecycle tests passed: $passed named scenarios (including three repeat cycles)."
} finally {
    if ($null -ne $persistentOwner) { $persistentOwner.Dispose() }
    if ($null -ne $abruptOwner) { if (-not $abruptOwner.HasExited) { $abruptOwner.Kill(); $abruptOwner.WaitForExit(10000) | Out-Null }; $abruptOwner.Dispose() }
    if ($null -ne $abruptJava) { $abruptJava.WaitForExit(10000) | Out-Null; $abruptJava.Dispose() }
    if ($null -ne $unrelated) { $unrelated.Dispose() }
    if ($hadCase) { [Environment]::SetEnvironmentVariable('XLM_LIFECYCLE_CASE',$oldCase,'Process') } else { Remove-Item Env:XLM_LIFECYCLE_CASE -ErrorAction SilentlyContinue }
    if ($hadConfig) { [Environment]::SetEnvironmentVariable('XLM_CONFIG_DIR',$oldConfig,'Process') } else { Remove-Item Env:XLM_CONFIG_DIR -ErrorAction SilentlyContinue }
    if ($hadSecrets) { [Environment]::SetEnvironmentVariable('XLM_SECRETS_DIR',$oldSecrets,'Process') } else { Remove-Item Env:XLM_SECRETS_DIR -ErrorAction SilentlyContinue }
    $base=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')+'\'
    $target=[IO.Path]::GetFullPath($fixture)
    if (-not $target.StartsWith($base,[StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe lifecycle test cleanup path.' }
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
}
