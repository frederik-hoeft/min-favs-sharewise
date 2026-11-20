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
