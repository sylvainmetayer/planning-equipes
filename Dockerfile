FROM maven:3.9-eclipse-temurin-25 AS build
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests dependency:go-offline
COPY src ./src
COPY .git ./.git
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
# L'application n'écrit rien sur le disque et n'ouvre qu'un port non
# privilégié : elle n'a aucune raison de tourner en root, où la moindre
# exécution de code arbitraire s'exercerait sur tout le conteneur.
RUN groupadd --system --gid 1001 planning && useradd --system --uid 1001 --gid planning planning
COPY --from=build --chown=1001:1001 /workspace/target/quarkus-app/ /app/
USER 1001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
