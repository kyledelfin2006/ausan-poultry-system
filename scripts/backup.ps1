<#
.SYNOPSIS
  Dump the compose Postgres (custom format) to a dated file and prune old dumps.
.PARAMETER BackupDir   Where dumps go (default .\backups)
.PARAMETER Keep        How many dumps to keep (default 14)
.PARAMETER ComposeFile Compose file (default compose.prod.yaml)
.PARAMETER EnvFile     Env file passed to compose (default .env)
#>
param(
  [string]$BackupDir = ".\backups",
  [int]$Keep = 14,
  [string]$ComposeFile = "compose.prod.yaml",
  [string]$EnvFile = ".env"
)
$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)

function Get-EnvValue($name) {
  $v = [Environment]::GetEnvironmentVariable($name)
  if ($v) { return $v }
  if (Test-Path $EnvFile) {
    $line = Get-Content $EnvFile | Where-Object { $_ -match "^$name=" } | Select-Object -Last 1
    if ($line) { return $line.Substring($name.Length + 1).Trim() }
  }
  return $null
}
$pgUser = Get-EnvValue "POSTGRES_USER"; $pgDb = Get-EnvValue "POSTGRES_DB"
if (-not $pgUser -or -not $pgDb) { throw "POSTGRES_USER / POSTGRES_DB not set (environment or $EnvFile)" }

New-Item -ItemType Directory -Force $BackupDir | Out-Null
$file = Join-Path (Resolve-Path $BackupDir) ("ausan-{0}.dump" -f (Get-Date -Format "yyyyMMdd-HHmmss"))
$tmp = "$file.partial"

# PowerShell pipes mangle binary data, so the dump is written inside the container and copied out.
$container = "ausan-backup-tmp.dump"
docker compose -f $ComposeFile --env-file $EnvFile exec -T postgres pg_dump -U $pgUser -d $pgDb -Fc -f "/tmp/$container"
if ($LASTEXITCODE -ne 0) { throw "pg_dump failed" }
$cid = docker compose -f $ComposeFile --env-file $EnvFile ps -q postgres
docker cp "${cid}:/tmp/$container" $tmp
if ($LASTEXITCODE -ne 0) { throw "docker cp failed" }
docker compose -f $ComposeFile --env-file $EnvFile exec -T postgres rm -f "/tmp/$container" | Out-Null
if ((Get-Item $tmp).Length -eq 0) { Remove-Item $tmp; throw "dump is empty" }
Move-Item $tmp $file
Write-Host ("Backup written: {0} ({1} bytes)" -f $file, (Get-Item $file).Length)

# Retention: keep the newest $Keep dumps.
Get-ChildItem $BackupDir -Filter "ausan-*.dump" | Sort-Object LastWriteTime -Descending |
  Select-Object -Skip $Keep | ForEach-Object { Write-Host "Pruning $($_.FullName)"; Remove-Item $_.FullName }
