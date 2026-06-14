# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Кэшируем зависимости отдельно — пересборка только при изменении pom.xml
COPY pom.xml .
RUN mvn dependency:go-offline -B -q

# Копируем исходники и собираем JAR
COPY src ./src
RUN mvn package -DskipTests -B -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Минимальный безопасный образ: non-root пользователь
RUN addgroup -S appgrp && adduser -S appuser -G appgrp
USER appuser

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# JVM-флаги для контейнерного окружения с ограниченной памятью (Render free = 512MB)
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=70.0", \
  "-XX:+UseSerialGC", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
