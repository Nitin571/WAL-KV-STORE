# --- Stage 1: Build ---
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# --- Stage 2: Hardened Runtime ---
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Install curl for health checking, create non-root appuser
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    useradd -u 10001 -r -s /bin/false appuser && \
    mkdir -p /data/wal /app && \
    chown -R appuser:appuser /data/wal /app

# Copy the executable jar
COPY --from=builder --chown=appuser:appuser /app/target/kvstore.jar /app/kvstore.jar

# Switch to non-root user
USER appuser

# Consistent Configuration defaults
ENV SERVER_PORT=8080
ENV WAL_FILE=/data/wal/store.wal
ENV WAL_AUTO_COMPACT_THRESHOLD_BYTES=10485760

EXPOSE 8080
VOLUME ["/data/wal"]

HEALTHCHECK --interval=10s --timeout=3s --retries=3 --start-period=5s \
    CMD curl -f http://localhost:8080/admin/health || exit 1

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/kvstore.jar"]
