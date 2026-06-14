# ============================================================
# IDEAQR Digital Gateway - Stage 2
# Multi-stage Dockerfile (Java 17), tuned for Render free tier
# ============================================================

# ---- Stage 1: build ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Cache dependencies first
COPY pom.xml .
RUN mvn -q -e dependency:go-offline

# Build the application
COPY src ./src
RUN mvn -q -e clean package -DskipTests

# ---- Stage 2: runtime ----
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Copy the fat jar from the build stage
COPY --from=build /build/target/ideaqr-gateway.jar app.jar

# Render provides PORT; default to 8080 for local runs
ENV PORT=8080
EXPOSE 8080

# JVM tuning for the 512MB Render free tier:
#  - MaxRAMPercentage=70 keeps the heap inside the container limit
#  - SerialGC has the smallest footprint for a single-CPU free instance
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70.0 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
