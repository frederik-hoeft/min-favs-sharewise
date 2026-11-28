# Clean-checkout source build; no host-built target/ directory is required.
FROM maven:3.10.0-eclipse-temurin-25-noble AS builder
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn -B -ntp -Punit-tests -DskipUnitTests=true clean package \
    && cp target/*.jar /build/app.jar

# Debian 13 provides a JRE-only package through its security repository.
FROM debian:trixie-slim AS runtime
# Fail closed if the package mirror lacks the Java 25.0.5.1 security update
# (CVE-2026-114938). The runtime must also include the health probe executable.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates curl openjdk-25-jre-headless \
    && dpkg --compare-versions \
        "$(dpkg-query -W -f='${Version}' openjdk-25-jre-headless)" \
        ge '25.0.5.1+2-1~deb13u1' \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
# Fail closed when the image runs without the production secret-backed profile.
ENV SPRING_PROFILES_ACTIVE=production
ARG BUILD_VERSION=dev
ARG VCS_REF=unknown
LABEL org.opencontainers.image.title="ShareWise backend" \
      org.opencontainers.image.version="${BUILD_VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}"
COPY --from=builder --chown=10001:10001 /build/app.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
# The readiness endpoint returns 503 when its datasource check fails.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent --show-error --max-time 5 http://127.0.0.1:8080/api/system/v1/health > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# Only the explicit disposable smoke target contains the test fixture. It uses
# the same production app.jar and dependencies; no test framework is shipped.
FROM builder AS authorization-smoke-fixture
RUN jar --create --file /build/authorization-smoke.jar -C target/test-classes sharewise/deploymentfixture \
    && jar tf /build/app.jar > /tmp/production-entries \
    && ! grep -q '^BOOT-INF/classes/sharewise/deploymentfixture/' /tmp/production-entries

FROM runtime AS authorization-smoke
COPY --from=authorization-smoke-fixture --chown=10001:10001 /build/authorization-smoke.jar /app/authorization-smoke.jar
ENTRYPOINT ["java", "-Dloader.path=/app/authorization-smoke.jar", "-Dloader.main=sharewise.deploymentfixture.AuthorizationSmokeApplication", "-cp", "/app/app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"]

# The normal/default image never copies the fixture jar or overrides its entrypoint.
FROM runtime AS production
