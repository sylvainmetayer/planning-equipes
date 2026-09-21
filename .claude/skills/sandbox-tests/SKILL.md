---
name: sandbox-tests
description: Run the full backend test suite of planning-equipes in a sandbox without mise and without Docker (Claude Code on the web, a fresh container) — JDK 25 and PostgreSQL from apt in place of the dev-services container, the %test. override that makes it stick, and the -DskipITs=false password trap. Use whenever ./mvnw cannot start, Docker is missing, or before reporting that the backend could not be tested.
---

# Running the suite without `mise` and without Docker

A sandbox (Claude Code on the web, a fresh container) usually has no `mise`, an
older JDK and no `node_modules`. **Do not report the suite as unrunnable — the
three pieces are one `apt-get` away**, and a claim that the backend could not be
tested is worth a lot less than the run itself.

```bash
apt-get update -qq                                   # the image's index is stale:
                                                     # openjdk-25 is invisible without this
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-25-jdk-headless
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64  # `.mvn/jvm.config` carries Java 24+
                                                     # flags, so an older JDK cannot even
                                                     # start Maven
cd src/main/webui && npm ci                          # needs Node ≥ 22.22.3 for the
                                                     # Angular CLI; install it if `node -v`
                                                     # is older
```

The `@QuarkusTest` classes need a database, and Quarkus dev services want a
Docker daemon that a sandbox rarely has (and Docker Hub blobs are often
unreachable even when `dockerd` starts). PostgreSQL from `apt` answers just as
well — the suite only needs a server, not the pinned image:

```bash
pg_ctlcluster 16 main start
su postgres -c "psql -c \"CREATE USER festival WITH PASSWORD 'festival' SUPERUSER;\""
su postgres -c "psql -c \"CREATE DATABASE festival OWNER festival;\""
./mvnw test '-D%test.quarkus.datasource.devservices.enabled=false' \
  -Dquarkus.datasource.jdbc.url=jdbc:postgresql://127.0.0.1:5432/festival \
  -Dquarkus.datasource.username=festival -Dquarkus.datasource.password=festival
```

The `%test.` prefix on the first override is what matters: `application.properties`
pins `%test.quarkus.datasource.devservices.enabled=true`, and a profile-specific
value outranks a plain system property.

Do not settle for `-Dmaven.compiler.release=21` on the JDK the image ships
with — it compiles all but a file or two and proves nothing about the
version that is deployed. What this loses is the fresh database per run
(`devservices.reuse=false` exists to prove that `V1..Vn` migrate a bare
database): a migration change deserves a `DROP DATABASE festival` first.


**`-DskipITs=false` needs a password that is not the shipped one.** The `*IT`
classes launch the packaged application, so it boots in `%prod` — where
`DefaultSecrets` refuses `DB_PASSWORD=festival`, the very value the recipe
above gives PostgreSQL, and the run dies on "Unable to determine the status of
the running process". CI never meets this: its dev services hand out a random
password. Give the role one of its own for that run
(`ALTER USER festival WITH PASSWORD 'autre-que-l-exemple'`, passed as
`-Dquarkus.datasource.password=…`), and leave `ADMIN_PASSWORD` alone — the pom
already hands the launched process a real one (`it.admin.password`), and
overriding it fails every `*IT` on `j_security_check` instead.
