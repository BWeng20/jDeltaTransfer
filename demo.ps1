<#
.SYNOPSIS
  Walks the whole version chain against a running server: full download of the first version,
  then a delta upgrade to every following one, and prints what each step cost.

.EXAMPLE
  .\demo.ps1 -Server http://localhost:8080 -Work D:\Projekte\jDeltaTransfer\data\client
#>
param(
    [string]$Server = 'http://localhost:8080',
    [string]$Work = (Join-Path $PSScriptRoot 'data\client'),
    [string]$Jar = (Join-Path $PSScriptRoot 'build\libs\jDeltaTransfer-1.0.0-all.jar'),
    [string]$JavaOpts = '-Xmx4g'
)

$ErrorActionPreference = 'Stop'
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
if (-not (Test-Path $Jar)) { throw "Fat jar not found at $Jar - run: gradlew fatJar" }
New-Item -ItemType Directory -Force -Path $Work | Out-Null
$cache = Join-Path $Work 'cache'

function Invoke-Jdt {
    param([string[]]$JdtArgs)
    $out = & $java $JavaOpts -jar $Jar client @JdtArgs 2>&1
    if ($LASTEXITCODE -ne 0) { $out | Write-Host; throw "client failed: $($JdtArgs -join ' ')" }
    return $out
}

$versions = (Invoke-RestMethod "$Server/api/versions").versions | Sort-Object id
if (-not $versions) { throw "server reports no versions" }
Write-Host "server has $($versions.Count) versions" -ForegroundColor Cyan

$results = @()
$previous = $null
foreach ($v in $versions) {
    $target = Join-Path $Work "$($v.id).zip"
    $sw = [Diagnostics.Stopwatch]::StartNew()
    if ($null -eq $previous) {
        $log = Invoke-Jdt @('fetch', '--server', $Server, '--cache', $cache,
                            '--version', $v.id, '--out', $target)
        $mode = 'full'
    } else {
        $log = Invoke-Jdt @('fetch', '--server', $Server, '--cache', $cache,
                            '--version', $v.id, '--base', $previous, '--out', $target)
        $mode = 'delta'
    }
    $sw.Stop()

    $transferred = ($log | Select-String -Pattern '^\s*transferred\s+(.+?)\s\s' ).Matches.Groups[1].Value
    $saved = ($log | Select-String -Pattern '\(([\d.]+%) less than the full archive\)').Matches.Groups[1].Value

    # Independent check: compare against the server's published hash.
    $localHash = (Invoke-Jdt @('hash', '--file', $target)) -split '\s+' | Select-Object -First 1
    $ok = if ($localHash -eq $v.sha256) { 'OK' } else { 'HASH MISMATCH' }

    $results += [pscustomobject]@{
        Version     = $v.id
        Mode        = $mode
        ArchiveSize = $v.sizeHuman
        Transferred = $transferred
        Saved       = $saved
        Verify      = $ok
        Seconds     = [math]::Round($sw.Elapsed.TotalSeconds, 1)
    }
    Write-Host ("  {0}  {1,-5}  archive {2,10}  transferred {3,10}  saved {4,7}  {5}" -f `
        $v.id, $mode, $v.sizeHuman, $transferred, $saved, $ok)
    if ($ok -ne 'OK') { throw "verification failed for $($v.id)" }
    $previous = $target
}

Write-Host ''
$results | Format-Table -AutoSize
