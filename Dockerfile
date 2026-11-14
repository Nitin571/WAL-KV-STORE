# --- Stage 1: Build ---
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# --- Stage 2: Runtime ---
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Create dedicated directory for persistent WAL files
RUN mkdir -p /data/wal && chmod 777 /data/wal

# Copy the fat executable jar
COPY --from=builder /app/target/kvstore.jar /app/kvstore.jar

# Configuration defaults
ENV SERVER_PORT=8080
ENV WAL_FILE=/data/wal/store.wal
ENV WAL_AUTO_COMPACT_THRESHOLD_BYTES=52428800

EXPOSE 8080
VOLUME ["/data/wal"]

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/kvstore.jar"]
