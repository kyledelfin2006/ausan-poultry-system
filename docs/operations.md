# Operations guide

Production runs as two containers (app and PostgreSQL 17) from `compose.prod.yaml`. Commands are PowerShell; bash equivalents differ only in the variable syntax.

> **Financial records are append-only.** Stock movements and ledger entries are never edited or deleted, not in the API and not by hand in the database. A mistake is corrected by a reversal or an opposite entry (cancel a delivery, record an adjustment), never by deleting. Treat backups as the only way to go back in time.

## 1. First deployment

Prerequisites: Docker with Compose v2 on the host (Docker Desktop on Windows/macOS, Docker Engine on Linux), Git, ports 8080 (or `APP_PORT`) open to the people or proxy that need the API.

```powershell
git clone https://github.com/kyledelfin2006/ausan-poultry-system.git; cd ausan-poultry-system
Copy-Item .env.example .env
```

Edit `.env` and replace every placeholder:

- `POSTGRES_PASSWORD` and `SPRING_DATASOURCE_PASSWORD`: the same long random value.
- `APP_SECURITY_JWT_SECRET`: at least 32 bytes with 16 or more distinct byte values. Generate: `openssl rand -base64 48`, or in PowerShell `[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))`.
- `APP_BOOTSTRAP_OWNER_USERNAME` / `APP_BOOTSTRAP_OWNER_PASSWORD`: the first owner account. The owner must replace this password before using other API routes.

Start:

```powershell
docker compose -f compose.prod.yaml --env-file .env up -d --build
docker compose -f compose.prod.yaml --env-file .env ps       # wait for app to be "healthy"
curl.exe http://localhost:8080/actuator/health
```

Flyway creates the schema and seeds the products, two placeholder branches and the three packages on first start.

### Remove the bootstrap variables

The owner is created only when the user table is empty, but do not leave the password lying around. After logging in and changing the bootstrap password:

1. Delete `APP_BOOTSTRAP_OWNER_USERNAME` and `APP_BOOTSTRAP_OWNER_PASSWORD` from `.env`.
2. Recreate the app: `docker compose -f compose.prod.yaml --env-file .env up -d app`.
3. Log in as the owner and create the assistant's account (`POST /api/users`), then rename the placeholder branches (`PATCH /api/branches/{id}`).

### Exposing it

The API speaks plain HTTP. Put a reverse proxy with TLS (Caddy, nginx, Cloudflare Tunnel, a cloud load balancer) in front before it is reachable from the internet; tokens and passwords must not travel in clear text. The database port is intentionally not published.

## 2. Upgrade

```powershell
./scripts/backup.ps1                       # always back up first
git pull
docker compose -f compose.prod.yaml --env-file .env up -d --build
docker compose -f compose.prod.yaml --env-file .env logs -f app    # watch Flyway apply migrations
```

Flyway runs pending migrations when the new app starts. Postgres data lives in the `ausan_pgdata` volume and survives rebuilds. The app stops gracefully (up to 30 s) so in-flight requests finish.

## 3. Rollback

Migrations only move forward, so a rollback depends on whether the new version applied a migration:

- **No new migration in the release:** `git checkout <previous-tag-or-commit>` and run the same `up -d --build`.
- **The release added a migration:** the old code may not run against the new schema. Restore the pre-upgrade backup into an empty database (section 5), then deploy the old version. Anything entered after the backup is lost, so decide quickly and tell the staff.

## 4. Backups

Dumps are `pg_dump` custom format (`.dump`), named `ausan-YYYYMMDD-HHMMSS.dump`.

```powershell
./scripts/backup.ps1                                     # to .\backups, keep 14
./scripts/backup.ps1 -BackupDir D:\ausan-backups -Keep 30
```
```bash
BACKUP_DIR=/srv/ausan-backups BACKUP_KEEP=30 ./scripts/backup.sh
```

They read `POSTGRES_USER` and `POSTGRES_DB` from `.env` (or the environment) and need the postgres container running. Retention deletes the oldest dumps beyond the limit. A failed dump never replaces a good one.

### Schedule

Daily is the minimum for a business that records money; copy the dump **off the machine** too (another disk, cloud storage), otherwise a dead disk takes the backups with it.

Linux cron (`crontab -e`):
```
0 2 * * * cd /srv/ausan-poultry-system && BACKUP_DIR=/srv/ausan-backups ./scripts/backup.sh >> /var/log/ausan-backup.log 2>&1
```

Windows Task Scheduler (run as the user that runs Docker Desktop):
```powershell
$action  = New-ScheduledTaskAction -Execute "pwsh.exe" -Argument "-NoProfile -File C:\ausan-poultry-system\scripts\backup.ps1 -BackupDir D:\ausan-backups"
$trigger = New-ScheduledTaskTrigger -Daily -At 2am
Register-ScheduledTask -TaskName "Ausan backup" -Action $action -Trigger $trigger
```

## 5. Restore (and the restore drill)

`restore.ps1` / `restore.sh` load a dump into an **empty** database after you type `RESTORE` to confirm. Use `-Yes` / `--yes` to skip the prompt in automation. They stop at the first error.

**Drill (do it once now, then every few months):** restore into a scratch database and look at it, without touching production.

```powershell
docker compose -f compose.prod.yaml --env-file .env exec -T postgres psql -U poultry -d postgres -c "create database drill"
# make a copy of .env with POSTGRES_DB=drill, then:
./scripts/restore.ps1 .\backups\ausan-20261120-020000.dump -EnvFile .env.drill
docker compose -f compose.prod.yaml --env-file .env exec -T postgres psql -U poultry -d drill -c "select count(*) from financial_entry"
docker compose -f compose.prod.yaml --env-file .env exec -T postgres psql -U poultry -d postgres -c "drop database drill"
```

**Real disaster recovery** (the live database is damaged or the release must be undone):

```powershell
docker compose -f compose.prod.yaml --env-file .env stop app
# replace the database with an empty one:
docker compose -f compose.prod.yaml --env-file .env exec -T postgres psql -U poultry -d postgres -c "drop database poultry" -c "create database poultry"
./scripts/restore.ps1 .\backups\<chosen>.dump
docker compose -f compose.prod.yaml --env-file .env start app
```

On a brand-new host: configure `.env`, run `docker compose ... up -d postgres`, then restore, then `up -d --build`.

## 6. Rotating `APP_SECURITY_JWT_SECRET`

Generate a new value, update `.env`, and recreate the app (`docker compose -f compose.prod.yaml --env-file .env up -d app`). **Every issued token becomes invalid, so everyone is logged out** and must sign in again (tokens last 8 hours anyway). Do it after a suspected leak, or when someone with access leaves. Passwords are unaffected. Rotate `POSTGRES_PASSWORD` separately: change it inside Postgres (`ALTER USER`) and in both `.env` variables, then recreate both containers.

## 7. Docker Desktop as the host

Workable for a small shop, with caveats:

- Docker Desktop must be running and the user logged in after every reboot. Enable **Start Docker Desktop when you sign in** and let the containers restart (`restart: unless-stopped` is already set). Disable sleep on the machine.
- Windows updates and reboots stop the stack; check `docker compose ps` afterwards.
- The data volume lives inside Docker Desktop's VM disk. Do not rely on it alone: schedule the backup task and copy dumps to another drive or cloud storage.
- Keep the host patched and do not forward port 8080 on the router; use a TLS tunnel or reverse proxy instead.

## 8. Troubleshooting

| Symptom | Check |
|---|---|
| `set APP_SECURITY_JWT_SECRET in .env` on `up` | A required variable is missing; compose refuses to start without it. |
| App restarts repeatedly | `docker compose ... logs app`. Common: secret shorter than 32 bytes, wrong DB password, failed migration. |
| `unhealthy` | The healthcheck calls `/actuator/health` inside the container; look for startup errors in the logs (start period is 60 s). |
| 401 for everyone after a restart | The JWT secret changed. Log in again. |
| 401 right after a user changed their password, or after logout | Expected: both end that user's sessions on every device. Log in again. |
| Browser console says "blocked by CORS policy" | The frontend's exact origin is not in `APP_CORS_ALLOWED_ORIGINS` (scheme, host and port must match; no trailing slash). Fix `.env`, recreate the app container. See [security.md](security.md#4-controls). |
| Need to trace one failed action | Take the `X-Request-Id` from the response (or the frontend) and search the logs for it; logs are JSON in production. See [security.md](security.md#5-observability). |
