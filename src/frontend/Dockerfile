# The build stage resolves exactly the checked-in npm lockfile.
FROM node:24-alpine AS builder
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
COPY package.json package-lock.json .npmrc ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build:static

# Only exported HTML/assets are shipped; Node and node_modules remain in builder.
FROM nginx:1.30.7-alpine3.24 AS runtime
ARG BUILD_VERSION=dev
ARG VCS_REF=unknown
LABEL org.opencontainers.image.title="ShareWise frontend" \
      org.opencontainers.image.version="${BUILD_VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}"
# Only the approved configuration variables are substituted; Nginx variables
# such as $request_uri and $remote_addr remain evaluated per request.
ENV NGINX_ENVSUBST_FILTER="^SHAREWISE_"
COPY nginx/production.conf.template /etc/nginx/templates/default.conf.template
COPY nginx/15-configure-sharewise.sh /docker-entrypoint.d/15-configure-sharewise.sh
RUN chmod +x /docker-entrypoint.d/15-configure-sharewise.sh \
    && mkdir -p /etc/nginx/tls
COPY --from=builder /app/.next-export /usr/share/nginx/html
EXPOSE 80 443
# The health endpoint binds to loopback only, independent of public DNS/TLS.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8081/health || exit 1
