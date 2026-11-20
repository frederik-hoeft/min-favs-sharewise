# Local production-style deployment

This Compose stack builds and runs the production backend and static frontend images with PostgreSQL 18 for **single-host acceptance testing**. It is separate from the development database and does not provision a public server, arrange certificate issuance, perform database backups, or implement CI deployment/rollbacks (P2.6c/d).

Nginx is the only publicly bound service. It serves the static Next.js export over HTTPS and forwards `/api/*` to Spring Boot, which connects to PostgreSQL through the isolated `data` network. Flyway runs as `sharewise_migrator`; the runtime application connects as `sharewise_app`. See [persistence ownership](../docs/architecture/persistence.md) and [production packaging](../docs/internal/tasks/p2.6-production-packaging/specification.md).

## Prerequisites

Use a trusted local Docker Engine with the Docker Compose plugin. The invoking shell needs Bash, OpenSSL, and `tar`; image builds run in containers and need no host Java, Maven, Node.js or npm installation. Docker access, including through a devcontainer-mounted socket, is equivalent to powerful access to the Docker host.

The recommended workflow uses the Docker CLI inside the VS Code devcontainer (**Docker-outside-of-Docker**, or DooD). Running the same commands on the Docker host is supported.

```sh
docker version
docker compose version
```

## Prepare and start

Run commands from the repository root:

```sh
./deploy/scripts/prepare-local.sh
./deploy/scripts/start.sh
```

The first command creates ignored `deploy/.local/compose.env`, `secrets/`, and `tls/` directories containing three distinct random 64-hex-character passwords and a **self-signed, two-day** certificate for `sharewise.test`. It refuses to overwrite existing files. The host-side passwords and private key are mode `0600`, inside private `0700` directories; the public certificate is mode `0644`. You can instead provide your own files matching this structure; use `deploy/production.env.example` as the environment template and keep credentials out of it.

The start script checks the Compose configuration, imports local secrets and certificates through the Docker API, then builds and starts PostgreSQL, Spring Boot and Nginx with health-based startup ordering. Subsequent `start.sh` invocations work with unchanged assets and existing data. See the [scripts](scripts/) for details.

Development forwards ports `3000` (Next.js) and `8080` (Spring Boot) through VS Code. The local deployment uses different host ports so it can coexist with those forwarded ports; VS Code automatic port discovery is disabled, while the explicit development forwards remain available.

By default the browser endpoints are **http://sharewise.test:18080/** (redirects to HTTPS) and **https://sharewise.test:8443/**, published only on **127.0.0.1 of the Docker daemon host**. Map `sharewise.test` to that loopback address through a host file or local DNS when testing in a browser, and explicitly trust the local certificate if necessary. For quick host-side checks without DNS setup:

```sh
curl -I --resolve sharewise.test:18080:127.0.0.1 http://sharewise.test:18080/
curl -kfsS --resolve sharewise.test:8443:127.0.0.1 https://sharewise.test:8443/
curl -kfsS --resolve sharewise.test:8443:127.0.0.1 \
  https://sharewise.test:8443/api/system/v1/health
```

`-k` skips verification **only for these disposable command-line checks**. A passing health response should include `"status":"UP"`. The browser, curl, and Docker CLI must not be assumed to share the same network namespace.

## Why assets are imported instead of bind-mounted

With DooD, the Docker CLI transfers image build contexts to the daemon, but Docker **bind mounts are interpreted by the daemon host**. A file under `/workspace` in the devcontainer therefore is not necessarily visible on the host at the same path. Named Docker volumes and stdin transfer avoid that problem:

- `postgres_data`: persistent PostgreSQL 18 data (never removed by ordinary `down`).
- `postgres_secrets`: administrator, migration, and application passwords, readable only in PostgreSQL's volume mount.
- `backend_secrets`: migration and application passwords, mounted to Spring's required config-tree `/run/secrets/` path; **not** the administrator password.
- `tls_certs`: certificate and key mounted read-only into Nginx.

`deploy/scripts/import-assets.sh` streams the local assets into a one-off, network-isolated `asset-loader` service through Docker stdin. Only that loader has write access to the three credential/certificate volumes. Files inside the separate Docker-private volumes must be readable by the respective unprivileged application processes, so password file mode is `0444` there; the original host-side files remain `0600`. Access to Docker-managed volumes must be restricted to trusted operators, and no secret is baked into an image or passed as a Compose environment variable. The PostgreSQL initialization script is public, credential-free code copied into a derived `postgres:18-trixie` image, not a daemon-side bind mount. Its Dockerfile-specific ignore file excludes the local secret/certificate directory from the build context entirely.

The importer is idempotent for identical files, but rejects a mismatch **before replacing anything**. It deliberately cannot rotate live database credentials or certificates. Database roles are created only on the **first start with an empty PostgreSQL volume**. For a persistent stack, password rotation requires coordinated PostgreSQL `ALTER ROLE`, updated application credentials, and a separately reviewed rotation procedure; changing local files is insufficient. This local acceptance harness does not implement that operation.

## Acceptance test

The deployment test uses a *different*, disposable Compose project and a fresh PostgreSQL volume. It streams the same local fixture into that project's independent volumes, builds the application images, then checks PostgreSQL initialization, Flyway ownership, role permissions, HTTPS forwarding, exposure, database persistence across restart, and application readiness recovery:

```sh
./deploy/scripts/test-compose.sh
# For another fresh test: SHAREWISE_COMPOSE_TEST_PROJECT=sharewise-p26ab-smoke2 ./deploy/scripts/test-compose.sh
```

It uses distinct host ports by default (`28080`/`18443`, overridable through `SHAREWISE_SMOKE_HTTP_PORT` and `SHAREWISE_SMOKE_HTTPS_PORT`), so the normal local stack can remain running. It **refuses to reuse a pre-existing test database volume** and leaves test containers/volumes available for inspection. To remove only an intentionally disposable test stack:

```sh
docker compose --env-file deploy/.local/compose.env -f deploy/compose.yaml \
  -p sharewise-p26ab-smoke down --volumes
```

`down --volumes` **deletes the selected stack's database and imported secrets**. Never invoke it for a persistent project without explicitly deciding to discard the data. Ordinary `down` retains all volumes.

For independent production-image build and HTTPS static-route checks, run `./deploy/scripts/test-images.sh`. The local Nginx proxy and startup integration tests can be run with `python3 -m unittest discover -s deploy/tests -p 'test_*.py' -v` (some tests require Nginx, OpenSSL and `envsubst`). Neither test suite replaces the full Compose/PostgreSQL acceptance workflow.

## Operate and troubleshoot

From the repository root:

```sh
docker compose --env-file deploy/.local/compose.env -f deploy/compose.yaml ps
docker compose --env-file deploy/.local/compose.env -f deploy/compose.yaml logs -f frontend backend postgres
docker compose --env-file deploy/.local/compose.env -f deploy/compose.yaml down
```

Edit `deploy/.local/compose.env` to adjust the hostname, localhost port bindings or image tags. The fixture generator never overwrites this file. If it was created with `SHAREWISE_HTTP_PORT=8080`, change that line to `SHAREWISE_HTTP_PORT=18080` before restarting. There is no need to regenerate credentials, certificates or the PostgreSQL volume. If a host port is already occupied, choose another free loopback port in this file; Compose cannot take an occupied port from another process. `SHAREWISE_BACKEND_IMAGE` and `SHAREWISE_FRONTEND_IMAGE` also accept registry image references for later stages. Use `SHAREWISE_COMPOSE_ENV_FILE` to select a different local config and `SHAREWISE_LOCAL_INPUT_DIR` to select an alternative source directory for import; the input paths are client-side only. `SHAREWISE_COMPOSE_PROJECT` overrides the project name for the import/start scripts. Prefer a separate project name and separate volumes for every destructive acceptance test.

When using DooD, run browser or published-port smoke requests **on the Docker daemon host** rather than relying on `localhost` in the devcontainer. The automated acceptance test instead resolves `frontend` through Compose networking and preserves HTTPS SNI/Host semantics, avoiding that namespace mismatch.

A missing secret or invalid certificate prevents normal service startup. Examine `docker compose logs` and `ps`; importing or starting with missing inputs should fail rather than fall back to development passwords. If first-time PostgreSQL initialization fails, use a **new disposable project with an empty volume** to retest: initialization scripts are not replayed on an already-populated volume. A database outage should make the Spring readiness endpoint unhealthy; `depends_on` does not guarantee automatic restart of running dependents after every outage.

The importer deliberately rejects replacing an existing volume credential or certificate without coordinated operational action. For certificate renewal in this disposable setup, recreate the separate TLS volume under a verified Compose project and import the new certificate while frontend is stopped; do not remove the database volume as part of certificate maintenance. There is no automated certificate renewal, off-host backup, or public-deployment hardening in this phase.
