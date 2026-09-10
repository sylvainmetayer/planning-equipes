# 0028 — Transactions déclaratives (`@Transactional`, Narayana) pour les unités de travail composées

- **Statut** : proposé — mesuré, non tranché
- **Date** : septembre 2026
- **Portée** : `service/JdbcEditionScope`, les unités de travail qui composent plusieurs écritures, les signatures de service qui portent une `Connection`
- **Issue** : #448, née de #392 (A5, question 7)

## Contexte

Le dépôt n'a **aucun `@Transactional`**. Une transaction est un `scope.write` ou `scope.writeAndReturn` de `JdbcEditionScope`, qui fait lui-même `setAutoCommit(false)` / `commit()` / `rollback()`. Une unité de travail qui doit écrire plusieurs choses ou rien — un stand avec les typologies et l'emplacement qu'il nomme, un import, un plan persisté — les fait toutes sous un seul `scope.write`, en passant sa `Connection` aux dépôts et services qui en prennent une. La règle tient dans la signature : une méthode qui prend une `Connection` rejoint la transaction de l'appelant, une méthode qui n'en prend pas commite seule. C'est le patron retenu en A5 de #392, sans Narayana, et #448 demandait de mesurer ce que l'alternative coûterait et rapporterait avant de rouvrir la question.

Ce que le code du 10 septembre 2026 contient, compté par script (`origin/main` à `dcb77cf6`) :

| Quoi | Combien |
|---|---|
| `dataSource.getConnection()` directs, hors `JdbcEditionScope` | **78** dans 19 fichiers : 48 en lecture, 30 en écriture |
| … dont avec un `setAutoCommit(false)` à la main | 0 — toutes les écritures directes sont une instruction en autocommit |
| Appels à `scope.write` / `scope.writeAndReturn` | **37** dans 17 fichiers |
| Méthodes prenant une `Connection` (hors scope) | **41** dans 14 fichiers, 1 seule publique (`TypologieService.validateIds`) |
| … qui doublent une jumelle sans `Connection` | **8** (`create` × 3 services, `saveStand`, `saveEmplacement`, `saveTypologie`, `typologieExists`, `validateIds`) |
| … qui n'existent qu'avec une `Connection` | **33** : les étapes internes d'une unité de travail (`PlanningPersistenceService` en porte 9, `AnimateurRepository` et `StandRepository` 4 chacun) |
| Unités composées traversant une signature de service publique | **1** : `ReferenceDataService.writeStand(stand, typologies, emplacement)` |
| Sauts de thread où une transaction liée au thread compte | 2 `@Scheduled` (`BackupService`, `NotificationsPlanifieesService`), le pool de 2 threads du solveur, 4 services passant par `EditionContext.executeIn` |

## Ce que le prototype a établi

Quatre étapes sur une branche jetable, chacune mesurée par les trois tests de `creer_stand_complet` (`ReferentielMcpToolsTest`), dont celui qui vérifie qu'un stand refusé ne laisse ni typologie ni emplacement derrière lui.

1. **Narayana est déjà sur le classpath.** `quarkus-flyway` tire `quarkus-agroal`, qui tire `quarkus-narayana-jta`, `agroal-narayana` et `jakarta.transaction-api`. Adopter `@Transactional` n'ajoute aucune dépendance ; la source de données est déjà enrôlable.
2. **L'annotation seule casse tout : 3 tests sur 3.** Sous `@Transactional`, Agroal enrôle la connexion et refuse `commit()` ; le `catch` de `writeAndReturn` appelle alors `rollback()`, refusé à son tour — `SQLException: Attempting to rollback while enlisted in a transaction` — et c'est ce second refus qui remonte, le premier est perdu. Le point 1 de #448 (« `JdbcEditionScope` serait à réécrire avant le premier usage ») est confirmé, avec une circonstance aggravante : l'erreur qui remonte n'est pas la cause.
3. **La réécriture du scope tient en six lignes**, et la suite entière reste verte. `writeAndReturn` commence par `if (QuarkusTransaction.isActive()) return read(failure, statement);` — dans une transaction ouverte par l'intercepteur, le scope exécute et laisse l'intercepteur commiter ou annuler. Les trois tests passent, l'unité composée gardant ses paramètres `Connection`. La suite complète, jouée en six tranches : **1 553 tests, 0 échec, 4 ignorés** — identique à `main`. Hors transaction, `isActive()` est faux et rien ne change ; le coût réel du point 2 de #448 est donc celui-là, pas une réécriture.
4. **Avec ce scope, l'unité composée n'a plus besoin de `Connection`.** `writeStand` réécrit avec les trois écritures nues (`typologies.create(t)`, `emplacements.create(e)`, `stands.create(s)`), chacune un `scope.write` de son côté : 3 tests sur 3, y compris le rollback. Agroal rend la même connexion enrôlée à chaque `getConnection()` de la transaction, si bien que la validation des typologies du stand — une lecture sur une connexion « neuve » — voit celles écrites l'instant d'avant. C'est ce que Narayana achète : la composition ne se lit plus dans les signatures.

Ce qu'il ne change pas, mesuré aussi : les 78 `getConnection()` directs. Hors de toute méthode annotée, ils gardent leur autocommit ; **sous** un cadre annoté, ils le rejoignent silencieusement. Le point 3 de #448 se résume ainsi : aucun ne change de sémantique tant qu'aucune annotation n'est posée au-dessus, et tous en changent dès qu'une l'est. `EditionContext`, lui, n'est pas concerné : Narayana n'exécute aucun code applicatif sur ses propres threads (le *reaper* ne fait qu'annuler les transactions expirées), et la transaction comme l'édition restent liées au thread qui les a ouvertes. Les sauts de thread listés plus haut posent à une transaction exactement la question qu'ils posent déjà à l'édition, ni plus ni moins.

## Options

**A — Garder le patron maison.** Zéro changement. La composition reste visible dans les signatures ; ce qu'on y perd est mesuré : 8 signatures jumelles aujourd'hui, une unité composée traversant un service. Une deuxième et une troisième unité composée coûteraient chacune leurs jumelles.

**B — `@Transactional` pour les unités composées, et seulement elles.** Le scope des six lignes ; l'annotation sur les seules méthodes d'entrée qui composent (`writeStand` à trois arguments, l'import, la persistance d'un plan), jamais sur un dépôt ; les 8 jumelles supprimées. Trois garde-fous structurels, sans quoi le point 3 devient la classe de bug de #408 : (1) `connection.commit()` et `setAutoCommit` interdits hors de `JdbcEditionScope` — le pendant du test qui interdit déjà un `create*`/`update*` nu hors de la façade ; (2) `@Transactional` interdit hors de `service/` et sur toute méthode non publique ou appelée par `this.` (l'intercepteur CDI ne voit ni l'un ni l'autre) ; (3) `@Transactional` interdit sur une méthode qui soumet du travail à un autre thread. Le `catch` de `writeAndReturn` doit aussi conserver la cause première (`addSuppressed`), le point 2 l'a montré.

**C — `@Transactional` partout.** Non mesuré, et non souhaité : 30 écritures directes en autocommit changeraient de sens sans qu'une ligne d'entre elles ne bouge.

## Décision

Aucune, à dessein. Les mesures ci-dessus sont ce qui manquait pour trancher ; la décision appartient au mainteneur et sera consignée en révisant ce document. Ce qui est acté : **C est écarté**, et B ne s'adopte pas sans ses trois garde-fous, que l'analyse a rendus précis.

## Conséquences

- Tant que le statut est « proposé », le patron maison reste la règle (`JdbcEditionScope`, javadoc « Composing writes ») : une nouvelle unité composée passe sa `Connection`.
- Une PR qui adopte B commence par le test structurel, puis le scope, puis l'annotation — dans cet ordre, pour que l'annotation ne puisse jamais précéder son garde-fou.
- Les chiffres de ce document se recomptent par script ; ils datent du 10 septembre 2026 et se périment avec chaque unité composée ajoutée.

## Annexe — reproduire le prototype

Sur `origin/main` (`dcb77cf6`), deux fichiers, seize lignes :

```java
// JdbcEditionScope.writeAndReturn, en tête
if (io.quarkus.narayana.jta.QuarkusTransaction.isActive()) {
    return read(failure, statement);
}
```

```java
// ReferenceDataService
@jakarta.transaction.Transactional
public WrittenStand writeStand(Stand stand, List<TypologieItem> typologiesACreer, Emplacement emplacementACreer) {
    for (TypologieItem typologie : typologiesACreer) {
        typologies.create(typologie);
    }
    if (emplacementACreer != null) {
        emplacements.create(emplacementACreer);
    }
    Stand ecrit = stands.create(stand);
    return new WrittenStand(ecrit, coherence.onStand(null, ecrit));
}
```

```sh
./mvnw test -Dtest='ReferentielMcpToolsTest#creerUnStandComplet*' -Dquarkus.http.test-port=0
```

Sans la première modification, les trois tests échouent sur le rollback refusé ; avec elle et l'ancienne version de `writeStand` (paramètres `Connection`), ils passent ; avec les deux, ils passent encore. Les comptages du tableau viennent de trois scripts d'inventaire (`getConnection()` classés lecture/écriture par le contenu du bloc `try`, déclarations de méthode prenant une `Connection`, jumelles par nom dans le même fichier) ; ils se refont avec `grep -rn "getConnection()" src/main/java` et `grep -rnE "\bConnection [a-zA-Z]+[,)]" src/main/java` comme point de départ.
