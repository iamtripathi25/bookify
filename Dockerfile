FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# Dependencies get their own layer, so they're downloaded again only when pom.xml changes.
# (No BuildKit cache mounts: Railway's builder requires service-specific mount ids.)
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package \
 && cp target/bookify-*.jar /src/app.jar

FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --home /app bookify
WORKDIR /app
COPY --from=build /src/app.jar app.jar
USER bookify
EXPOSE 8080
# Heap is explicit, never derived from host RAM (the deploy target caps the service at 1 GB).
ENV JAVA_OPTS="-Xms512m -Xmx512m"
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
