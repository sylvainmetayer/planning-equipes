FROM maven:3.9-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976 AS build
# Les paquets apt ne sont pas épinglés à une version (hadolint DL3008) : les
# dépôts Debian/Ubuntu et PGDG retirent les anciennes, un build épinglé
# casserait au premier correctif de sécurité, et Renovate ne sait pas les
# suivre. Ce qui est figé, c'est l'image de base ; le scan Trivy de l'image
# publiée surveille le reste.
# hadolint ignore=DL3008
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests dependency:go-offline
COPY src ./src
COPY .git ./.git
# The Maven revision comes from the git tag being built (docs/versioning.md):
# an exact `v*` tag becomes the version (v1.2.0 → 1.2.0), any other commit
# keeps its short SHA so the startup line never claims a version that was
# not released.
RUN --mount=type=cache,target=/root/.m2 \
    REVISION="$(git describe --tags --exact-match HEAD 2>/dev/null || git rev-parse --short HEAD)" && \
    mvn -q -DskipTests -Drevision="${REVISION#v}" package

FROM eclipse-temurin:25-jre@sha256:fcd7fd7b387f94bb2ac461478a7436ad8e349924c374ea8313919624dceae636
WORKDIR /app
# pg_dump pour la sauvegarde automatique de nuit (BACKUP_DIR). Le client vient
# du dépôt PGDG et non de celui d'Ubuntu, qui n'offre que la version 16 : un
# pg_dump plus ancien que le serveur refuse de tourner, et le serveur de la pile
# de production est un PostgreSQL 18. Cette version-là est donc à faire évoluer
# en même temps que l'image `postgres:` de docker-compose.prod.yml. Pas de
# version de paquet épinglée : voir l'étape de construction plus haut.
# hadolint ignore=DL3008
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
    && install -d /usr/share/postgresql-common/pgdg \
    && curl -fsSL --proto '=https' --tlsv1.2 https://www.postgresql.org/media/keys/ACCC4CF8.asc \
         -o /usr/share/postgresql-common/pgdg/pgdg.asc \
    && echo "deb [signed-by=/usr/share/postgresql-common/pgdg/pgdg.asc] https://apt.postgresql.org/pub/repos/apt $(. /etc/os-release && echo "$VERSION_CODENAME")-pgdg main" \
         > /etc/apt/sources.list.d/pgdg.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends postgresql-client-18 \
    && apt-get purge -y curl gnupg \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/* \
# En dehors du répertoire de sauvegarde, l'application n'écrit rien sur le
# disque, et n'ouvre qu'un port non privilégié : elle n'a aucune raison de
# tourner en root, où la moindre exécution de code arbitraire s'exercerait sur
# tout le conteneur.
#
# L'uid est 1000 parce que c'est celui du premier compte d'une machine Linux :
# un répertoire de l'hôte monté sur `/backup` lui appartient alors déjà, et
# l'exploitant n'a pas de `chown` à faire pour que la sauvegarde puisse écrire.
# L'image de base est une Ubuntu, qui livre son propre compte `ubuntu` sur ce
# même 1000 : il faut le retirer d'abord, sinon `useradd` échoue sur un uid
# déjà pris.
    && userdel --remove ubuntu \
    && groupadd --gid 1000 planning \
    && useradd --uid 1000 --gid planning --no-create-home --shell /usr/sbin/nologin planning \
# Créé ici, et appartenant déjà à l'utilisateur applicatif : un volume Docker
# monté sur un chemin qui existe dans l'image en reprend les droits. Sans cela
# il arriverait en `root:root` et la première sauvegarde échouerait sur un
# refus d'écriture.
#
# `/backups` est l'ancien chemin, celui qu'un `.env.prod` d'avant ce changement
# écrit encore (BACKUP_DIR=/backups) : un lien vers `/backup` le garde valide,
# faute de quoi la sauvegarde de nuit échouerait sans que personne n'ait rien
# touché. Voir docs/exploitation.md § La sauvegarde automatique.
    && install -d -o 1000 -g 1000 /backup \
    && ln -s /backup /backups \
    && chown -h 1000:1000 /backups
COPY --from=build --chown=1000:1000 /workspace/target/quarkus-app/ /app/
USER 1000
EXPOSE 8080
# Le solveur vit de sa mémoire : sa recherche locale alloue sans relâche, et
# avec le tas par défaut de la JVM (25 % de la limite du conteneur, 256 Mo sous
# 1 Go) il passe son temps à collecter. Trois quarts de la limite pour le tas,
# et le collecteur parallèle, fait pour le débit (le défaut est G1, ou Serial
# sous 2 cœurs ou 1,8 Go), au prix de pauses de l'ordre du dixième de seconde :
# sur une petite machine, les deux ensemble font évaluer au calcul plus de deux
# fois plus de coups par seconde qu'avec les défauts sous 1 Go. Le pourcentage
# suppose une limite de mémoire sur le conteneur : sans elle, c'est 75 % de
# l'hôte.
#
# `JDK_JAVA_OPTIONS` plutôt que `JAVA_TOOL_OPTIONS` : seul le lanceur `java` le
# lit, et un exploitant le remplace entièrement en le redéfinissant dans son
# environnement. Voir docs/exploitation.md § La mémoire de l'application.
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseParallelGC"
ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
