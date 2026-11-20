# RUN exercises the image builder instead of merely copying a file.
# This is a disposable capability probe, not an application image.
FROM alpine:3.22
RUN printf 'container-build-run-ok\n' > /probe-run.txt
COPY payload.txt /probe-copy.txt
