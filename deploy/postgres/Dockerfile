# Only the static, credential-free role creation script is baked into the image.
# Actual passwords are mounted from a Docker-managed volume at runtime.
FROM postgres:18-trixie
COPY deploy/postgres/10-create-roles.sh /docker-entrypoint-initdb.d/10-create-roles.sh
RUN chmod 0755 /docker-entrypoint-initdb.d/10-create-roles.sh
