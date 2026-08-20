# Éditions — cloisonner le référentiel et les résultats de solveur

**Statut : implémenté.** Les migrations `V32` à `V35` (qui créent le
cloisonnement sous le nom `groupe`) puis `V36` (qui le renomme en `edition`,
plus parlant d'un point de vue métier), le contexte d'édition côté serveur,
l'API `/api/editions`, l'intercepteur et la page « Éditions » côté Angular
sont livrés. Une base existante devient une mono-édition `DEFAUT` strictement
identique à ce qu'elle était.

## 1. Le besoin

Avant cette évolution, la base ne contenait **qu'un seul référentiel** : un jeu
de stands, d'animateurs, de typologies, d'emplacements, de paramètres légaux et
un planning persisté. Préparer l'édition 2026 signifiait donc écraser les
données de 2025 — et avec elles le résultat de solveur qu'on aimerait pouvoir
relire, comparer, ou reprendre comme point de départ.

L'objectif : pouvoir tenir côte à côte une édition « Année 2025 » (archivée,
consultable, avec ses stands et ses animateurs de l'époque) et une édition
« Année 2026 » (en cours), **sans qu'aucune donnée de l'une ne fuite dans
l'autre**, et basculer de l'une à l'autre en un clic.

## 2. Ce qui existait déjà : `groupe_creneau`

Une notion de groupe existait déjà, mais **cloisonne uniquement les créneaux** :

- table `groupe_creneau (id, nom, actif, groupe_source_id)`, créée en `V10` ;
- `creneau.groupe_creneau_id` référence ce groupe ;
- un index unique partiel (`WHERE actif`) garantit **un seul groupe actif** —
  désormais un seul *par édition* (`V33`) ;
- le solveur ne construit son problème que sur les créneaux du groupe actif
  (`PlanningService.construireDepuisReferenceData` →
  `ReferenceDataService.listCreneauxGroupeActif`) ;
- `planning_resolution` mémorise pour quel groupe le dernier calcul a été fait,
  ce qui alimente le bandeau d'avertissement `app-groupe-mismatch-banner`.

Le cas d'usage d'origine est « préparer une grille de créneaux de repli et la
basculer au dernier moment ». C'est une notion **plus étroite** que celle
traitée ici : deux grilles alternatives à l'intérieur d'une même édition, pas
deux éditions distinctes. Cette notion de « groupe de créneaux » n'est **pas**
concernée par le renommage décrit ici — elle garde son nom, sa table
`groupe_creneau` et ses classes `GroupeCreneau`.

## 3. Décision de modélisation : deux niveaux, pas un

Deux options se présentaient.

| | A — deux niveaux (`edition` ⊃ `groupe_creneau`) | B — un seul niveau (on promeut `groupe_creneau`) |
| --- | --- | --- |
| Modèle | Nouvelle table `edition`, `groupe_creneau` devient son enfant | `groupe_creneau` devient l'unique périmètre |
| Grilles alternatives dans une même année | conservées telles quelles | il faut **cloner tout le référentiel** (150 animateurs, 60 stands) pour tester une autre grille |
| Migration | ajout d'une table + d'une colonne partout | renommage sémantique d'une notion existante, plus casse-gueule |
| Coût conceptuel | deux notions à expliquer | une seule |

**Retenu : A.** Le coût de B est le mauvais : dupliquer tout le référentiel pour
tester un autre découpage est exactement ce que `groupe_creneau` évite, et cette
capacité est utilisée (régénération de découpage, `groupe_source_id`). Les deux
notions répondent à deux questions différentes et restent distinctes.

Vocabulaire retenu dans l'UI, pour qu'« édition » ne soit pas ambigu avec
« groupe de créneaux » :

- **Édition** (table `edition`, ex-`groupe`) : le périmètre complet — « Année
  2025 » ;
- **Grille de créneaux** (table `groupe_creneau`) : une variante de découpage
  **à l'intérieur** d'une édition.

Le code Java et Angular a suivi ce même renommage (`Edition` / `EditionStore` /
`EditionContext`, …) ; seul `GroupeCreneau` — un concept distinct — garde son
nom.

## 4. Schéma

### 4.1 La table `edition` (créée sous le nom `groupe` par `V32`, renommée par `V36`)

```sql
-- V32, tel qu'appliqué à l'origine (la table s'appelait alors `groupe`) :
CREATE TABLE groupe (
    id      VARCHAR(64) PRIMARY KEY,
    nom     VARCHAR(255) NOT NULL,
    defaut  BOOLEAN NOT NULL DEFAULT FALSE,
    cree_le TIMESTAMP NOT NULL DEFAULT now()
);

-- Un seul groupe par défaut, garanti côté base (même motif que groupe_creneau.actif).
CREATE UNIQUE INDEX idx_groupe_defaut_unique ON groupe (defaut) WHERE defaut;

INSERT INTO groupe (id, nom, defaut) VALUES ('DEFAUT', 'Groupe par défaut', TRUE);

-- V36 renomme ensuite la table, l'index et le seed :
ALTER TABLE groupe RENAME TO edition;
ALTER INDEX idx_groupe_defaut_unique RENAME TO idx_edition_defaut_unique;
UPDATE edition SET nom = '2026' WHERE id = 'DEFAUT' AND nom = 'Groupe par défaut';
```

`defaut` n'est **pas** « l'édition courante » (celle-là est choisie par le
client, cf. §5) : c'est le repli pour tout appelant qui n'en désigne aucune —
export CLI, tâche planifiée, appel d'API direct.

### 4.2 Tables cloisonnées

Reçoivent en `V33` une colonne
`groupe_id VARCHAR(64) NOT NULL REFERENCES groupe(id) ON DELETE CASCADE`
(renommée `edition_id` par `V36`), remplie à `'DEFAUT'` lors de la migration :

`animateur`, `stand`, `emplacement`, `typologie`, `groupe_creneau`,
`contrainte_ad_hoc`, `constraint_toggle`, `verrouillage_planning`,
`parametres_legaux`, `parametres_solveur`, `parametres_decoupage`,
`planning_resolution`.

Les tables filles héritaient du périmètre par leur seule clé étrangère —
`animateur_competence`, `stand_typologie`, `creneau`, `poste_affectation`, … —
mais le passage des clés primaires en composite (§4.3) fait entrer `edition_id`
dans ces clés étrangères, donc dans les tables filles elles-mêmes (`V34`).
`edition_id` n'y est pas un périmètre indépendant : c'est la colonne de tête
d'une FK, contrainte à rester égale à celle du parent.

Chaque table porte en plus une FK directe vers `edition(id) ON DELETE CASCADE` :
supprimer une édition devient une seule instruction qui emporte tout son
référentiel, et les FK métier restées en `NO ACTION` — vérifiées en fin
d'instruction, pas ligne à ligne — ne la bloquent pas.

### 4.3 Clés primaires : passées en composite

`stand.id`, `animateur.id`, `typologie.id`, `emplacement.id`,
`groupe_creneau.id`, `contrainte_ad_hoc.id` et `constraint_toggle.nom` sont des
identifiants métier saisis par l'utilisateur (`tir-a-la-corde`, …). Le cas
d'usage visé — « les mêmes stands en 2025 et en 2026 » — les fait **collisionner
immédiatement** si la clé reste globale.

Deux sorties possibles : préfixer les identifiants à l'import
(`2026__tir-a-la-corde`, laid et visible par l'utilisateur), ou passer les clés
en composite `(edition_id, id)`. **On est passé en composite** : le surcoût est
concentré dans une migration (réécriture d'une vingtaine de contraintes FK)
alors que le préfixage polluerait durablement les identifiants, les URLs et les
YAML de scénario. Les requêtes du repository gagnent de toute façon un prédicat
`edition_id = ?`.

`creneau.id` reste un `BIGINT` identity global (déjà unique) — inutile d'y
toucher ; seul `edition_id` lui est ajouté, pour porter la FK composite vers
`groupe_creneau`.

Les tables singleton (`parametres_legaux`, `parametres_solveur`,
`parametres_decoupage`, `planning_resolution`) perdent leur `CHECK (id = 1)` :
leur clé primaire devient `edition_id`, une ligne par édition. Une ligne absente
est sans conséquence — le repository retombe alors sur les valeurs par défaut du
code.

### 4.4 Migrations

Une migration par lot, pour rester relisible et rejouable :

| Version | Contenu |
| --- | --- |
| `V32` | table `groupe`, ligne `DEFAUT` |
| `V33` | colonnes `groupe_id` + backfill `'DEFAUT'` sur les tables de §4.2 ; les index d'unicité « une seule grille active », « une seule typologie ninja » et « une cible verrouillée une seule fois » deviennent uniques *par groupe* |
| `V34` | bascule des clés primaires métier en `(groupe_id, id)` et réécriture des FK |
| `V35` | singletons de paramètres : PK `groupe_id`, une ligne par groupe |
| `V36` | renomme `groupe` → `edition`, `groupe_id` → `edition_id` partout (colonnes, contraintes, index), et le seed `'Groupe par défaut'` → `'2026'` — sans toucher à `groupe_creneau` |

Aucune perte de donnée : une base existante devient une mono-édition `DEFAUT`,
strictement identique à ce qu'elle était. Aucune sémantique de suppression n'est
changée non plus — chaque `ON DELETE` existant est reconduit à l'identique, les
FK composites nullables utilisant la forme `ON DELETE SET NULL (colonne)` de
PostgreSQL 15+ pour ne pas tenter d'annuler aussi `edition_id`.

## 5. Comment le serveur sait quelle édition est demandée

**Décision : le client la désigne à chaque requête**, via un en-tête
`X-Edition-Id`, avec repli sur l'édition `defaut` s'il est absent ou inconnu.

L'alternative — un drapeau `actif` en base comme pour `groupe_creneau` — serait
plus simple mais globale : deux onglets ouverts partageraient forcément la même
édition, ce qui interdit précisément le cas visé (« aller voir les résultats »
de 2025 pendant qu'on travaille sur 2026). Basculer deviendrait aussi une
écriture en base, donc un événement concurrent entre utilisateurs.

Mise en œuvre :

- `EditionHeaderFilter` (`ContainerRequestFilter`) dépose l'en-tête dans
  `EditionRequestScope` (`@RequestScoped`) ;
- `EditionContext` (`@ApplicationScoped`) le résout : surcharge liée au thread →
  édition du **jeton d'espace animateur** de la requête (posée par les gardes
  `@JetonRequis`/`@SessionEspaceRequise`, elle prime sur l'en-tête que l'espace
  ne croit jamais) → en-tête de la requête **si l'édition existe** → édition
  `defaut`. Jamais
  d'erreur 400 : une édition supprimée dans un onglet resté ouvert ne doit pas
  casser l'écran. Les ids connus et l'id par défaut sont mis en cache, invalidé
  à chaque écriture sur `/api/editions` ;
- le contexte n'est pas lui-même `@RequestScoped` parce qu'il doit servir des
  threads sans requête (worker du solveur) : `EditionContext.executeDans(id, …)`
  lie explicitement une édition au thread courant ;
- `ReferenceDataRepository`, `PlanningPersistenceService` (ainsi que
  `PlanSnapshotService` et `DemandeEchangeService`) ajoutent
  `edition_id = ?` à toutes leurs requêtes. Être le point de passage unique de
  tout le SQL rend le changement mécanique et **vérifiable** : une requête sans
  prédicat d'édition s'y voit. La convention est portée par un helper,
  `prepareScoped`, qui lie l'édition au **premier** paramètre de l'instruction.
  Unique exception assumée : `resoudreJetonAnimateur` (issue #165), qui résout
  un jeton d'espace animateur **globalement** — le jeton arrive sur une URL
  publique sans en-tête d'édition à croire, et est justement unique toutes
  éditions confondues pour désigner la sienne ; les gardes de l'espace lient
  ensuite l'édition résolue à la requête (`EditionRequestScope`), et tout le
  reste s'exécute dedans sans enveloppe explicite ;
- côté Angular, un `HttpInterceptor` (`core/edition.interceptor.ts`) pose
  l'en-tête depuis `core/edition-courante.ts`, dont la valeur est persistée en
  `localStorage`. C'est un module et non un service : l'intercepteur tourne à
  chaque requête, passer par un service le ferait dépendre du `HttpClient`
  qu'il intercepte.

Le verrou du solveur (`SolverJobService`) reste **global** : une résolution à la
fois pour toute l'instance, quelle que soit sa cible. Un verrou par édition ferait
tourner deux solveurs Timefold simultanés sur la même JVM, ce que le
dimensionnement mémoire actuel n'anticipe pas. Le job capte en revanche son
`editionId` **à la soumission**, sur le thread de la requête, et le rebinde sur
le worker : il continue d'écrire dans l'édition pour laquelle il a été lancé
même si le navigateur a basculé entre-temps. La file « résoudre tous les
groupes » (issue #167) respecte ce verrou plutôt que de le contourner : les
groupes s'y enchaînent **en séquence** dans un seul job, jamais en parallèle.

## 6. Ce que ça change ailleurs

- **Import de scénario** (`/api/reference-data/import-scenario`) : écrit dans
  l'édition courante, et elle seule. C'est le chemin nominal pour peupler un
  « Année 2026 » vierge sans toucher à 2025.
- **Dupliquer une édition** — `POST /api/editions/{id}/dupliquer` : `INSERT …
  SELECT` table par table avec le nouvel `edition_id`, résultats de solveur
  (`poste_affectation`, `planning_resolution`) exclus. C'est la fonction qui rend
  le tout utilisable : « 2026 = 2025 moins les affectations ». Les identifiants
  métier sont recopiés à l'identique — c'est précisément ce que les clés
  composites autorisent ; seuls les `creneau.id`, générés par la base, sont
  réattribués, via une table temporaire de correspondance à travers laquelle les
  lignes qui les référencent sont réécrites.
- **Supprimer une édition** : `ON DELETE CASCADE` emporte tout le référentiel ;
  confirmation explicite obligatoire côté IHM, et refus côté serveur de
  supprimer l'édition par défaut, l'édition courante, ou la dernière restante.
- **Réinitialiser la base** (`POST /api/planning/reset`) : ne vide plus que
  l'édition courante. C'est devenu un `DELETE` cloisonné là où c'était un
  `TRUNCATE` global — sans quoi réinitialiser 2026 effacerait 2025.
- **Dump SQL** (`DatabaseDumpService`) : reste global (c'est une sauvegarde de
  l'instance, toutes éditions comprises ; la table `edition` y a été ajoutée en
  tête). Un export cloisonné par édition existe déjà sous une autre forme —
  l'export de scénario YAML — qui, lui, suit l'édition courante.
- **Faisabilité, heures, export PDF/ICS, calendriers, volumétrie** : rien à
  changer dans leur logique, ils lisent tous le référentiel via
  `ReferenceDataService` et suivent donc le contexte.
- **Analyse de score et fraîcheur des données** (`ConstraintAnalysisStore`,
  `ReferenceDataChangeTracker`) : mémoire vive, désormais indexée par édition —
  éditer 2026 ne doit pas faire passer le planning de 2025 pour périmé.
- **Journal de notifications** (frontend, `localStorage`) : une clé par
  édition, pour la même raison.
- **Verrouillages de planning** : cloisonnés eux aussi. Leur identifiant reste
  un UUID global — donc pas de clé composite —, mais l'unicité d'une cible
  verrouillée devient « une fois par édition **et** par grille de créneaux ».
- **Outils MCP** (`mcp/`) : servis hors du filtre JAX-RS, ils n'ont pas
  d'en-tête `X-Edition-Id` et travaillent donc dans l'édition par défaut. C'est
  le comportement voulu pour un appelant qui ne désigne rien ; exposer le choix
  de l'édition à l'assistant reste à faire.

## 7. Les écrans

### 7.1 Le bandeau « Édition actuelle »

`src/main/webui/src/app/shared/edition-actuelle-bar.ts`, posé une fois dans la
coquille applicative (`app.html`, en tête de `mat-sidenav-content`) et donc
présent sur **tous** les écrans :

- bandeau `sticky` sous la barre d'outils, qui reste visible au défilement ;
- libellé « Édition actuelle : *nom* », lu depuis `EditionStore.courant()`,
  c'est-à-dire depuis `GET /api/editions/courant` : c'est l'édition à laquelle
  le serveur a **réellement** résolu la requête, pas celle que le navigateur
  croit avoir choisie ;
- bouton « Changer » ouvrant un menu des autres éditions, masqué s'il n'y en a
  aucune, et lien « Gérer » vers la page Éditions ;
- la bascule écrit l'id en `localStorage` puis **recharge la page**, comme le
  fait déjà le changement de langue : elle remplace les données derrière tous les
  écrans ouverts d'un coup, et un rechargement est la seule garantie qu'aucune
  page ne continue d'afficher les lignes de l'édition précédente. C'est une
  action rare et délibérée.

Il est complémentaire du bandeau `app-groupe-mismatch-banner`, qui reste
silencieux tant que tout est cohérent : l'un rappelle **où** on est, l'autre
prévient quand le planning affiché **n'est plus à jour** pour cet endroit.

### 7.2 La page « Éditions »

`src/main/webui/src/app/pages/editions/` (route `/editions`, entrée
« Éditions » du menu Planning) : liste des éditions, création — vide ou par
duplication d'une existante —, renommage en ligne, désignation de l'édition par
défaut, bascule et suppression. Les actions impossibles y sont désactivées
plutôt que refusées après coup : on ne peut pas supprimer l'édition par défaut,
l'édition consultée dans cet onglet, ni la dernière restante.

## 8. Ordre de mise en œuvre

Les quatre étapes ont été livrées ensemble mais restent séparables, chacune sans
régression :

1. `V32`/`V33` (table `groupe` + colonnes), `EditionContext` et le prédicat
   `groupe_id` dans `ReferenceDataRepository` — à ce stade tout fonctionne
   encore en mono-édition `DEFAUT`, aucun comportement visible ne change.
2. CRUD `/api/editions` + `EditionStore` et intercepteur côté Angular ; le
   bandeau de §7.1 bascule sur ces sources.
3. `V34`/`V35` (clés composites, paramètres par groupe) — c'est le pas qui
   autorise réellement deux éditions aux identifiants identiques.
4. Duplication d'édition, cloisonnement de l'import de scénario, de la
   réinitialisation, de l'analyse et des notifications ; suppression d'édition
   avec ses garde-fous.
5. `V36` : renommage `groupe` → `edition` dans tout le code (Java, SQL,
   Angular, docs) — plus parlant d'un point de vue métier, sans changer aucun
   comportement.
