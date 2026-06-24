# syntax=docker/dockerfile:1

# ----------------------------------------------------------------------
# Stage 1 — Build the fat jar with Maven (Temurin JDK 17)
# ----------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Cache dependencies first (only re-downloads when pom.xml changes)
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

# Build the application
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ----------------------------------------------------------------------
# Stage 2 — Minimal runtime (JRE only)
# ----------------------------------------------------------------------
FROM eclipse-temurin:17-jre
WORKDIR /app

# Non-root user for safety
RUN useradd -r -u 1001 ideaqr
COPY --from=build /build/target/ideaqr-gateway.jar app.jar
RUN mkdir -p /app/data && chown -R ideaqr:ideaqr /app
USER ideaqr

# The app honours $PORT (defaults to 8080) — Render/Heroku friendly.
EXPOSE 8080

# Sensible default JVM flags; render.yaml overrides JAVA_OPTS at deploy time. The shell
# form expands $JAVA_OPTS (the exec ["java", ...] form did NOT, so the configured flags
# were silently ignored). `exec` keeps Java as PID 1 for correct signal handling.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
