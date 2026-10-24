$ErrorActionPreference = 'Stop'
$hosts = "$env:SystemRoot\System32\drivers\etc\hosts"
$backupDir = 'E:\DeepSeek Harness\IPV6\mobile-relay\v6assist'
$backup = Join-Path $backupDir '_hosts.backup.txt'
$log = Join-Path $env:TEMP 'v6assist-hosts.log'
$mode = $args[0]
$out = @()

try {
    if ($mode -eq 'add') {
        if (-not (Test-Path $backup)) { Copy-Item $hosts $backup -Force }
        $lines = Get-Content $hosts
        if ($lines -notmatch 'v6assist temporary') {
            Add-Content -Path $hosts -Value "`r`n# v6assist temporary: github push edge (remove after use)`r`n20.27.177.113 github.com" -Encoding ASCII
            $out += 'ADDED'
        } else { $out += 'ALREADY' }
    } elseif ($mode -eq 'restore') {
        if (Test-Path $backup) {
            Copy-Item $backup $hosts -Force
            $out += 'RESTORED'
        } else { $out += 'NO_BACKUP' }
    }
    ipconfig /flushdns | Out-Null
    $out += 'FLUSHED'
} catch {
    $out += "ERR: $($_.Exception.Message)"
}
$out -join "`n" | Set-Content -Path $log -Encoding UTF8
