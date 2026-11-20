# An actual RUN step exercises unprivileged user namespaces and OCI worker mounts.
# This is a disposable capability probe, not an application image.
FROM alpine:3.22
RUN printf 'buildkit-rootless-run-ok\n' > /probe-run.txt
COPY payload.txt /probe-copy.txt
