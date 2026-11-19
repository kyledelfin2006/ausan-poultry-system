<#
.SYNOPSIS
  Restore a dump made by backup.ps1/backup.sh into the compose Postgres.
  The target database must be EMPTY. Stop the app first:
    docker compose -f compose.prod.yaml stop app
.EXAMPLE
  .\scripts\restore.ps1 .\backups\ausan-20261120-020000.dump
#>
param(
  [Parameter(Mandatory = $true)][string]$Dump,
  [string]$ComposeFile = "compose.prod.yaml",
  [string]$EnvFile = ".env",
  [switch]$Yes
)
$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)
if (-not (Test-Path $Dump)) { throw "Dump file not found: $Dump" }
$dumpPath = (Resolve-Path $Dump).Path

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

if (-not $Yes) {
  Write-Host "About to restore '$dumpPath' into database '$pgDb'. It must be empty; existing objects will cause errors."
  if ((Read-Host "Type RESTORE to continue") -ne "RESTORE") { Write-Host "Aborted."; exit 1 }
}

$cid = docker compose -f $ComposeFile --env-file $EnvFile ps -q postgres
docker cp $dumpPath "${cid}:/tmp/restore.dump"
if ($LASTEXITCODE -ne 0) { throw "docker cp failed" }
try {
  # --exit-on-error: stop at the first problem instead of leaving a half-restored database.
  docker compose -f $ComposeFile --env-file $EnvFile exec -T postgres pg_restore -U $pgUser -d $pgDb --no-owner --exit-on-error /tmp/restore.dump
  if ($LASTEXITCODE -ne 0) { throw "pg_restore failed" }
} finally {
  docker compose -f $ComposeFile --env-file $EnvFile exec -T postgres rm -f /tmp/restore.dump | Out-Null
}
Write-Host "Restore complete. Start the app again: docker compose -f $ComposeFile --env-file $EnvFile start app"
