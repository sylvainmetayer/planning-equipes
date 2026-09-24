# L'image Keycloak de Planning Équipes : l'image officielle, plus deux choses
# que celle-ci ne porte pas.
#
#   1. L'authentificateur « code par e-mail » (keycloak/code-email/). Keycloak
#      26 ne sait pas envoyer de code à la connexion ; c'est la seule des
#      méthodes du realm qui demande du code. Passkeys, TOTP et fournisseurs
#      externes sont natifs.
#   2. Le thème « planning » (themes/planning/) : la page de connexion et les
#      e-mails aux couleurs du produit, avec un dossier brand/ qu'un client
#      remplace par ses visuels — docs/keycloak.md § Le thème.
#
# Le contexte de construction est la RACINE du dépôt, pas ce dossier : le
# module Maven vit sous keycloak/code-email/. D'où `context: .` avec
# `dockerfile: docker/keycloak/Dockerfile` dans docker-compose.yml.
#
# C'est cette image que la pile de développement, la CI et la production
# emploient — la même partout, publiée par
# .github/workflows/docker-keycloak-ghcr.yml sous
# ghcr.io/sylvainmetayer/planning-equipes-keycloak.
#
# Deux étapes plutôt qu'un JAR versionné dans le dépôt : un binaire commité
# dérive de sa source sans que rien ne le dise, et personne ne relit un JAR.
#
# Les deux versions de Keycloak — l'image ci-dessous et `keycloak.version`
# dans keycloak/code-email/pom.xml — bougent ENSEMBLE : un greffon compilé
# contre une autre lignée se charge, puis casse à la première méthode
# disparue. Le JDK de l'étape de construction est celui de Keycloak (21), pas
# celui de l'application (mise.toml) : un JAR compilé pour 25 lèverait
# UnsupportedClassVersionError dans le serveur.

FROM maven:3.9-eclipse-temurin-21 AS extension
WORKDIR /src

# Le POM seul d'abord : les dépendances ne se retéléchargent qu'au changement de
# ce fichier, pas à chaque retouche du code.
COPY keycloak/code-email/pom.xml ./pom.xml
RUN mvn -B --no-transfer-progress dependency:go-offline

COPY keycloak/code-email/src ./src
RUN mvn -B --no-transfer-progress package

FROM quay.io/keycloak/keycloak:26.5.7

# /opt/keycloak/providers : c'est là que le serveur cherche ses greffons au
# démarrage. Aucun enregistrement ailleurs n'est nécessaire — le fichier
# META-INF/services du JAR suffit à le faire connaître.
COPY --from=extension --chown=1000:0 /src/target/planning-keycloak-code-email.jar \
     /opt/keycloak/providers/planning-keycloak-code-email.jar

# Le thème, puis le dossier brand/ rendu visible aux deux types de thème par un
# lien symbolique. Keycloak sert `login/resources/brand/…` et
# `email/resources/brand/…` ; un client ne monte qu'UN dossier,
# /opt/keycloak/themes/planning/brand, et les deux le voient. Un seul point de
# montage, c'est ce qui fait qu'un e-mail et une page de connexion ne peuvent
# pas porter deux logos différents par oubli.
COPY --chown=1000:0 docker/keycloak/themes/planning /opt/keycloak/themes/planning
USER root
RUN ln -s ../../brand /opt/keycloak/themes/planning/login/resources/brand \
 && mkdir -p /opt/keycloak/themes/planning/email/resources \
 && ln -s ../../brand /opt/keycloak/themes/planning/email/resources/brand \
 && chown -R 1000:0 /opt/keycloak/themes/planning
# Retour à l'utilisateur non privilégié de l'image officielle : le serveur ne
# tourne jamais en root.
USER 1000

# Construction « optimisée » : la production démarre avec `start --optimized`
# sans refaire ce travail à chaque redémarrage, et c'est aussi ici qu'un JAR
# de greffon qui ne se charge pas fait échouer la construction plutôt que le
# premier démarrage.
#
# Les options de construction sont passées à la commande, PAS posées en ENV :
# un `KC_DB=postgres` laissé dans l'environnement de l'image s'appliquerait
# aussi à `start-dev`, qui chercherait alors une base sur localhost:5432 au
# lieu de son store embarqué. `start-dev` (développement, CI) reconstruit avec
# ses propres réglages ; `start --optimized` (production) reprend ceux-ci.
RUN /opt/keycloak/bin/kc.sh build --db=postgres --health-enabled=true
