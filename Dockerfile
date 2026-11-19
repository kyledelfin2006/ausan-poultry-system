# syntax=docker/dockerfile:1

# ---- build stage -----------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Wrapper and pom first so the dependency layer is cached until they change.
COPY mvnw pom.xml ./
COPY .mvn .mvn
# Guard against CRLF checkouts on Windows breaking the shell script.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline || true

COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -DskipTests package \
 && java -Djarmode=tools -jar target/*.jar extract --layers --destination /workspace/layers

# ---- runtime stage ---------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S -G app -h /app app
WORKDIR /app

# Layered jar: rarely-changing layers first for better image caching.
COPY --from=build --chown=app:app /workspace/layers/dependencies/ ./
COPY --from=build --chown=app:app /workspace/layers/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/layers/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/layers/application/ ./

USER app
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

# exec so the JVM is PID 1 and receives SIGTERM (graceful shutdown).
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/*.jar"]
