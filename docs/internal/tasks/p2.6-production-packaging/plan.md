# P2.6a/b: Production packaging and registry publishing implementation plan

**Status:** Human-approved implementation sequence (November 20, 2026); Phase A runner probes completed, builder-selection review pending. **Contract:** [Approved P2.6a/b specification](specification.md) and [P2.6 backlog](../backlog.md#p26---production-packaging-deployment-and-cicd). **Workflow:** [AI development and quality assurance](../../../development/ai-development-workflow.md).

## Baseline, scope and sequencing

- [ ] Confirm the approved specification and plan are committed to the authoritative task branch before dependent implementation. For local review, reconstruct the approved source snapshot plus both approved task documents; commit/push those documents to the authoritative GitLab task branch before dependent implementation is accepted.
- [ ] At the start of each implementation increment, verify the exact source revision, inspect affected repository guidelines, establish a clean branch from the **last human-approved baseline**, and run relevant unchanged checks. Do not combine unreviewed phases in one patch.
- [ ] Limit this work to **P2.6a** (backend/frontend images, Nginx and production-style Compose) and **P2.6b** (CI builder, registry publication and versioning). P2.6c host provisioning, secrets/cert issuance and P2.6d deployment orchestration, rollout and rollback are not implementation tasks here.
- [ ] Keep application code, backend module boundaries, existing Maven/npm dependency descriptors, database migrations, and GitLab test/security coverage stable unless a reviewed requirement forces a narrowly scoped change. P2.6 does not implement pending business features.

The early **runner feasibility spike** is independent of the production-stack implementation: it can be reviewed first or undertaken in parallel, but it must pass before P2.6b's publishing implementation commits to a builder. The P2.6a image and stack increments are sequential. P2.6b publishing follows the image contract and versioning increment. Each gate below requires human review of a focused Git patch; passing a tool check alone does not authorize the next increment.

### Validation baseline and environment boundaries

The supplied November 20 source snapshot has already passed a clean, strict-offline Maven `clean verify` using the supplied Eclipse Temurin 25.0.5.1, Maven 3.10.0 and verified **Maven-only** dependency handoff (122 Unit tests; no failures). The backend is a Spring Boot 4.1 modular monolith with PostgreSQL 18 and separate application/migrator roles. The frontend is Next.js 16 and has a static-export acceptance probe, but the normal Dockerfile still packages a Node.js standalone server. The existing GitLab CI has distinct frontend, backend Unit/Integration/E2E, security and offline-handoff jobs. These are baseline observations, **not** evidence of P2.6 acceptance.

- This offline workspace has **no supplied Node/npm toolchain/cache**, container runtime, or PostgreSQL service. Do not claim frontend, Nginx, Compose, image or PostgreSQL acceptance from Maven success. Reuse the verified Maven handoff without changing dependency versions; request a separately prepared Node/npm handoff if offline frontend verification becomes necessary.
- Perform Docker/Compose and database integration checks on a machine with a container runtime, required base images/build dependencies and TLS fixtures. Run GitLab compatibility, registry and pipeline-permission checks on the **actual university runner**; a local emulator is insufficient.
- Repeat the existing Maven and frontend regression gates after relevant source changes. For offline backend checks, use the supplied Maven executable with `-o` and the isolated `-Dmaven.repo.local=...` repository. For frontend checks, use `npm ci` from the lockfile and the existing npm scripts; use `npm ci --offline` only with a verified Node handoff. Record the source revision, commands, tool versions, results and unavailable infrastructure at each review.

## Phase A - Prove a daemonless runner build (P2.6b feasibility)

**Dependency:** Access to a disposable GitLab pipeline on the university runner. No production image publication or registry secrets are needed for the initial probe.

- [ ] Inspect runner executor/architecture, image execution restrictions, user namespaces, mount behavior and allowed CI variables. Use a pinned `moby/buildkit:rootless` candidate with `buildctl-daemonless.sh` and `BUILDKITD_FLAGS=--oci-worker-no-process-sandbox`; do not require Docker socket, DinD, elevated privileges or a privileged runner.
- [ ] Add a **separately reviewable, non-publishing** BuildKit probe job or temporary pipeline on an isolated ref. Build a trivial Dockerfile to an OCI/tar output or equivalent, not to the release registry. Capture the image digest/version, runner job URL, command, image pin and unprivileged execution evidence (B-02 feasibility).
- [ ] If rootless BuildKit is blocked, retain the error evidence (including seccomp/user-namespace failures) and test a maintained, pinned daemonless alternative, beginning with rootless Buildah or the maintained Kaniko fork identified in the specification. Do **not** fix compatibility by relaxing runner isolation to `seccomp:unconfined` or introducing a privileged service. Escalate if no compliant builder works.
- [ ] Remove or explicitly isolate temporary probe configuration after the experiment; record the chosen executor, image pin, required flags, supported platform(s) and any reproducibility limits for Phase F.

### Running the Phase A probe

1. Commit/push the reviewed patch on an **unprotected, isolated GitLab ref**. In **Build > Pipelines > New pipeline**, select that ref and set `P26AB_BUILDKIT_PROBE=1`. The `probe-rootless-buildkit` job runs only in a manually started web pipeline with that variable and an unprotected ref; ordinary CI is unchanged.
2. The job uses the version-pinned `moby/buildkit:v0.35.1-rootless` image, a non-root user and `BUILDKITD_FLAGS=--oci-worker-no-process-sandbox`. There is no Docker service/socket, registry authentication or image push. A small Dockerfile with **both `RUN` and `COPY`** produces a local OCI archive. Pulling its Alpine base requires **read** access to an image registry.
3. Review the job trace and seven-day artifacts (`runner.txt`, `build.log`, `index.json`, `probe.oci.tar`). Record the job URL, runner executor/security policy, resolved builder image digest from the runner trace, UID/privileges, OCI image manifest digest and any error. Distinguish namespace or mount failures from registry-read and checkout-permission problems. Confirm the runner's configuration with its administrator before approving Gate A.

`--oci-worker-no-process-sandbox` weakens process isolation *inside* the builder, so run this probe only for trusted source. A passing probe proves a trivial OCI build on that runner, **not** B-02 completion: Phase F must also build both application Dockerfiles there.

### Rootless BuildKit failure and Kaniko fallback probe

The first actual university runner probe, [job 2691850](https://gitlab.gwdg.de/min-favs/sharewise/-/jobs/2691850) at commit `44202d19`, ran with GitLab Runner 19.5.3 and the Docker executor on Linux/amd64. The job ran as UID 1000, with no effective capabilities; the rootless BuildKit daemon could not start:

```text
[rootlesskit:parent] error: failed to start the child: fork/exec /proc/self/exe: operation not permitted
```

This shows that **rootless BuildKit is blocked by the current runner policy**, before executing the test Dockerfile. The log alone does not identify which precise security mechanism rejected the call. Do not alter university runner security settings or assume rootless Buildah will work with the same namespace restrictions.

To test the maintained Kaniko alternative on the same runner:

1. Push the separately reviewed Phase A fallback patch to a **trusted, isolated, unprotected ref**. Start a web pipeline with `P26AB_KANIKO_PROBE=1`; the opt-in `probe-kaniko` job must not run in ordinary pipelines.
2. The job uses the digest-pinned [`osscontainertools/kaniko` debug executor](https://github.com/osscontainertools/kaniko/pkgs/container/kaniko) from a maintained community fork, not the archived `gcr.io/kaniko-project/executor`. The v1.28.6 choice includes the fix for the ACR credential-helper issue affecting releases before v1.28.3 (CVE-2026-105769); verify the exact pull digest from the GitLab runner trace. It builds the same `RUN`+`COPY` fixture without a Docker socket, Docker daemon, `privileged` mode, registry credentials or a registry push; it writes a local Docker image tarball and SHA-256 digest.
3. Inspect the job log and `kaniko-probe-results/` artifacts for the resolved executor image digest, Docker executor security profile (confirm with the administrator), build result and tarball. Inspect the resulting image's `/probe-run.txt` and `/probe-copy.txt` contents using a trusted local image tool, where available. Record the exact GitLab job URL and any failures. The Kaniko image normally runs as root **inside the unprivileged job container**; this is not rootless execution and does not mean a privileged Docker executor.

Kaniko executes Dockerfile commands in the job container's own security boundary rather than a nested sandbox. Restrict Dockerfile control and registry credentials to trusted refs when later implementing Phase F. A passing fallback probe establishes runner feasibility only; both actual application Dockerfiles still require runner testing in Phase F.

### Kaniko runner result (November 20, 2026)

The fallback probe **passed** on the university shared runner in [job 2691989](https://gitlab.gwdg.de/min-favs/sharewise/-/jobs/2691989), at commit `86c9a94aa5e61f19ce5bfc9a6174a20eb22458e6`. Evidence was supplied as `kaniko-probe-results.zip` containing `runner.txt`, `build.log`, `digest.txt` and `probe.docker.tar`.

- **Environment:** GitLab Runner 19.5.3, Docker executor, Linux/amd64; job UID:GID `0:0`, effective capabilities `00000000a80425fb` (no `CAP_SYS_ADMIN`). The probe rejected a mounted `/var/run/docker.sock` and did not configure DinD or a privileged service. Being root *inside* the container does not prove the Docker executor is privileged; the administrator must still confirm the runner policy.
- **Builder:** Community-maintained Kaniko v1.28.6, configured via the digest-pinned `ghcr.io/osscontainertools/kaniko:v1.28.6-debug@sha256:7ab44d0131c3051b6dea128067e258c7afb5c4815082a93b9cf6c624f2140bf0`. The ZIP does not contain the full GitLab pull trace, so the actual image-pull digest has **not** been independently checked against that configured pin.
- **Execution:** The Kaniko build log confirms both `RUN` and `COPY`, and explicitly reports that registry publication was skipped (`--no-push`). Independent inspection of the Docker archive confirmed `/probe-run.txt` contains `container-build-run-ok\n` and `/probe-copy.txt` contains `ShareWise P2.6ab rootless BuildKit runner probe\n`.
- **Artifact integrity:** The produced Docker tarball's SHA-256 is `b42d885cef7953d9ae5bf54ad2764cac29d020288cefabff633ae52b434f12ff`, matching `runner.txt`. Kaniko wrote the fixture image digest `sha256:c7e5b88a6a2edb66d1a2cabcfb1dab2a06488ed95c78b9645127d21d7d77de5b`; this is **not** the executor image's digest. A Docker archive does not by itself independently validate that registry-style manifest digest.

**Gate A proposal:** Select the maintained Kaniko fork for Phase F, subject to human approval and verification of the runner's configured security policy and resolved builder image pin. The probe establishes the viability of this trivial Dockerfile on the runner, **not** the production backend/frontend image builds, registry credentials or publishing gates. Retain both opt-in probe jobs isolated from normal CI for reproducible diagnostics until their removal is separately approved.

**Gate A:** The human reviews actual runner evidence and approves one daemonless builder. If access or compatibility is unavailable, record **blocked**; P2.6a may proceed independently, but P2.6b publication cannot be accepted. An unverified fallback must not silently replace BuildKit.

## Phase B - Reproducible backend and static frontend images (P2.6a)

**Dependency:** Approved image contracts D1–D3; no deployment host, live registry or production database is required to implement the build definitions. Build execution needs a container-capable machine.

- [ ] Refactor the frontend build-mode selection in `src/frontend/next.config.ts` and npm scripts to provide an explicit production **static export** without changing `next dev`, its `/api` rewrite, the normal build/test workflow or the existing disposable `test:static-export` probe. Prefer reusing one export configuration, not duplicating route definitions or acceptance logic (A-01, A-02).
- [ ] Replace the Node runtime frontend Dockerfile with a multi-stage Node 24/npm-lockfile export build and a lean **Nginx-only** runtime. Add a suitable `.dockerignore`. Ensure the final image has no Next.js standalone server, npm dependencies, Node interpreter, build-cache output or source-only development configuration. Retain source revision/version metadata as nonsecret OCI labels.
- [ ] Make the Spring image buildable from a clean checkout with a reproducible Maven/JDK 25 build stage (or a reviewed artifact-input alternative with a documented clean-build path). Use a Java 25 JRE-only runtime, fixed executable JAR, non-root UID and an HTTP readiness probe whose executable is genuinely present in the image. Add a focused `.dockerignore`; keep credentials, Maven cache, compiler and tests out of the runtime layers.
- [ ] Add tests/checks for export mode selection and generation of existing routes and assets, ordinary dev/standalone compatibility, Dockerfile build context and runtime image composition. Validate the existing static-export test and inspect final images. Avoid introducing new dependencies unless their offline-handoff implications are separately approved.

**Verification:** `npm run build`, `npm run lint`, `npm run typecheck`, `npm run test:typecheck`, `npm run test`, `npm run test:static-export`; `mvn -B -ntp -o -Punit-tests -Dmaven.repo.local=<supplied-cache> clean verify`; then build each image from an **uncached clean source** and inspect processes, contents, user and labels. Runtime database readiness is fully exercised in Phase D. Use actual supplied-tool paths for offline commands, never an unrelated system cache.

**Gate B:** Human reviews a focused production-image patch, export-mode tests and image inspection evidence (A-01 and image aspects of A-02). No requirement for prebuilt `target/` or Node at runtime; development behavior remains intact. Merge only after approval.

## Phase C - Static routing, HTTPS and trusted reverse proxy (P2.6a)

**Dependency:** Approved Phase B frontend image. This increment owns Nginx behavior; it does not yet claim the complete database-backed Compose startup.

- [ ] Add one focused Nginx configuration for the static export and `/api/*` reverse proxy. Resolve `/`, nested extensionless routes and generated assets exactly; missing routes return `404`, not a general SPA fallback. Disable dotfile/config exposure and apply the approved HTML, fingerprinted-asset and API cache policy (A-02, A-05).
- [ ] Terminate TLS using external certificate/key mounts, restrict accepted hostnames, and redirect HTTP to HTTPS. Support configurable local hostnames/port bindings without rebuilding the product image. Fail clearly when TLS material is missing; do not add certificate issuance or host provisioning (A-04, A-08).
- [ ] Forward the unchanged `/api/...` path, query, method, request body, authorization and cookie headers. Overwrite/remove untrusted `Forwarded` and `X-Forwarded-*` input and supply only trusted scheme/host/client metadata. Configure Spring's forwarded-header handling consistently, without rewriting application cookies or adding application authentication logic (A-03 through A-05).
- [ ] Provide focused proxy tests against a controlled backend that echoes observed request metadata and returns test `Set-Cookie` headers. Cover forged forwarding values, wrong host, `Host`/SNI and redirect behavior, HTTP body forwarding, query/path preservation, API cache exclusion, long nested routes and unknown paths. Verify Nginx syntax before HTTP probes.

**Gate C:** Human reviews the Nginx/configuration change and HTTP test evidence for A-02 through A-05. Absence of a live PostgreSQL-backed backend must be clearly marked; its real readiness acceptance remains Phase D.

## Phase D - Production database bootstrap, configuration and Compose (P2.6a)

**Dependency:** Approved image and proxy increments. Requires a real PostgreSQL 18 container, a container/Compose runtime and disposable test TLS/secrets for acceptance.

- [ ] Add a production-only PostgreSQL first-initialization script or equivalent that creates `sharewise_app` and `sharewise_migrator` with least privilege, without reusing development `_dev` credentials or copying test bootstrap into production. Consume protected secret files safely, do not echo or interpolate passwords into logs, and distinguish first initialization from restart with a populated volume (A-06, A-07).
- [ ] Add a Spring production configuration/profile that **requires** both datasource and Flyway passwords through runtime-provided secret files (for example Spring config-tree property names). Verify all development password defaults are overridden and missing/invalid production inputs fail closed. Keep Flyway schema/migration ownership with the migrator and application DML with `sharewise_app`; backend never needs PostgreSQL administrator credentials (A-06, A-08).
- [ ] Add production-style Compose service definitions for `frontend` (Nginx), `backend` (Spring) and `postgres` (PostgreSQL 18); use two networks and a persistent PostgreSQL volume. Publish only frontend HTTP/HTTPS ports. Mount externally generated local TLS fixtures and secrets via ignored paths; provide a committed safe configuration **template**, not usable credentials or private keys (A-08).
- [ ] Wire `pg_isready`, database-backed backend readiness and an executable frontend/Nginx probe. Use dependency health conditions for startup ordering and explicit restart policies, while acknowledging that Compose startup order does not guarantee automatic downstream recovery. Define visible failure states and local remediation (A-03, A-07, A-09).
- [ ] Supply a reproducible local operator workflow: create fresh local secrets/certificate in ignored paths, build from a clean checkout, `docker compose config`, start services, inspect health/logs, verify HTTPS/API, restart without data loss, and safely tear down a **disposable** test volume. Warn explicitly that volume deletion is destructive and that role-password rotation on existing volumes is a separate operation.
- [ ] Automate as many Compose/HTTP/SQL smoke checks as practical: empty-volume bootstrap, app-role DDL/Flyway-history denial, migration success and failure, missing secrets, persistence after restart, DB outage/recovery, only Nginx ports published, no secrets in image/CI logs, and HTTPS trust-boundary behavior (A-03 through A-09). Do not replace PostgreSQL checks with mocks.

**Verification:** Run `docker compose -f <production-compose-file> config -q`, clean image builds, `docker compose ... up --wait` (or documented equivalent), `curl` HTTPS/redirect/route probes, role-specific `psql` permission assertions and `docker compose` inspection on a suitable host. Re-run the relevant backend PostgreSQL Integration/E2E gates, plus Phase B/C regression tests. The host supplies runtime and images; the Maven-only offline handoff does not.

**Gate D:** Human reviews the stack/config/tests and executes the documented clean local production-style Compose workflow, including a negative/failure path. Record observed outcomes for A-01 through A-09, known caveats and the reviewed patch. P2.6a is reviewable independently of P2.6b; no deployment host or automated rollout is implied.

## Phase E - Shared version contract and CI rule design (P2.6b)

**Dependency:** Approved specification D10–D12. Can be developed separately from runner image execution, but publication waits for Phase F.

- [ ] Implement one narrowly scoped, shell-testable version constructor and one `construct-version` job/artifact: `MAJOR_VERSION.MINOR_VERSION.CI_PIPELINE_IID`, initially `0.1.<IID>`, with `-CI_COMMIT_REF_SLUG` for trusted non-main branches. Validate non-empty/positive IID, approved `MAJOR.MINOR` values, allowed tag characters and length. Identify **main by branch identity**, not merely a ref slug or tag named `main`; use the exact shared artifact in all subsequent jobs (B-03).
- [ ] Define an explicit publishing policy: **main** gets a versioned release and, after the matching pair succeeds, `latest`; trusted same-project non-main branch pipelines may receive prerelease tags; external/fork merge requests and other untrusted refs run non-publishing validation. Review how MR, tag, API/manual, scheduled and protected-ref pipelines are evaluated by the existing `workflow:rules` before adding publishing rules (B-07).
- [ ] Establish stage and artifact edges for version, two image pushes, pair validation and optional `latest` promotion. Do not accidentally bypass `build-backend`, `build-frontend`, Unit, Integration, E2E, frontend lint/typecheck/Vitest/static-export, offline-handoff, SAST or secret detection via a narrow `needs` list. Prefer stage ordering plus artifact-only `dependencies` when this preserves all gates (B-01).
- [ ] Test version logic locally across main, features, nested/slashed and long branch refs, malformed IID, tag/MR ref confusion and successive IIDs. Lint the expanded GitLab YAML and inspect the complete pipeline graph for safe rules and gates; do not publish in this phase.

**Gate E:** Human approves version semantics, exact CI ref/rule policy, and failing-input tests (B-01, B-03, B-07). Any deviation from the approved `0.1` initial version or publishing trust policy returns to specification review.

## Phase F - BuildKit registry publishing and immutable image evidence (P2.6b)

**Dependency:** Approved Phases A, D and E. Requires the actual GitLab runner, its registry and permission settings; do not claim acceptance from local YAML lint alone.

- [ ] Extend `.gitlab-ci.yml` with **two** daemonless image jobs using the Phase A pinned builder and the Phase B/D production Dockerfiles. Supply each Dockerfile's correct context (the backend may need the Maven project root); use scoped GitLab registry credentials and no Docker socket, DinD or privileged runner. Keep dependency caches separate from registry image-layer caches (B-02, B-04).
- [ ] Ensure pushes target distinct `${CI_REGISTRY_IMAGE}/backend` and `${CI_REGISTRY_IMAGE}/frontend` paths and use **one artifact-provided version**. Emit source-revision labels and the registry-confirmed digest/reference for each image. Detect missing auth, failed push or missing/unparsable digest as job failures (B-03 through B-05).
- [ ] After both **versioned** image pushes succeed, validate their version/commit pairing and assemble a release artifact containing full registry paths, digests, version, commit and pipeline identity. No complete-pair artifact on partial success. Promote `latest` on main **only after** both versioned images are valid, serialize promotion, and prevent an older/stale main pipeline from overwriting a newer alias. Surface an incomplete alias promotion as an error. Do not claim cross-repository atomicity for `latest`: future deployments must use the paired digest references (B-04 through B-06).
- [ ] Explicitly handle competing main pipelines, pipeline/job retries and pre-existing version tags: do not silently replace an earlier accepted version with a different digest. Review project registry tag protection and retention policy so digest-qualified images remain pullable for rollback; document any administrator action needed and do not claim permanence without evidence (B-06).
- [ ] Verify protected main, trusted feature ref, untrusted fork MR, invalid credentials, induced failure in one image job, and a complete successful pair. Pull and start both images by **digest** from a container-capable acceptance host. Execute a later pipeline to prove that a previous digest remains resolvable after `latest` advances (B-04 through B-07).

**Verification:** Actual GitLab CI lint and pipeline execution, original test/security job results, runner logs proving daemonless non-privileged operation, registry push/pull checks by tag and digest, release-artifact inspection, safe negative-path pipelines, and the already accepted Phase D Compose HTTP/DB smoke tests against CI-produced images. Do not print credential material into trace output.

**Gate F:** Human reviews the CI/publishing patch and actual GitLab evidence for B-01 through B-07. No `latest` claim without a completed versioned pair, no deployment reference without digests, and no claim of runner compatibility without an actual successful job. This establishes P2.6b only, not P2.6d deployment automation.

## Phase G - Final documentation, independent review and acceptance

**Dependency:** The P2.6a and P2.6b implementation increments have passed their own gates or are explicitly identified as blocked.

- [ ] Reconcile authoritative, steady-state documentation with **verified implementation** in [architecture](../../../architecture.md), [technical stack](../../../stack/overview.md), [frontend architecture](../../../architecture/frontend.md), [persistence architecture](../../../architecture/persistence.md), [development setup](../../../development.md), [frontend development](../../../development/frontend.md) and [test/CI guide](../../../development/tests.md). Make focused edits only where contracts changed. Explain local TLS/secrets, image build contexts, configuration and health semantics, CI image versioning, digest artifacts and the P2.6c/d interface; avoid copying task checklists into those documents.
- [ ] Run documentation-relative-link and command example checks, Markdown formatting checks and `git diff --check`. Reconcile this plan's checkboxes and scenario traceability with approved, observed evidence; unrun tests remain explicitly unverified. Keep acceptance records, actual CI job URLs, selected builder/version, local operator commands and limitations in the task review/handoff material.
- [ ] Obtain an **independent agent review** of the complete change(s) against the approved specification and security boundaries, address substantive findings through reviewable follow-up patches, and repeat affected acceptance tests.
- [ ] Have the human perform/manual-confirm local Compose startup, HTTPS/same-origin API behavior, failure and persistence checks, GitLab registry image publication and digest-pull workflow. Record manual results and final merge authorization separately. Only then mark P2.6a/P2.6b complete in the backlog; P2.6c/P2.6d remain pending.

**Gate G:** Specification, final implementation diff, tests, CI evidence, operator workflow and steady-state docs agree. The human approves final acceptance and **separately authorizes merge**; a successful build is not itself merge authorization.

## Review and evidence checklist

| Deliverable | Required evidence | Specification cases |
| --- | --- | --- |
| Image build and static frontend | Clean builds, static routes/assets and final-image inspection; ordinary development/build tests unchanged | A-01, A-02 |
| Nginx and trust boundary | `nginx -t`, HTTPS/host/redirect/headers/cookie/cache and missing-route probes against real or controlled backend | A-02–A-05, A-08 |
| Production DB and Compose | Empty-volume initialization, role permission checks, persistent restart, bad secret/migration, failure/recovery and exposed-port probes | A-03, A-06–A-09 |
| Builder and versioning | Actual unprivileged runner build logs, pinned builder, version-script test matrix and GitLab CI graph | B-01–B-03, B-07 |
| Registry publishing | Successful paired images and digests, authenticated pulls, partial-failure tests, main/prerelease tagging, retention and older-digest resolution | B-04–B-07 |
| Documentation and final review | Reconciled architectural/developer docs, independent review, human manual acceptance and approved patches | All applicable cases |

**Known environment gates:** A local container runtime, PostgreSQL and Node toolchain are absent from the supplied offline workspace, and the university runner's actual sandbox restrictions and registry policy have not been verified here. These are execution prerequisites, not grounds to weaken assertions or label missing tests as passing. Changes to the approved architecture or acceptance cases require renewed specification approval before revising affected phases.
