# Local Docker deployment

This directory contains the single-host production-style ShareWise stack for local acceptance: static Next.js via HTTPS Nginx, Spring Boot, and PostgreSQL 18. Only Nginx publishes host ports by default; PostgreSQL can be explicitly published for local devcontainer inspection. This is **not** public-host provisioning, production certificate management, backups, or automated rollout (P2.6c/d).

## First-time setup

You need a trusted Docker Engine with Compose, plus Bash, OpenSSL and `tar` in the shell running the scripts. Build tools run inside the image build stages, not on your host. Commands work from the VS Code devcontainer through Docker-outside-of-Docker or directly on the Docker host.

From the repository root:

```sh
./deploy/scripts/bootstrap.sh
```

This **idempotent** bootstrap creates `deploy/.env` and protected, ignored `deploy/.local/` database credentials/TLS certificates if absent, imports them into service-specific Docker-managed volumes, builds the images, and initializes the IAM ES256 signing key directly in its Docker volume. It does **not** start the application. It preserves existing passwords, configuration, certificates, IAM signing keys, and database volumes. It rejects incomplete fixtures, certificate-hostname mismatches, or attempts to silently change stored volume credentials. New test certificates are self-signed, valid for **365 days**, and cover `localhost` and `127.0.0.1` (DNS and IP subject alternative names respectively).

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

By default, open **https://localhost:8443/** in a browser on the **Docker daemon host**. The HTTP URL, http://localhost:18080/, redirects to HTTPS. Both host ports bind to `127.0.0.1`; neither Spring nor PostgreSQL is published by default. These distinct ports avoid conflict with the devcontainer's `3000`/`8080` development forwards.

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

## Ingress request-body limits

The HTTPS Nginx edge is the **only** request-body size enforcement point in the shipped deployment. Its global default is **1 MiB** (`client_max_body_size 1m` in `src/frontend/nginx/production.conf.template`), returning HTTP `413` before proxying oversized requests, including chunked bodies without `Content-Length`. Larger or streaming endpoints require a deliberately reviewed per-location Nginx override; the backend has no independently configured request-body limit. Keep the Spring Boot service private behind this ingress. Any alternative/public backend ingress must enforce an equivalent limit in its own proxy or gateway before exposure.

## IAM JWT signing-key provisioning and distribution

The local stack enables P2.1a production IAM by default. Its reusable **`iam-key-init` one-shot container** provisions an ES256/NIST P-256 pair using OpenSSL. The backend starts only after the provisioning job exits successfully (`service_completed_successfully`). Keys are **not** in the application image, repository, `.env`, or `deploy/.local` host fixtures.

The signing identity has a stable `kid` (default `iam-local-1`, set by `SHAREWISE_IAM_ACTIVE_KEY_ID`). Two independent Docker volumes isolate private material from public verification material:

| Volume | Contents | Access |
| --- | --- | --- |
| `iam_signing_private` | `active.pem` (unencrypted PKCS#8 private key), `key-id` | Key-init writes; backend UID/GID `10001` reads through a read-only mount; no other service mounts it |
| `iam_verification_public` | `active.pem` (SPKI PEM public key), `key-id` | Key-init writes; backend and public-key exporter read; future verifiers on the same Docker daemon may mount read-only |

The job runs with no network access, a read-only container filesystem, and temporary work within the private-key volume. Its private key never passes through Docker image layers, Compose environment variables, logs, or host paths. **Docker daemon administrators and anyone with access to the private volume can still extract the key**; a volume is not an HSM or an OS-level boundary against the Docker administrator. Back up and protect the private volume accordingly.

The standalone key initializer and the Compose fallback default to `require-existing`.
The **local-only** `deploy/.env.example` deliberately opts into `generate-if-absent`:
copying this local template into a persistent or production installation also opts into
implicit regeneration if both volumes are lost. For durable installations, set
`SHAREWISE_IAM_KEY_MODE=require-existing` after the single authorized provisioning run.
The key-init container drops all Linux capabilities except `CHOWN` (needed to grant
backend GID `10001` read access), and runs without networking or a writable root filesystem.

The default `deploy/.env.example` specifies:

```dotenv
SHAREWISE_IAM_KEY_MODE=generate-if-absent
SHAREWISE_IAM_ACTIVE_KEY_ID=iam-local-1
SHAREWISE_IAM_ISSUER=https://localhost:8443
SHAREWISE_IAM_AUDIENCE=sw-api-v1
```

`bootstrap.sh` runs `scripts/iam-keys.sh init` after building the images. `docker compose up` also runs the job as a startup prerequisite. On subsequent runs, it **verifies** the stored key ID, P-256 algorithm and public/private match without replacing the pair. A partial fixture, changed `kid`, invalid key, symlink, or concurrent/interrupted initializer causes failure instead of an implicit rotation. The volume-local `.iam-lock` prevents two provisioning jobs from both creating a new identity; an orphaned lock or staging directory requires operator investigation before removal. The backend uses the corresponding Spring configuration via Compose environment variables, including `sharewise.iam.auth.enabled=true` and key paths `/run/iam/{private,public}/active.pem`. Externally provisioned private keys must contain exactly one unencrypted PKCS#8 PEM block with **LF line endings**; this initializer rejects CRLF-formatted PEMs even though the Java key loader accepts them. Convert line endings before placing such keys in the volume.

To export a **public key only** to another host or service, from `deploy/`:

```sh
./scripts/iam-keys.sh export > ../iam-local-1-public.pem
openssl pkey -pubin -in ../iam-local-1-public.pem -noout
```

The exporter mounts **only `iam_verification_public`**. The PEM may be distributed through configuration management to future extracted microservices; each verifier must explicitly bind the trusted public key to the approved `kid`, issuer, audience, algorithm and token profile. There is no public JWKS endpoint or automated cross-host trust distribution yet. A future local container can instead mount `iam_verification_public` read-only, but must never mount `iam_signing_private`.

**Production reuse.** The local Compose stack is not a hardened public-host deployment. Production orchestrators can build/publish `deploy/iam-key-init/Dockerfile` and run its entrypoint as a one-shot job using persistent, independent private/public volumes, supplying `IAM_KEY_ID`, `IAM_KEY_MODE`, and the two mount paths (`/keys/private` and `/keys/public`). Gate signer startup on successful job completion, mount both keys read-only into the IAM signer and **only the public key** into other services. The provisioning interface is the same as the local container, so no key-generation shell script needs to be copied into the Spring image.

For a production Compose deployment, the essential service wiring is:

```yaml
services:
  iam-key-init:
    image: registry.example.invalid/sharewise/iam-key-init:<approved-version>
    restart: "no"
    network_mode: none
    read_only: true
    environment:
      IAM_KEY_ID: ${SHAREWISE_IAM_ACTIVE_KEY_ID:?}
      IAM_KEY_MODE: ${SHAREWISE_IAM_KEY_MODE:?}
    volumes:
      - iam_signing_private:/keys/private
      - iam_verification_public:/keys/public
    tmpfs: [/tmp]
  backend:
    depends_on:
      iam-key-init:
        condition: service_completed_successfully
    volumes:
      - iam_signing_private:/run/iam/private:ro
      - iam_verification_public:/run/iam/public:ro
    # Configure IAM issuer, audience, active kid and trusted key paths explicitly.
  # A separate public-only export job or read-only verifier mount provides distribution.
volumes:
  iam_signing_private: {}
  iam_verification_public: {}
```

This is the **key-lifecycle portion**, not a standalone production stack: connect it to the existing backend, network, secrets and ingress definitions, pin a reviewed image digest, and supply real issuer/trust settings. The source image and entrypoint are reusable; the project does not currently ship a production-wide orchestration or automated signing-key rotation system.

For a production installation, keep **`SHAREWISE_IAM_KEY_MODE=require-existing`** after provisioning. This refuses startup if the signing volumes disappear, rather than silently creating a different identity. On the *first* approved provisioning only, set `generate-if-absent` and run the key job. Then switch to `require-existing` and rerun the idempotent check. Alternatively pre-provision the exact files and matching `key-id` metadata in the designated volumes under an independently controlled secret-management procedure. Do not use the local sample issuer or `kid` for public deployments; configure a stable, explicit issuer and audience, and independently manage TLS, backups, access controls and service images.

Changing `SHAREWISE_IAM_ACTIVE_KEY_ID` is **not rotation**: the job rejects it against a populated volume. Rotation requires provisioning a new identity separately, distributing its public key to all verifiers **before cutover**, retaining the old public key during token-lifetime overlap, and updating the active signer plus `sharewise.iam.auth.keys[]` trust entries together. Back up the original private volume and its `kid` alongside recoverable authentication state. **Do not destroy the private-key volume while PostgreSQL/session data survives**; losing the signer can invalidate outstanding access tokens. Emergency revocation and staged rotation are separate operational procedures, not implemented by this bootstrapper.

When changing `SHAREWISE_PUBLIC_HOST` or the exposed HTTPS port, also set `SHAREWISE_IAM_ISSUER` to the exact intended stable origin. The disposable Compose acceptance script substitutes its separate HTTPS port for the IAM issuer so tests do not accidentally inherit the normal local origin.

## Configuration, secrets and Docker-outside-of-Docker

The local `deploy/.env` is based on [`.env.example`](.env.example) and is loaded automatically by Docker Compose when invoked in `deploy/`. It contains public hostname, loopback bind addresses, host ports, project name, IAM issuer/audience/key-ID/mode and optional image overrides; **no passwords or signing-key bytes**. Exported shell variables take precedence over `.env`, so avoid conflicting `SHAREWISE_PUBLIC_HOST`, `SHAREWISE_HTTPS_PORT`, or `COMPOSE_PROJECT_NAME` overrides. The bootstrap script preserves any existing `.env` rather than changing user-selected ports or hostnames.

Private input files live under ignored `deploy/.local/secrets/` and `deploy/.local/tls/`, with restrictive file modes. The import helper streams them over the Docker API into named volumes, rather than using bind mounts that require the same source paths on the Docker daemon host. The PostgreSQL initialization script is copied into the database image, not mounted from the developer's workspace.

| Volume | Purpose |
| --- | --- |
| `postgres_data` | PostgreSQL data, preserved by normal `down` |
| `postgres_secrets` | PostgreSQL admin, migrator and application passwords |
| `backend_secrets` | Application/migrator passwords, but **not** administrator password |
| `tls_certs` | Nginx certificate and private key |
| `iam_signing_private` | IAM ES256 private key and key ID; backend read-only |
| `iam_verification_public` | IAM ES256 public key and key ID; exportable independently |

The database/TLS importer is idempotent when files are unchanged; it rejects changed passwords in existing volumes. IAM keys are provisioned by the separate one-shot container, not imported from host files. Password rotation on persistent PostgreSQL data requires separate, coordinated database and application changes. Local generated passwords and private keys must never be committed. The Docker socket confers extensive control of the host and is appropriate only for trusted local development and acceptance.

A devcontainer and the Docker host share the daemon, **not** a network namespace. Open the published HTTPS URL from the daemon host browser/terminal. The acceptance script communicates through Compose service DNS inside the deployment networks, so it does not depend on `localhost` from the devcontainer.

## Optional PostgreSQL access from the devcontainer

The default deployment keeps PostgreSQL private on the `data` Compose network. The VS Code devcontainer uses Docker-outside-of-Docker: it shares the Docker **daemon** with the deployed stack but does not share its network namespace, so a PostgreSQL connection to `localhost:5432` inside the devcontainer does not reach the deployed database. The devcontainer supplies `host.docker.internal` through Docker's `host-gateway` mapping for reaching ports published on the daemon host.

On Docker Desktop, publish PostgreSQL on host loopback by uncommenting only the following setting in `deploy/.env`, which is also shown in [`.env.example`](.env.example):

```dotenv
COMPOSE_FILE=compose.yaml:compose.postgres-host.yaml
```

The [host-access Compose override](compose.postgres-host.yaml) defaults to `127.0.0.1:15432` on the Docker host; the devcontainer reaches that published port at `host.docker.internal:15432`. This is confirmed for the local Docker Desktop setup, but Docker Desktop configurations may differ. The override stays disabled unless explicitly selected, and bootstrap preserves an existing `.env` without trying to discover network addresses.

On native Linux Docker Engine, loopback publishing generally cannot be reached from an ordinary bridge-networked devcontainer. Set `SHAREWISE_POSTGRES_BIND` explicitly to a suitable daemon-host bridge address (inspect it with `docker network inspect bridge`) instead. `SHAREWISE_POSTGRES_PORT` optionally changes the host port. Do not use the IP returned by resolving `host.docker.internal` as a publishing address: container-to-host destinations and host bind addresses are different concepts. Avoid `0.0.0.0` unless exposing the database on all host interfaces is intentional.

From `deploy/`, validate the merged Compose configuration and recreate the affected service without deleting the database volume:

```bash
docker compose config
docker compose up -d --force-recreate postgres
```

In the devcontainer's PostgreSQL extension, connect to host `host.docker.internal`, port `15432` (or `SHAREWISE_POSTGRES_PORT`), database `sharewise`, user `sharewise_app`, and the application-role password held in ignored `deploy/.local/secrets/sharewise.database.app-password`. Prefer this least-privileged role for inspection; use `sharewise_migrator` only when migration privileges are genuinely necessary. The deployed PostgreSQL instance is separate from the devcontainer's `postgres` service and its development database.

The override adds a second, non-internal Docker bridge to PostgreSQL so Docker can publish its host port while retaining its existing internal `data` network for the backend. **Enabling it reduces network isolation:** other containers that can reach the bound host interface may connect to PostgreSQL, subject to database authentication. Use it only for a trusted local acceptance environment; never enable this override on a public or production host. The disposable `test-compose.sh` stack always uses the base private topology regardless of this local setting.

To disable host access, remove or comment out `COMPOSE_FILE` and the PostgreSQL bind/port variables in `deploy/.env`, then run `docker compose up -d --force-recreate postgres` from `deploy/`. This recreates the container without the published port and preserves the existing database volume. Verify with `docker compose ps` and `docker compose port postgres 5432` (which should report no published binding).

## Disposable end-to-end acceptance

From `deploy/`, run:

```sh
./scripts/test-compose.sh
```

The script uses a separate `sharewise-p26ab-smoke` Compose project, with distinct loopback host ports (`28080`/`18443`) for the production-style smoke, and refuses to reuse an existing test database volume. It builds and starts a fresh stack, verifies PostgreSQL initialization and Flyway ownership, checks runtime-role permissions, exercises HTTPS API and proxy security (including IAM and Group Management workflows), and verifies data persistence after a database restart. After the production-style checks, it replaces the backend with the disposable authorization fixture and removes proxy host bindings while the actual frontend Node E2E runner tests the distributed authorization protocol over HTTPS. On success it **removes the disposable stack and its volumes**; on failure it preserves them for troubleshooting.

For inspection after a successful run, opt out of automatic cleanup. The retained authorization fixture is unpublished:

```sh
./scripts/test-compose.sh --keep
```

A retained test stack must be removed before rerunning with the same project name because the acceptance test requires an empty database. Remove it with `./scripts/cleanup.sh test` or run an independent project:

```sh
SHAREWISE_COMPOSE_TEST_PROJECT=sharewise-p26ab-smoke2 ./scripts/test-compose.sh --keep
```

For manual distributed authorization acceptance in a real browser, use the explicit browser mode instead:

```sh
./scripts/test-compose.sh --browser
```

This runs the same unpublished automated tests first. On success, it retains the disposable backend and database and replaces only the proxy/frontend with a test-only image containing `/app/authorization-smoke`. The proxy publishes the smoke HTTP/HTTPS ports on `127.0.0.1`; the backend and database remain unpublished. The normal deployment image has no fixture page or fixture endpoints. Open `https://localhost:18443/` (or the configured smoke origin) on the Docker daemon host, trust the local certificate, register or sign in with a disposable account, then open `/app/authorization-smoke`. **Use a separate browser profile or a separate TLS-covered hostname from the ordinary local deployment:** cookies are scoped by hostname, not port, so the two localhost stacks can otherwise interfere with each other's authentication cookies.

The fixture page seeds only the authenticated caller's Group projection and creates two real groups through the production API. Use the buttons to call the protected fixture endpoints through the normal session-scoped frontend transport. With DevTools Network open, verify cold (`401`, issuance `200`, replay `200`), warm (`200`) and cross-endpoint (`401`, replay `200`, no issuance) flows. Change the target or timestamp to exercise affinity, compare the effect counter before and after mutations, and wait for expiration to test lazy renewal. Do not export bearer/attestation headers, cookie values or unredacted HAR files. Browser mode retains the stack for manual testing; clean up with `./scripts/cleanup.sh test sharewise-p26ab-smoke` when finished.

The browser fixture also displays live diagnostic snapshots of the tab-local credential and requirement caches. In this build and in `npm run dev`, the root identity provider exposes `window.sharewiseDebug.authorization.snapshot()`, `credentials()` and `requirements()`; use `console.table(window.sharewiseDebug.authorization.credentials())` or `console.table(window.sharewiseDebug.authorization.requirements())` from any page. These are detached metadata snapshots, not cache objects; expired entries remain visible until normal lazy eviction, and an empty requirement marker has no credential reference. Snapshots omit credential bytes but may contain resource identifiers and projected query values, so treat them as development data. The ordinary production build does not register this global.

If a test fails, inspect `docker compose -p sharewise-p26ab-smoke ps -a` and `docker compose -p sharewise-p26ab-smoke logs` before explicitly cleaning it up. The ordinary local deployment and its volumes are unaffected.

The disposable Compose smoke performs CSRF bootstrap, registration and login through the real HTTPS proxy, rotates the refresh cookie while retaining the session ID, validates the replacement JWT against the exported public-only IAM key, and confirms that logout clears the cookie and prevents further refresh. It also checks the static Groups list, workspace and invitation routes and exercises a two-account Group lifecycle through the HTTPS API: creation and listing, outsider denial, invitation creation, preview and single-use redemption, administrator-only access, promotion, revocation and departure. It verifies that the existing bearer observes the promoted role without renewal. Until IAM-to-Group provisioning is integrated, the probe inserts only the two verified IAM user IDs into `groups.users` using the application database role; all business state changes use the public API.

The smoke generates disposable credentials and does not print passwords, cookies, signing keys or bearer tokens. Group API bearer headers and invitation secrets are passed via stdin, not process arguments. Run it on a Docker host with Python 3 and OpenSSL. The probe complements rather than replaces PostgreSQL-backed E2E tests or interactive browser acceptance. Major implementation phases must satisfy the [local smoke gate](../docs/development/ai-development-workflow.md#6-perform-local-deployment-smoke-and-manual-acceptance) before merge.

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

`local` removes the normal Compose project's data/credential/certificate/**IAM signing-key** volumes and ignored `deploy/.env`/`.local` state, so the next bootstrap starts completely fresh. `tests` discovers projects by Docker's Compose project labels and the reserved test prefix, including orphaned test volumes. It does not target the normal local project. The `tls` mode stops the normal stack and removes just its TLS volume and local certificate files, preserving PostgreSQL data, database passwords and `.env`. Bootstrap and start again afterward.

If `docker compose up` fails due to a host-port conflict, choose free loopback ports in `.env`; this does not require regenerating secrets or rebuilding images. Inspect `docker compose ps` and `docker compose logs` for startup failures. Database role initialization happens **only on an empty database volume**; do not delete that volume to retry a persistent installation. A backend readiness failure may indicate unavailable PostgreSQL, migration failure or a missing required secret.
