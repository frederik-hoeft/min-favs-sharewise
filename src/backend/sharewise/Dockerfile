# Clean-checkout source build; no host-built target/ directory is required.
FROM maven:3.10.0-eclipse-temurin-25-noble AS builder
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn -B -ntp -Punit-tests -DskipUnitTests=true clean package \
    && cp target/*.jar /build/app.jar

FROM eclipse-temurin:25-jre-resolute AS runtime
# Install the probe executable in the runtime image, not just the build stage.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
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
