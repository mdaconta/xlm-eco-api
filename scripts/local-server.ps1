[CmdletBinding()]
param(
    [ValidateSet('Check', 'Run')][string]$Action = 'Check',
    [string]$ConfigDir,
    [string]$SecretsDir,
    [string]$JarPath
)

$ErrorActionPreference = 'Stop'
# Nonzero Java exits are returned verbatim even when the caller promotes native exits to errors.
$PSNativeCommandUseErrorActionPreference = $false
$projectDir = Split-Path -Parent $PSScriptRoot

function Resolve-PhysicalPath([string]$Path, [int]$Depth = 0) {
    if ($Depth -gt 40) { throw 'Too many filesystem links in startup path.' }
    $absolute = [IO.Path]::GetFullPath($Path)
    $root = [IO.Path]::GetPathRoot($absolute)
    $current = $root
    $parts = $absolute.Substring($root.Length).Split([char[]]'\/', [StringSplitOptions]::RemoveEmptyEntries)
    foreach ($part in $parts) {
        $item = Get-Item -LiteralPath (Join-Path $current $part) -Force
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            $target = @($item.Target)[0]
            if ([string]::IsNullOrWhiteSpace($target)) { throw 'Cannot resolve a linked startup path safely.' }
            if (-not [IO.Path]::IsPathRooted($target)) { $target = Join-Path $current $target }
            $current = Resolve-PhysicalPath $target ($Depth + 1)
        } else { $current = $item.FullName }
    }
    return $current
}

function Require-Path([string]$Value, [string]$Label, [string]$Type) {
    # IsPathRooted alone accepts drive-relative C:folder and root-relative \folder.
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch '^(?:[A-Za-z]:[\\/]|[\\/]{2}[^\\/]+[\\/][^\\/]+)') {
        throw "$Label must be an absolute Windows path."
    }
    if (-not (Test-Path -LiteralPath $Value -PathType $Type)) { throw "$Label is missing or has the wrong type." }
    return Resolve-PhysicalPath $Value
}

function Require-ExternalDirectory([string]$Value, [string]$Label) {
    $resolved = Require-Path $Value $Label 'Container'
    $ancestor = $resolved
    while ($ancestor) {
        if (Test-Path -LiteralPath (Join-Path $ancestor '.git')) {
            throw "$Label must be outside every Git checkout (including linked paths)."
        }
        $ancestor = Split-Path -Parent $ancestor
    }
    return $resolved
}

if (-not $PSBoundParameters.ContainsKey('ConfigDir')) {
    $ConfigDir = if (Test-Path Env:XLM_CONFIG_DIR) { $env:XLM_CONFIG_DIR } else { Join-Path $env:USERPROFILE '.xlm\config' }
}
if (-not $PSBoundParameters.ContainsKey('SecretsDir')) {
    $SecretsDir = if (Test-Path Env:XLM_SECRETS_DIR) { $env:XLM_SECRETS_DIR } else { Join-Path $env:USERPROFILE '.xlm\secrets' }
}
if (-not $PSBoundParameters.ContainsKey('JarPath')) { $JarPath = Join-Path $projectDir 'target\xlm-eco-api-1.0-SNAPSHOT.jar' }

$config = Require-ExternalDirectory $ConfigDir 'XLM config directory'
$secrets = Require-ExternalDirectory $SecretsDir 'XLM secrets directory'
$jar = Require-Path $JarPath 'XLM JAR' 'Leaf'
if (-not (Test-Path -LiteralPath (Join-Path $config 'server.properties') -PathType Leaf)) { throw 'XLM server.properties is missing.' }
if (-not (Test-Path -LiteralPath (Join-Path $secrets 'admin.token') -PathType Leaf)) { throw 'XLM admin.token is missing; no secret content was read.' }
$java = (Get-Command java -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
Write-Output 'XLM startup prerequisites found. Server readiness and provider credentials have not been checked.'
if ($Action -eq 'Check') { return }

$hadConfig = Test-Path Env:XLM_CONFIG_DIR
$hadSecrets = Test-Path Env:XLM_SECRETS_DIR
$oldConfig = [Environment]::GetEnvironmentVariable('XLM_CONFIG_DIR', 'Process')
$oldSecrets = [Environment]::GetEnvironmentVariable('XLM_SECRETS_DIR', 'Process')
try {
    [Environment]::SetEnvironmentVariable('XLM_CONFIG_DIR', $config, 'Process')
    [Environment]::SetEnvironmentVariable('XLM_SECRETS_DIR', $secrets, 'Process')
    # Each array argument remains separate, including a JAR path containing spaces.
    # Foreground Java retains normal certificate and hostname validation.
    & $java '-Djavax.net.ssl.trustStoreType=Windows-ROOT' '-Djavax.net.ssl.trustStore=NONE' '-jar' $jar
    $serverExitCode = $LASTEXITCODE
} finally {
    if ($hadConfig) { [Environment]::SetEnvironmentVariable('XLM_CONFIG_DIR', $oldConfig, 'Process') } else { Remove-Item Env:XLM_CONFIG_DIR -ErrorAction SilentlyContinue }
    if ($hadSecrets) { [Environment]::SetEnvironmentVariable('XLM_SECRETS_DIR', $oldSecrets, 'Process') } else { Remove-Item Env:XLM_SECRETS_DIR -ErrorAction SilentlyContinue }
}
exit $serverExitCode
