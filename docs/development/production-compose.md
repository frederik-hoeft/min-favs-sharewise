# Local production-style Compose acceptance

The [production Compose file](../../compose.production.yaml) runs the same backend and static/Nginx frontend images used for packaging, together with PostgreSQL 18. This is a disposable acceptance environment for **P2.6a**, not host provisioning, certificate issuance, registry deployment, backup/restore or automated rollout (P2.6c/d). See the [persistence architecture](../architecture/persistence.md) for schema authority and runtime permissions.

## Configuration and startup

The frontend is the only service with published ports (HTTP and HTTPS, bound to host loopback by default). It joins the `edge` network with the backend; only the backend and PostgreSQL join the isolated `data` network. Nginx proxies `/api/*` by the internal service name `backend` and terminates TLS. PostgreSQL stores data in the named `postgres_data` volume under `/var/lib/postgresql` (PostgreSQL 18 layout).

On an **empty** PostgreSQL volume, the official entrypoint initializes the database and runs `deploy/postgres/10-create-roles.sh`. It creates `sharewise_migrator` with database `CONNECT, CREATE` and `sharewise_app` with `CONNECT`. Only the migrator performs Flyway DDL; the baseline migration grants application DML privileges for future context objects. The init script runs **once**, not at every restart, and refuses empty/invalid role secrets.

Spring's `production` profile imports the two application role passwords as required config-tree files from `/run/secrets/` and explicitly replaces the development password defaults. The backend image enables that profile by default; missing secrets must prevent it from starting. PostgreSQL reads its administrator password through `POSTGRES_PASSWORD_FILE`; the administrator secret is never mounted in the backend. Production passwords are not Docker build arguments, image layers or Compose environment values.

Compose waits for PostgreSQL's `pg_isready` before starting Spring and waits for the database-backed Spring readiness endpoint before starting Nginx. Health checks remain active after startup. `depends_on` orders container startup and some explicit Compose restarts; it **does not automatically restart dependents for every runtime database outage**. A running frontend can continue serving static content while its API returns `502/503` when the backend or database is unavailable.

## Disposable local fixture

Run these commands from the repository root on a Docker/Compose-capable host, with Docker, OpenSSL, Git and the image-build dependencies available:

```bash
bash scripts/prepare-production-local.sh
# Creates .local/production/compose.env, secrets/*, tls/*.pem
# Generated secret files contain independent 32-byte random values encoded as hex.
docker compose --env-file .local/production/compose.env -f compose.production.yaml config -q
docker compose --env-file .local/production/compose.env -f compose.production.yaml up --build --wait -d
docker compose --env-file .local/production/compose.env -f compose.production.yaml ps
docker compose --env-file .local/production/compose.env -f compose.production.yaml logs --tail=80 backend postgres frontend
```

`prepare-production-local.sh` refuses to overwrite an existing fixture and generates a self-signed two-day certificate for `sharewise.test`. Keep `.local/production/` outside version control. The parent directories have mode `0700`; secrets are `0444` **inside those private directories** so file-backed Compose secrets remain readable to the non-root PostgreSQL and backend processes on Docker implementations that do not honor the secret `uid/gid/mode` options. Do not copy these files to an unprotected directory. The TLS private key is `0600`. For real deployments, use externally provisioned secrets, certificates and access control instead of this local generator.

The default published ports are `127.0.0.1:8080` (HTTP) and `127.0.0.1:8443` (HTTPS). The public hostname and host port are independent of Nginx's internal listeners (`80` and `443`). For an HTTP/HTTPS test **on the Docker host**:

```bash
curl -I --resolve sharewise.test:8080:127.0.0.1 http://sharewise.test:8080/
curl -k --resolve sharewise.test:8443:127.0.0.1 https://sharewise.test:8443/
curl -k --resolve sharewise.test:8443:127.0.0.1 https://sharewise.test:8443/api/system/v1/health
# Expect HTTP 308 redirect, static page, and {"status":"UP"} respectively.
```

In Docker-outside-of-Docker, local `127.0.0.1` may refer to the development container rather than the daemon host. More importantly, **file-backed Compose secrets, TLS mounts and the PostgreSQL initialization script are daemon-side bind mounts**: the Docker host must see the referenced file paths. Prefer running this Compose stack on the Docker host or from a client using paths shared with the daemon; Docker-outside-of-Docker does not automatically translate `/workspace` bind paths. Once the mounts are valid, the acceptance script below probes Nginx through internal Compose DNS with the correct Host/SNI and does not rely on access to host-published ports.

For different ports, paths, image tags or a real DNS hostname, copy and edit [the safe environment template](../../deploy/production.env.example). `SHAREWISE_BACKEND_IMAGE` and `SHAREWISE_FRONTEND_IMAGE` allow later deployment to use digest-qualified registry images while retaining the same Compose service contract. `SHAREWISE_SECRETS_DIR` and `SHAREWISE_TLS_DIR` may point to externally managed paths. No credentials belong in the environment file.

## Automated PostgreSQL and HTTPS acceptance

The following script uses its **own** Compose project, `sharewise-p26ab-smoke`, and refuses to reuse an existing database volume. It builds both images, checks readiness and HTTPS same-origin API behavior, inspects port bindings, verifies the Flyway migration role and application DML/DDL boundaries against PostgreSQL, writes a disposable marker, restarts PostgreSQL without deleting its volume, verifies the marker survived, and drops it. It does not delete the stack or its volume automatically.

```bash
bash scripts/test-production-compose.sh
# Optional: SHAREWISE_COMPOSE_TEST_PROJECT=sharewise-p26ab-smoke2 bash scripts/test-production-compose.sh
# Inspect the disposable result, then remove ONLY that test project's containers/volume:
docker compose --env-file .local/production/compose.env -f compose.production.yaml \
    -p sharewise-p26ab-smoke down --volumes
```

**`down --volumes` permanently deletes the specified project's PostgreSQL data.** Never run it against a persistent deployment or a project name you have not verified. A stopped/restarted stack without `--volumes` reuses the same database and does **not** rerun the initialization script. Database role password rotation on an existing volume requires explicit PostgreSQL `ALTER ROLE` and coordinated secret replacement. Changing secret files alone does not rotate stored role passwords.

## Negative and recovery checks

Perform these checks only with a separate disposable project and a separate copy of the generated secrets, never on a live service:

1. **Missing secret:** Temporarily omit `sharewise.database.app-password` from the copied secret directory and start the disposable stack using `SHAREWISE_SECRETS_DIR` set to that path. The file mount or Spring config-tree placeholder must fail, and the backend must not report ready. For a missing migrator password, expect the same fail-closed outcome.
2. **Invalid migration credential on a populated volume:** Start a fresh disposable stack successfully, retain its volume, stop the backend, then replace the copied migrator secret with a different 64-character hexadecimal secret. Recreate the backend with the new secret mount; Flyway must fail authentication and Spring must not become healthy. Restore the original secret and recreate the backend to recover. Changing the secret before the *first* database initialization is **not** a valid negative test, since PostgreSQL would create the role with the new value.
3. **Database outage:** Stop PostgreSQL while the backend and frontend are running. Within the backend's configured health-check window, `/api/system/v1/health` must cease returning `200` and backend health must become `unhealthy`. Start PostgreSQL, inspect backend logs, and confirm readiness recovers. If necessary, explicitly restart the backend through Compose; do not assume automatic recovery from dependency ordering.
4. **Exposure and trust:** Inspect `docker compose ps` and `docker inspect` to confirm only frontend ports are published. Check the HTTP redirect, unknown-host rejection and spoofed forwarded headers with the existing Nginx integration tests. `docker compose config` exposes **secret file paths**, not password contents. Neither Compose output nor logs should contain passwords or private keys.

Use `docker compose --env-file .local/production/compose.env -f compose.production.yaml logs --tail=100 <service>` and `ps` for diagnostics. For a failed first initialization, correct the secret/configuration and start with a **new disposable project/empty volume**; PostgreSQL does not replay init scripts on a partially populated volume. Inspect and remove failed disposable volumes explicitly, rather than automatically deleting data after a startup failure.
