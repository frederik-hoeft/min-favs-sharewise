# Local Docker deployment

This directory contains the single-host production-style ShareWise stack for local acceptance: static Next.js via HTTPS Nginx, Spring Boot, and PostgreSQL 18. Only Nginx publishes host ports. This is **not** public-host provisioning, production certificate management, backups, or automated rollout (P2.6c/d).

## First-time setup

You need a trusted Docker Engine with Compose, plus Bash, OpenSSL and `tar` in the shell running the scripts. Build tools run inside the image build stages, not on your host. Commands work from the VS Code devcontainer through Docker-outside-of-Docker or directly on the Docker host.

From the repository root:

```sh
./deploy/scripts/bootstrap.sh
```

This **idempotent** one-time bootstrap creates `deploy/.env` and protected, ignored `deploy/.local/` credentials/certificates if absent, then imports them into service-specific Docker-managed volumes and builds the three images. It does **not** start the application. It preserves existing passwords, configuration, certificates, and database volumes. It rejects incomplete fixtures, certificate-hostname mismatches, or attempts to silently change stored volume credentials. New test certificates are self-signed, valid for **365 days**, and cover `localhost` and `127.0.0.1` (DNS and IP subject alternative names respectively).

Then operate directly from `deploy/`, with no extra Compose file, environment file, or project arguments:

```sh
cd deploy
docker compose up -d       # Images have been built by bootstrap.sh
docker compose ps
docker compose logs -f frontend backend postgres
docker compose down        # Stops containers, preserves the database and volumes
```

Use `docker compose up -d --build` after changing application source or image build definitions. The optional `--build` forces a rebuild; it is not required for ordinary stop/start. Do not run `docker compose down -v` unless you intend to delete the database and every local secret/certificate volume.

## Production image base updates

The current Dockerfiles use release-tagged (not digest-pinned) upstream images for Maven/Temurin, Debian, Node, Nginx and PostgreSQL. These tags are **mutable**: rebuilding the same commit later can resolve different base layers. Debian `apt-get` and npm dependency download behavior can also change over time. Kaniko's `--reproducible` flag removes build timestamps but does not freeze all build inputs. The validated output digests in the CI release descriptor are the references for deployment and rollback, not proof of bit-for-bit rebuild reproducibility.

For stronger reproducibility, a reviewed follow-up should resolve **current, verified multi-platform index digests** for each base (for example, using `docker buildx imagetools inspect <image:tag>` on a Docker-capable machine), replace the relevant `FROM` references with `image:tag@sha256:<verified-index-digest>`, and pin the deployment's PostgreSQL base consistently. Rebuild both application images and run the local image/Compose tests and a protected preview pipeline. Keep a regular security-update workflow: refreshing a pin is an explicit, tested change, not something an immutable digest receives automatically. The external Debian package repositories still need a separately reviewed snapshot/pinning policy if exact rebuild equivalence is required.

## Local browser URLs and TLS

By default, open **https://localhost:8443/** in a browser on the **Docker daemon host**. The HTTP URL, http://localhost:18080/, redirects to HTTPS. Both host ports bind to `127.0.0.1`; neither Spring nor PostgreSQL is published. These distinct ports avoid conflict with the devcontainer's `3000`/`8080` development forwards.

The TLS certificate is self-signed and **not automatically trusted** by Chrome or your operating system. Trust it explicitly for browser acceptance, or use a certificate already trusted by your machine. Chrome's `ERR_SSL_UNRECOGNIZED_NAME_ALERT` means Nginx rejected the TLS SNI hostname, which can happen when `SHAREWISE_PUBLIC_HOST` does not match the URL you opened. It is different from a certificate-authority warning. Use the hostname configured in `deploy/.env`; for the default setup that is `localhost`.

For CLI smoke checks on the Docker host (skipping certificate trust only for these checks):

```sh
curl -kfsS https://localhost:8443/
curl -kfsS https://localhost:8443/api/system/v1/health
```

If an existing fixture was originally generated for `sharewise.test`, migrate its TLS material **without deleting database data or passwords**:

```sh
cd deploy
./scripts/bootstrap.sh                  # Also migrates legacy .local/compose.env to .env
./scripts/cleanup.sh tls                # Stops the stack and removes only TLS material
# Set SHAREWISE_PUBLIC_HOST=localhost in .env (leave other values unchanged).
./scripts/bootstrap.sh                  # Generates/imports a new localhost certificate
docker compose up -d
```

`cleanup.sh tls` requires confirmation (or explicit `--yes`). Certificates are scoped to the public hostname; merely changing `.env` cannot update an existing certificate or the TLS Docker volume. When using a custom hostname, set `SHAREWISE_PUBLIC_HOST` in `.env` and supply a matching certificate. Nginx rejects unknown TLS hostnames, including mismatched SNI, rather than falling back to an unrelated certificate.

## Configuration, secrets and Docker-outside-of-Docker

The local `deploy/.env` is based on [`.env.example`](.env.example) and is loaded automatically by Docker Compose when invoked in `deploy/`. It contains public hostname, loopback bind addresses, host ports, project name and optional image overrides; **no passwords**. Exported shell variables take precedence over `.env`, so avoid conflicting `SHAREWISE_PUBLIC_HOST`, `SHAREWISE_HTTPS_PORT`, or `COMPOSE_PROJECT_NAME` overrides. The bootstrap script preserves any existing `.env` rather than changing user-selected ports or hostnames.

Private input files live under ignored `deploy/.local/secrets/` and `deploy/.local/tls/`, with restrictive file modes. The import helper streams them over the Docker API into named volumes, rather than using bind mounts that require the same source paths on the Docker daemon host. The PostgreSQL initialization script is copied into the database image, not mounted from the developer's workspace.

| Volume | Purpose |
| --- | --- |
| `postgres_data` | PostgreSQL data, preserved by normal `down` |
| `postgres_secrets` | PostgreSQL admin, migrator and application passwords |
| `backend_secrets` | Application/migrator passwords, but **not** administrator password |
| `tls_certs` | Nginx certificate and private key |

The importer is idempotent when files are unchanged; it rejects changed passwords in existing volumes. Password rotation on persistent PostgreSQL data requires separate, coordinated database and application changes. Local generated passwords and private keys must never be committed. The Docker socket confers extensive control of the host and is appropriate only for trusted local development and acceptance.

A devcontainer and the Docker host share the daemon, **not** a network namespace. Open the published HTTPS URL from the daemon host browser/terminal. The acceptance script communicates through Compose service DNS inside the deployment networks, so it does not depend on `localhost` from the devcontainer.

## Disposable end-to-end acceptance

From `deploy/`, run:

```sh
./scripts/test-compose.sh
```

The script uses a separate `sharewise-p26ab-smoke` Compose project, with distinct published host ports (`28080`/`18443`), and refuses to reuse an existing test database volume. It builds and starts a fresh stack, verifies PostgreSQL initialization and Flyway ownership, checks runtime-role permissions, exercises HTTPS API and proxy security, and verifies data persistence after a database restart. On success it **removes the disposable stack and its volumes**; on failure it preserves them for troubleshooting.

For inspection after a successful run, opt out of automatic cleanup:

```sh
./scripts/test-compose.sh --keep
```

A retained test stack must be removed before rerunning with the same project name because the acceptance test requires an empty database. Remove it with `./scripts/cleanup.sh test` or run an independent project:

```sh
SHAREWISE_COMPOSE_TEST_PROJECT=sharewise-p26ab-smoke2 ./scripts/test-compose.sh --keep
```

If a test fails, inspect `docker compose -p sharewise-p26ab-smoke ps -a` and `docker compose -p sharewise-p26ab-smoke logs` before explicitly cleaning it up. The ordinary local deployment and its volumes are unaffected.

For independent production-image smoke checks, use `./scripts/test-images.sh`. Focused static/integration tests run with `python3 -m unittest discover -s tests -p 'test_*.py' -v` (Nginx, OpenSSL and `envsubst` are needed for the Nginx tests).

## Cleanup and resetting local state

**Ordinary shutdown is just `docker compose down`.** Cleanup is deliberately separate and always requires interactive confirmation or an explicit `--yes`. From `deploy/`:

```sh
./scripts/cleanup.sh test                # Destroy default disposable test project + volumes
./scripts/cleanup.sh test sharewise-p26ab-smoke2
./scripts/cleanup.sh tests               # Discover and destroy ALL disposable test projects
./scripts/cleanup.sh tls                 # Replace TLS only; preserve database/passwords
./scripts/cleanup.sh local               # DANGER: delete local database + ALL local configuration
```

`local` removes the normal Compose project's data/credential/certificate volumes and ignored `deploy/.env`/`.local` state, so the next bootstrap starts completely fresh. `tests` discovers projects by Docker's Compose project labels and the reserved test prefix, including orphaned test volumes. It does not target the normal local project. The `tls` mode stops the normal stack and removes just its TLS volume and local certificate files, preserving PostgreSQL data, database passwords and `.env`. Bootstrap and start again afterward.

If `docker compose up` fails due to a host-port conflict, choose free loopback ports in `.env`; this does not require regenerating secrets or rebuilding images. Inspect `docker compose ps` and `docker compose logs` for startup failures. Database role initialization happens **only on an empty database volume**; do not delete that volume to retry a persistent installation. A backend readiness failure may indicate unavailable PostgreSQL, migration failure or a missing required secret.
