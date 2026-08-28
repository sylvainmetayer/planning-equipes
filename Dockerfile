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
# pg_dump pour la sauvegarde automatique de nuit (BACKUP_DIR). Le client vient
# du dépôt PGDG et non de celui d'Ubuntu, qui n'offre que la version 16 : un
# pg_dump plus ancien que le serveur refuse de tourner, et le serveur de la pile
# de production est un PostgreSQL 18. Cette version-là est donc à faire évoluer
# en même temps que l'image `postgres:` de docker-compose.prod.yml.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
    && install -d /usr/share/postgresql-common/pgdg \
    && curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc \
         -o /usr/share/postgresql-common/pgdg/pgdg.asc \
    && echo "deb [signed-by=/usr/share/postgresql-common/pgdg/pgdg.asc] https://apt.postgresql.org/pub/repos/apt $(. /etc/os-release && echo $VERSION_CODENAME)-pgdg main" \
         > /etc/apt/sources.list.d/pgdg.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends postgresql-client-18 \
    && apt-get purge -y curl gnupg \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*
# En dehors du répertoire de sauvegarde, l'application n'écrit rien sur le
# disque, et n'ouvre qu'un port non privilégié : elle n'a aucune raison de
# tourner en root, où la moindre exécution de code arbitraire s'exercerait sur
# tout le conteneur.
RUN groupadd --system --gid 1001 planning && useradd --system --uid 1001 --gid planning planning
# Créé ici, et appartenant déjà à l'utilisateur applicatif : un volume Docker
# monté sur un chemin qui existe dans l'image en reprend les droits. Sans cela
# il arriverait en `root:root` et la première sauvegarde échouerait sur un
# refus d'écriture.
RUN install -d -o 1001 -g 1001 /backups
COPY --from=build --chown=1001:1001 /workspace/target/quarkus-app/ /app/
USER 1001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
