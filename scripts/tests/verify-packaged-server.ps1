# Read-only startup check for the packaged server's default loopback listeners.
# Invoke through local-server.ps1 -Action Verify; never starts a server itself.
param([Parameter(Mandatory=$true)]$ServerProcess)
$ErrorActionPreference = 'Stop'
$deadline = [DateTime]::UtcNow.AddSeconds(30)
do {
    if ($ServerProcess.HasExited) { throw 'Owned packaged server exited before readiness.' }
    $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop |
        Where-Object { $_.OwningProcess -eq $ServerProcess.Id -and $_.LocalAddress -eq '127.0.0.1' })
    if (50052 -in $listeners.LocalPort -and 50053 -in $listeners.LocalPort) {
        if ($ServerProcess.HasExited) { throw 'Owned packaged server exited during readiness check.' }
        Write-Output "Packaged API ready: owned PID=$($ServerProcess.Id), inference=127.0.0.1:50052, admin=127.0.0.1:50053. No provider calls."
        return
    }
    Start-Sleep -Milliseconds 200
} while ([DateTime]::UtcNow -lt $deadline)
throw 'Owned packaged server did not open both default loopback listeners within 30 seconds.'
