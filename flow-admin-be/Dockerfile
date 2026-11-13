FROM maven:3.9.16-eclipse-temurin-25 AS build

WORKDIR /workspace
COPY . ./
RUN mvn -pl admin-be-boot -am -DskipTests package

FROM eclipse-temurin:25-jre

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home flowadmin
WORKDIR /app
COPY --from=build /workspace/admin-be-boot/target/admin-be-boot-*.jar app.jar
USER flowadmin
EXPOSE 8081
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=30 \
    CMD curl --fail --silent http://localhost:8081/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
