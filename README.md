# Planning Équipes

Implémentation initiale Quarkus + Timefold pour le planning du festival de jeux (15 jours, ~150 animateurs), basée sur `CLAUDE.md`.

## Stack

- Quarkus (API REST + serveur statique frontend)
- Timefold Solver (`HardMediumSoftScore`)
- PostgreSQL + Flyway
- Frontend vanilla JS (`src/main/resources/META-INF/resources`)
- Docker Compose

## Endpoints MVP

- `GET /api/planning/sample` : retourne un jeu d'exemple
- `POST /api/solve` : résout un planning envoyé en JSON

## Lancer en local

```bash
./mvnw quarkus:dev
```

## Lancer les tests

```bash
./mvnw test
```

## Lancer avec Docker Compose

```bash
docker compose up --build
```
