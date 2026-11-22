# Reusable, network-isolated key bootstrapper for local or external deployments.
FROM alpine:3.24
RUN apk add --no-cache openssl
COPY --chmod=0555 init-iam-keys.sh /usr/local/bin/init-iam-keys.sh
ENTRYPOINT ["/usr/local/bin/init-iam-keys.sh"]
