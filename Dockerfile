FROM maven:3.9-eclipse-temurin-25 AS build
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY pom.xml .
# Dependencies in a LAYER, not in a cache mount. `--mount=type=cache` is the
# faster of the two on a machine that keeps its builder, but it is invisible to
# `cache-to: type=gha` — which exports layers and nothing else — so every CI
# image build re-downloaded the whole `.m2` (audit #392, C9). Baked into this
# layer, it is exported with it and restored as long as `pom.xml` is unchanged,
# which is exactly when it is still valid. The price is paid where it is
# cheapest: a `pom.xml` that moves now re-downloads everything rather than the
# delta, on the rare local `docker compose --profile app up --build`.
#
# `quarkus:go-offline` beside `dependency:go-offline`, which does not see the
# same world: the `*-deployment` jars that `quarkus:build` resolves at
# augmentation time are the bulk of those 400 MB, and without this goal they
# would be re-fetched by the `package` below on every single build — the very
# download this layer exists to avoid. With it, that `package` fetches some ten
# more megabytes and nothing else.
RUN mvn -q -DskipTests dependency:go-offline quarkus:go-offline
COPY src ./src
COPY .git ./.git
# The Maven revision comes from the git tag being built (docs/versioning.md):
# an exact `v*` tag becomes the version (v1.2.0 → 1.2.0), any other commit
# keeps its short SHA so the startup line never claims a version that was
# not released.
RUN REVISION="$(git describe --tags --exact-match HEAD 2>/dev/null || git rev-parse --short HEAD)" && \
    mvn -q -DskipTests -Drevision="${REVISION#v}" package

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
#
# L'uid est 1000 parce que c'est celui du premier compte d'une machine Linux :
# un répertoire de l'hôte monté sur `/backups` lui appartient alors déjà, et
# l'exploitant n'a pas de `chown` à faire pour que la sauvegarde puisse écrire.
# L'image de base est une Ubuntu, qui livre son propre compte `ubuntu` sur ce
# même 1000 : il faut le retirer d'abord, sinon `useradd` échoue sur un uid
# déjà pris.
RUN userdel --remove ubuntu \
    && groupadd --gid 1000 planning \
    && useradd --uid 1000 --gid planning --no-create-home --shell /usr/sbin/nologin planning
# Créé ici, et appartenant déjà à l'utilisateur applicatif : un volume Docker
# monté sur un chemin qui existe dans l'image en reprend les droits. Sans cela
# il arriverait en `root:root` et la première sauvegarde échouerait sur un
# refus d'écriture.
RUN install -d -o 1000 -g 1000 /backups
COPY --from=build --chown=1000:1000 /workspace/target/quarkus-app/ /app/
USER 1000
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
