# Licences des dépendances tierces

**Fichier généré — ne pas le modifier à la main.** Il se régénère en deux
commandes, depuis la racine puis depuis `src/main/webui` :

```bash
./mvnw license:add-third-party
npm run licences
```

Le job `test` du workflow *Tests* refait les deux et refuse la branche si le
fichier commité a pris du retard.

## Pourquoi ce fichier

L'application est distribuée sous AGPL-3.0-only — c'est l'objet de
[`LICENSE`](../LICENSE) et de [`NOTICE`](../NOTICE). Mais l'image publiée
redistribue aussi les bibliothèques ci-dessous, et leurs licences MIT, BSD ou
Apache demandent que leur notice voyage avec le binaire. Ce fichier est la
réponse à la première question que pose un tiers qui déploie l'image : qu'est-ce
qu'il y a dedans, et sous quoi ?

Ce qu'il ne couvre **pas** : les paquets du système de base de l'image (JRE
Temurin, client PostgreSQL, Ubuntu). Ceux-là sont inventoriés par le SBOM
CycloneDX que `docker-ghcr.yml` produit et attache à chaque image publiée —
voir [`developpement.md`](developpement.md).

## Résumé

Un composant sous double licence compte dans chacune des deux.

| Licence | Dépendances Java | Paquets npm |
| --- | ---: | ---: |
| 0BSD | — | 1 |
| Apache-2.0 | 188 | 4 |
| BSD-2-Clause | 3 | 2 |
| CC-BY-4.0 | — | 1 |
| CC0-1.0 | 1 | — |
| EDL-1.0 | 7 | — |
| EPL-1.0 | 6 | — |
| EPL-2.0 | 14 | — |
| GPL-2.0-with-classpath-exception | 13 | — |
| ISC | — | 10 |
| LGPL-2.1 | 1 | — |
| MIT | 2 | 71 |
| MIT-0 | 1 | — |
| MPL-2.0 | 1 | — |
| Public Domain | 1 | — |
| **Total** | **216** | **89** |

## Dépendances Java

Fermeture transitive des scopes `compile` et `runtime` : ce que l'image
embarque dans `target/quarkus-app/`. Les scopes `test` et `provided` en sont
exclus — rien de ce qu'ils apportent n'est distribué.

| Dépendance | Version | Licence |
| --- | --- | --- |
| `ai.timefold.solver:timefold-solver-core` | 2.5.0 | Apache-2.0 |
| `com.aayushatharva.brotli4j:brotli4j` | 1.23.0 | Apache-2.0 |
| `com.aayushatharva.brotli4j:native-linux-x86_64` | 1.23.0 | Apache-2.0 |
| `com.aayushatharva.brotli4j:service` | 1.23.0 | Apache-2.0 |
| `com.cronutils:cron-utils` | 9.2.1 | Apache-2.0 |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.22 | Apache-2.0 |
| `com.fasterxml.jackson.core:jackson-core` | 2.22.0 | Apache-2.0 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.0 | Apache-2.0 |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` | 2.22.2 | Apache-2.0 |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.22.0 | Apache-2.0 |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.22.2 | Apache-2.0 |
| `com.fasterxml.jackson.module:jackson-module-parameter-names` | 2.22.0 | Apache-2.0 |
| `com.fasterxml:classmate` | 1.7.1 | Apache-2.0 |
| `com.github.librepdf:openpdf` | 3.0.5 | LGPL-2.1 ou MPL-2.0 |
| `com.github.victools:jsonschema-generator` | 4.38.0 | Apache-2.0 |
| `com.google.zxing:core` | 3.5.4 | Apache-2.0 |
| `com.sun.istack:istack-commons-runtime` | 4.1.2 | EDL-1.0 |
| `io.agroal:agroal-api` | 3.2.1 | Apache-2.0 |
| `io.agroal:agroal-narayana` | 3.2.1 | Apache-2.0 |
| `io.agroal:agroal-pool` | 3.2.1 | Apache-2.0 |
| `io.micrometer:micrometer-commons` | 1.17.0 | Apache-2.0 |
| `io.micrometer:micrometer-core` | 1.17.0 | Apache-2.0 |
| `io.micrometer:micrometer-observation` | 1.17.0 | Apache-2.0 |
| `io.netty:netty-buffer` | 4.1.137.Final | Apache-2.0 |
| `io.netty:netty-codec` | 4.1.137.Final | Apache-2.0 |
| `io.netty:netty-codec-dns` | 4.1.137.Final | Apache-2.0 |
| `io.netty:netty-codec-haproxy` | 4.1.137.Final | Apache-2.0 |
| `io.netty:netty-codec-http` | 4.1.137.Final | Apache-2.0 |
| `io.netty:netty-codec-http2` | 4.1.137.Final | Apache-2.0 |
| `io.netty:netty-codec-socks` | 4.1.137.Final | Apache-2.0 |
| `io.netty:netty-common` | 4.1.137.Final | Apache-2.0 |
| `io.netty:netty-handler` | 4.1.137.Final | Apache-2.0 |
| `io.netty:netty-handler-proxy` | 4.1.137.Final | Apache-2.0 |
| `io.netty:netty-resolver` | 4.1.137.Final | Apache-2.0 |
| `io.netty:netty-resolver-dns` | 4.1.137.Final | Apache-2.0 |
| `io.netty:netty-tcnative-classes` | 2.0.81.Final | Apache-2.0 |
| `io.netty:netty-transport` | 4.1.137.Final | Apache-2.0 |
| `io.netty:netty-transport-native-unix-common` | 4.1.137.Final | Apache-2.0 |
| `io.opentelemetry.instrumentation:opentelemetry-instrumentation-api` | 2.28.1 | Apache-2.0 |
| `io.opentelemetry.semconv:opentelemetry-semconv` | 1.41.1 | Apache-2.0 |
| `io.opentelemetry:opentelemetry-api` | 1.62.0 | Apache-2.0 |
| `io.opentelemetry:opentelemetry-api-incubator` | 1.62.0-alpha | Apache-2.0 |
| `io.opentelemetry:opentelemetry-common` | 1.62.0 | Apache-2.0 |
| `io.opentelemetry:opentelemetry-context` | 1.62.0 | Apache-2.0 |
| `io.quarkiverse.mcp:quarkus-mcp-server-core` | 1.13.2 | Apache-2.0 |
| `io.quarkiverse.mcp:quarkus-mcp-server-http` | 1.13.2 | Apache-2.0 |
| `io.quarkiverse.mcp:quarkus-mcp-server-sse-client` | 1.13.2 | Apache-2.0 |
| `io.quarkiverse.quinoa:quarkus-quinoa` | 2.9.0 | Apache-2.0 |
| `io.quarkus.arc:arc` | 3.38.3 | Apache-2.0 |
| `io.quarkus.gizmo:gizmo2` | 2.1.1 | Apache-2.0 |
| `io.quarkus.qute:qute-core` | 3.38.3 | Apache-2.0 |
| `io.quarkus.resteasy.reactive:resteasy-reactive` | 3.38.3 | Apache-2.0 |
| `io.quarkus.resteasy.reactive:resteasy-reactive-common` | 3.38.3 | Apache-2.0 |
| `io.quarkus.resteasy.reactive:resteasy-reactive-common-types` | 3.38.3 | Apache-2.0 |
| `io.quarkus.resteasy.reactive:resteasy-reactive-jackson` | 3.38.3 | Apache-2.0 |
| `io.quarkus.resteasy.reactive:resteasy-reactive-vertx` | 3.38.3 | Apache-2.0 |
| `io.quarkus.security:quarkus-security` | 2.3.2 | Apache-2.0 |
| `io.quarkus.vertx.utils:quarkus-vertx-utils` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-agroal` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-arc` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-bootstrap-runner` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-classloader-commons` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-core` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-credentials` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-datasource` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-datasource-common` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-development-mode-spi` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-devservices` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-elytron-security` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-elytron-security-common` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-elytron-security-properties-file` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-flyway` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-fs-util` | 1.4.2 | Apache-2.0 |
| `io.quarkus:quarkus-ide-launcher` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-jackson` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-jdbc-postgresql` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-jsonp` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-mailer` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-mutiny` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-narayana-jta` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-netty` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-qute` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-rest` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-rest-common` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-rest-jackson` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-rest-jackson-common` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-scheduler` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-scheduler-api` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-scheduler-common` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-scheduler-kotlin` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-scheduler-spi` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-security` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-security-runtime-spi` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-smallrye-context-propagation` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-smallrye-health` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-smallrye-openapi` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-swagger-ui` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-tls-registry` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-tls-registry-spi` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-transaction-annotations` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-value-registry` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-vertx` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-vertx-http` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-vertx-latebound-mdc-provider` | 3.38.3 | Apache-2.0 |
| `io.quarkus:quarkus-virtual-threads` | 3.38.3 | Apache-2.0 |
| `io.sentry:sentry` | 8.57.0 | MIT |
| `io.smallrye.certs:smallrye-private-key-pem-parser` | 0.9.3 | Apache-2.0 |
| `io.smallrye.classfile:jdk-classfile-backport` | 26 | GPL-2.0-with-classpath-exception |
| `io.smallrye.common:smallrye-common-annotation` | 2.19.0 | Apache-2.0 |
| `io.smallrye.common:smallrye-common-classloader` | 2.19.0 | Apache-2.0 |
| `io.smallrye.common:smallrye-common-constraint` | 2.19.0 | Apache-2.0 |
| `io.smallrye.common:smallrye-common-cpu` | 2.19.0 | Apache-2.0 |
| `io.smallrye.common:smallrye-common-expression` | 2.19.0 | Apache-2.0 |
| `io.smallrye.common:smallrye-common-function` | 2.19.0 | Apache-2.0 |
| `io.smallrye.common:smallrye-common-io` | 2.19.0 | Apache-2.0 |
| `io.smallrye.common:smallrye-common-net` | 2.19.0 | Apache-2.0 |
| `io.smallrye.common:smallrye-common-os` | 2.19.0 | Apache-2.0 |
| `io.smallrye.common:smallrye-common-ref` | 2.19.0 | Apache-2.0 |
| `io.smallrye.common:smallrye-common-resource` | 2.19.0 | Apache-2.0 |
| `io.smallrye.common:smallrye-common-search` | 2.19.0 | Apache-2.0 |
| `io.smallrye.common:smallrye-common-vertx-context` | 2.19.0 | Apache-2.0 |
| `io.smallrye.config:smallrye-config` | 3.17.2 | Apache-2.0 |
| `io.smallrye.config:smallrye-config-common` | 3.17.2 | Apache-2.0 |
| `io.smallrye.config:smallrye-config-core` | 3.17.2 | Apache-2.0 |
| `io.smallrye.reactive:mutiny` | 3.3.0 | Apache-2.0 |
| `io.smallrye.reactive:mutiny-smallrye-context-propagation` | 3.3.0 | Apache-2.0 |
| `io.smallrye.reactive:mutiny-zero-flow-adapters` | 1.2.1 | Apache-2.0 |
| `io.smallrye.reactive:smallrye-mutiny-vertx-auth-common` | 3.23.0 | Apache-2.0 |
| `io.smallrye.reactive:smallrye-mutiny-vertx-bridge-common` | 3.23.0 | Apache-2.0 |
| `io.smallrye.reactive:smallrye-mutiny-vertx-core` | 3.23.0 | Apache-2.0 |
| `io.smallrye.reactive:smallrye-mutiny-vertx-mail-client` | 3.23.0 | Apache-2.0 |
| `io.smallrye.reactive:smallrye-mutiny-vertx-runtime` | 3.23.0 | Apache-2.0 |
| `io.smallrye.reactive:smallrye-mutiny-vertx-uri-template` | 3.23.0 | Apache-2.0 |
| `io.smallrye.reactive:smallrye-mutiny-vertx-web` | 3.23.0 | Apache-2.0 |
| `io.smallrye.reactive:smallrye-mutiny-vertx-web-common` | 3.23.0 | Apache-2.0 |
| `io.smallrye.reactive:smallrye-reactive-converter-api` | 3.0.3 | Apache-2.0 |
| `io.smallrye.reactive:smallrye-reactive-converter-mutiny` | 3.0.3 | Apache-2.0 |
| `io.smallrye:jandex` | 3.6.0 | Apache-2.0 |
| `io.smallrye:smallrye-context-propagation` | 2.3.0 | Apache-2.0 |
| `io.smallrye:smallrye-context-propagation-api` | 2.3.0 | Apache-2.0 |
| `io.smallrye:smallrye-context-propagation-jta` | 2.3.0 | Apache-2.0 |
| `io.smallrye:smallrye-context-propagation-storage` | 2.3.0 | Apache-2.0 |
| `io.smallrye:smallrye-fault-tolerance-vertx` | 6.11.2 | Apache-2.0 |
| `io.smallrye:smallrye-health` | 4.3.0 | Apache-2.0 |
| `io.smallrye:smallrye-health-api` | 4.3.0 | Apache-2.0 |
| `io.smallrye:smallrye-health-provided-checks` | 4.3.0 | Apache-2.0 |
| `io.smallrye:smallrye-open-api-core` | 4.3.5 | Apache-2.0 |
| `io.smallrye:smallrye-open-api-model` | 4.3.5 | Apache-2.0 |
| `io.vertx:vertx-auth-common` | 4.5.32 | Apache-2.0 ou EPL-1.0 |
| `io.vertx:vertx-bridge-common` | 4.5.32 | Apache-2.0 ou EPL-1.0 |
| `io.vertx:vertx-core` | 4.5.32 | Apache-2.0 ou EPL-2.0 |
| `io.vertx:vertx-mail-client` | 4.5.32 | Apache-2.0 ou EPL-1.0 |
| `io.vertx:vertx-uri-template` | 4.5.32 | Apache-2.0 ou EPL-1.0 |
| `io.vertx:vertx-web` | 4.5.32 | Apache-2.0 ou EPL-2.0 |
| `io.vertx:vertx-web-client` | 4.5.32 | Apache-2.0 ou EPL-1.0 |
| `io.vertx:vertx-web-common` | 4.5.32 | Apache-2.0 ou EPL-1.0 |
| `jakarta.activation:jakarta.activation-api` | 2.1.4 | EDL-1.0 |
| `jakarta.annotation:jakarta.annotation-api` | 3.0.0 | EPL-2.0 ou GPL-2.0-with-classpath-exception |
| `jakarta.authentication:jakarta.authentication-api` | 3.1.0 | EPL-2.0 ou GPL-2.0-with-classpath-exception |
| `jakarta.authorization:jakarta.authorization-api` | 3.0.0 | EPL-2.0 ou GPL-2.0-with-classpath-exception |
| `jakarta.el:jakarta.el-api` | 6.0.1 | EPL-2.0 ou GPL-2.0-with-classpath-exception |
| `jakarta.enterprise:jakarta.enterprise.cdi-api` | 4.1.0 | Apache-2.0 |
| `jakarta.enterprise:jakarta.enterprise.lang-model` | 4.1.0 | Apache-2.0 |
| `jakarta.inject:jakarta.inject-api` | 2.0.1 | Apache-2.0 |
| `jakarta.interceptor:jakarta.interceptor-api` | 2.2.0 | EPL-2.0 ou GPL-2.0-with-classpath-exception |
| `jakarta.json:jakarta.json-api` | 2.1.3 | EPL-2.0 ou GPL-2.0-with-classpath-exception |
| `jakarta.resource:jakarta.resource-api` | 2.1.0 | EPL-2.0 ou GPL-2.0-with-classpath-exception |
| `jakarta.servlet:jakarta.servlet-api` | 6.0.0 | EPL-2.0 ou GPL-2.0-with-classpath-exception |
| `jakarta.transaction:jakarta.transaction-api` | 2.0.1 | EPL-2.0 ou GPL-2.0-with-classpath-exception |
| `jakarta.validation:jakarta.validation-api` | 3.1.1 | Apache-2.0 |
| `jakarta.ws.rs:jakarta.ws.rs-api` | 3.1.0 | EPL-2.0 ou GPL-2.0-with-classpath-exception |
| `jakarta.xml.bind:jakarta.xml.bind-api` | 4.0.5 | EDL-1.0 |
| `org.crac:crac` | 1.5.0 | BSD-2-Clause |
| `org.eclipse.angus:angus-activation` | 2.0.3 | EDL-1.0 |
| `org.eclipse.microprofile.config:microprofile-config-api` | 3.1.1 | Apache-2.0 |
| `org.eclipse.microprofile.context-propagation:microprofile-context-propagation-api` | 1.3 | Apache-2.0 |
| `org.eclipse.microprofile.health:microprofile-health-api` | 4.0.1 | Apache-2.0 |
| `org.eclipse.microprofile.openapi:microprofile-openapi-api` | 4.1.1 | Apache-2.0 |
| `org.eclipse.parsson:parsson` | 1.1.9 | EPL-2.0 ou GPL-2.0-with-classpath-exception |
| `org.flywaydb:flyway-core` | 12.0.0 | Apache-2.0 |
| `org.glassfish.expressly:expressly` | 6.0.0 | EPL-2.0 ou GPL-2.0-with-classpath-exception |
| `org.glassfish.jaxb:jaxb-core` | 4.0.9 | EDL-1.0 |
| `org.glassfish.jaxb:jaxb-runtime` | 4.0.9 | EDL-1.0 |
| `org.glassfish.jaxb:txw2` | 4.0.9 | EDL-1.0 |
| `org.hdrhistogram:HdrHistogram` | 2.2.2 | BSD-2-Clause ou CC0-1.0 |
| `org.hibernate.validator:hibernate-validator` | 9.1.3.Final | Apache-2.0 |
| `org.jboss.logging:commons-logging-jboss-logging` | 2.0.0.Final | Apache-2.0 |
| `org.jboss.logging:jboss-logging` | 3.6.3.Final | Apache-2.0 |
| `org.jboss.logmanager:jboss-logmanager` | 3.2.2.Final | Apache-2.0 |
| `org.jboss.narayana.jta:narayana-jta` | 7.3.4.Final | Apache-2.0 |
| `org.jboss.narayana.jts:narayana-jts-integration` | 7.3.4.Final | Apache-2.0 |
| `org.jboss.slf4j:slf4j-jboss-logmanager` | 2.0.2.Final | Apache-2.0 |
| `org.jboss.threads:jboss-threads` | 3.9.2 | Apache-2.0 |
| `org.jboss:jboss-transaction-spi` | 8.0.0.Final | Public Domain |
| `org.jctools:jctools-core` | 4.0.5 | Apache-2.0 |
| `org.jspecify:jspecify` | 1.0.0 | Apache-2.0 |
| `org.postgresql:postgresql` | 42.7.13 | BSD-2-Clause |
| `org.reactivestreams:reactive-streams` | 1.0.4 | MIT-0 |
| `org.slf4j:slf4j-api` | 2.0.18 | MIT |
| `org.wildfly.common:wildfly-common` | 2.0.1 | Apache-2.0 |
| `org.wildfly.security:wildfly-elytron-asn1` | 2.9.2.Final | Apache-2.0 |
| `org.wildfly.security:wildfly-elytron-auth` | 2.9.2.Final | Apache-2.0 |
| `org.wildfly.security:wildfly-elytron-auth-server` | 2.9.2.Final | Apache-2.0 |
| `org.wildfly.security:wildfly-elytron-base` | 2.9.2.Final | Apache-2.0 |
| `org.wildfly.security:wildfly-elytron-credential` | 2.9.2.Final | Apache-2.0 |
| `org.wildfly.security:wildfly-elytron-encryption` | 2.9.2.Final | Apache-2.0 |
| `org.wildfly.security:wildfly-elytron-keystore` | 2.9.2.Final | Apache-2.0 |
| `org.wildfly.security:wildfly-elytron-password-impl` | 2.9.2.Final | Apache-2.0 |
| `org.wildfly.security:wildfly-elytron-permission` | 2.9.2.Final | Apache-2.0 |
| `org.wildfly.security:wildfly-elytron-provider-util` | 2.9.2.Final | Apache-2.0 |
| `org.wildfly.security:wildfly-elytron-realm` | 2.9.2.Final | Apache-2.0 |
| `org.wildfly.security:wildfly-elytron-util` | 2.9.2.Final | Apache-2.0 |
| `org.wildfly.security:wildfly-elytron-x500` | 2.9.2.Final | Apache-2.0 |
| `org.wildfly.security:wildfly-elytron-x500-cert` | 2.9.2.Final | Apache-2.0 |
| `org.wildfly.security:wildfly-elytron-x500-cert-util` | 2.9.2.Final | Apache-2.0 |
| `org.yaml:snakeyaml` | 2.6 | Apache-2.0 |

## Paquets npm

Fermeture des dépendances de production de `src/main/webui`, pairs compris.
C'est un **surensemble** de ce que le bundle Angular embarque réellement : un
paquet qui ne sert qu'à la compilation mais qu'une dépendance de production
entraîne (le compilateur Angular, par exemple) est listé ici alors que rien de
lui ne part dans le bundle. Surestimer ne coûte qu'une ligne ; sous-estimer
coûterait une notice manquante.

| Paquet | Version | Licence |
| --- | --- | --- |
| `@angular/cdk` | 22.0.6 | MIT |
| `@angular/common` | 22.0.8 | MIT |
| `@angular/compiler-cli` | 22.0.8 | MIT |
| `@angular/compiler` | 22.0.8 | MIT |
| `@angular/core` | 22.0.8 | MIT |
| `@angular/forms` | 22.0.8 | MIT |
| `@angular/localize` | 22.0.8 | MIT |
| `@angular/material` | 22.0.6 | MIT |
| `@angular/platform-browser` | 22.0.8 | MIT |
| `@angular/router` | 22.0.8 | MIT |
| `@babel/code-frame` | 7.29.7 | MIT |
| `@babel/compat-data` | 7.29.7 | MIT |
| `@babel/core` | 7.29.7 | MIT |
| `@babel/generator` | 7.29.7 | MIT |
| `@babel/helper-compilation-targets` | 7.29.7 | MIT |
| `@babel/helper-globals` | 7.29.7 | MIT |
| `@babel/helper-module-imports` | 7.29.7 | MIT |
| `@babel/helper-module-transforms` | 7.29.7 | MIT |
| `@babel/helper-string-parser` | 7.29.7 | MIT |
| `@babel/helper-validator-identifier` | 7.29.7 | MIT |
| `@babel/helper-validator-option` | 7.29.7 | MIT |
| `@babel/helpers` | 7.29.7 | MIT |
| `@babel/parser` | 7.29.7 | MIT |
| `@babel/template` | 7.29.7 | MIT |
| `@babel/traverse` | 7.29.7 | MIT |
| `@babel/types` | 7.29.7 | MIT |
| `@jridgewell/gen-mapping` | 0.3.13 | MIT |
| `@jridgewell/remapping` | 2.3.5 | MIT |
| `@jridgewell/resolve-uri` | 3.1.2 | MIT |
| `@jridgewell/sourcemap-codec` | 1.5.5 | MIT |
| `@jridgewell/trace-mapping` | 0.3.31 | MIT |
| `@sentry/angular` | 10.69.0 | MIT |
| `@sentry/browser-utils` | 10.69.0 | MIT |
| `@sentry/browser` | 10.69.0 | MIT |
| `@sentry/conventions` | 0.16.0 | MIT |
| `@sentry/core` | 10.69.0 | MIT |
| `@sentry/feedback` | 10.69.0 | MIT |
| `@sentry/replay-canvas` | 10.69.0 | MIT |
| `@sentry/replay` | 10.69.0 | MIT |
| `@standard-schema/spec` | 1.1.0 | MIT |
| `@types/babel__core` | 7.20.5 | MIT |
| `@types/babel__generator` | 7.27.0 | MIT |
| `@types/babel__template` | 7.4.4 | MIT |
| `@types/babel__traverse` | 7.28.0 | MIT |
| `ansi-regex` | 6.2.2 | MIT |
| `ansi-styles` | 6.2.3 | MIT |
| `baseline-browser-mapping` | 2.11.5 | Apache-2.0 |
| `browserslist` | 4.28.7 | MIT |
| `caniuse-lite` | 1.0.30001806 | CC-BY-4.0 |
| `chokidar` | 5.0.0 | MIT |
| `cliui` | 9.0.1 | ISC |
| `convert-source-map` | 1.9.0 | MIT |
| `convert-source-map` | 2.0.0 | MIT |
| `debug` | 4.4.3 | MIT |
| `electron-to-chromium` | 1.5.397 | ISC |
| `emoji-regex` | 10.6.0 | MIT |
| `entities` | 8.0.0 | BSD-2-Clause |
| `escalade` | 3.2.0 | MIT |
| `fdir` | 6.5.0 | MIT |
| `gensync` | 1.0.0-beta.2 | MIT |
| `get-caller-file` | 2.0.5 | ISC |
| `get-east-asian-width` | 1.6.0 | MIT |
| `js-tokens` | 4.0.0 | MIT |
| `jsesc` | 3.1.0 | MIT |
| `json5` | 2.2.3 | MIT |
| `leaflet` | 1.9.4 | BSD-2-Clause |
| `lru-cache` | 5.1.1 | ISC |
| `ms` | 2.1.3 | MIT |
| `node-releases` | 2.0.51 | MIT |
| `parse5` | 8.0.1 | MIT |
| `picocolors` | 1.1.1 | ISC |
| `picomatch` | 4.0.4 | MIT |
| `readdirp` | 5.0.0 | MIT |
| `reflect-metadata` | 0.2.2 | Apache-2.0 |
| `rxjs` | 7.8.2 | Apache-2.0 |
| `semver` | 6.3.1 | ISC |
| `semver` | 7.7.4 | ISC |
| `string-width` | 7.2.0 | MIT |
| `strip-ansi` | 7.2.0 | MIT |
| `tinyglobby` | 0.2.16 | MIT |
| `tslib` | 2.8.1 | 0BSD |
| `typescript` | 6.0.3 | Apache-2.0 |
| `update-browserslist-db` | 1.2.3 | MIT |
| `wrap-ansi` | 9.0.2 | MIT |
| `y18n` | 5.0.8 | ISC |
| `yallist` | 3.1.1 | ISC |
| `yargs-parser` | 22.0.0 | ISC |
| `yargs` | 18.0.0 | MIT |
| `zod` | 4.4.2 | MIT |
