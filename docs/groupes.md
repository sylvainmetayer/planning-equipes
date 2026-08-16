# Groupes — cloisonner le référentiel et les résultats de solveur

**Statut : implémenté.** Les migrations `V32` à `V35`, le contexte de groupe
côté serveur, l'API `/api/groupes`, l'intercepteur et la page « Groupes » côté
Angular sont livrés. Une base existante devient un mono-groupe `DEFAUT`
strictement identique à ce qu'elle était.

## 1. Le besoin

Avant cette évolution, la base ne contenait **qu'un seul référentiel** : un jeu
de stands, d'animateurs, de typologies, d'emplacements, de paramètres légaux et
un planning persisté. Préparer l'édition 2026 signifiait donc écraser les
données de 2025 — et avec elles le résultat de solveur qu'on aimerait pouvoir
relire, comparer, ou reprendre comme point de départ.

L'objectif : pouvoir tenir côte à côte un groupe « Année 2025 » (archivé,
consultable, avec ses stands et ses animateurs de l'époque) et un groupe
« Année 2026 » (en cours), **sans qu'aucune donnée de l'un ne fuite dans
l'autre**, et basculer de l'un à l'autre en un clic.

## 2. Ce qui existait déjà : `groupe_creneau`

Une notion de groupe existait déjà, mais **cloisonne uniquement les créneaux** :

- table `groupe_creneau (id, nom, actif, groupe_source_id)`, créée en `V10` ;
- `creneau.groupe_creneau_id` référence ce groupe ;
- un index unique partiel (`WHERE actif`) garantit **un seul groupe actif** —
  désormais un seul *par groupe* (`V33`) ;
- le solveur ne construit son problème que sur les créneaux du groupe actif
  (`PlanningService.construireDepuisReferenceData` →
  `ReferenceDataService.listCreneauxGroupeActif`) ;
- `planning_resolution` mémorise pour quel groupe le dernier calcul a été fait,
  ce qui alimente le bandeau d'avertissement `app-groupe-mismatch-banner`.

Le cas d'usage d'origine est « préparer une grille de créneaux de repli et la
basculer au dernier moment ». C'est une notion **plus étroite** que celle
traitée ici : deux grilles alternatives à l'intérieur d'une même édition, pas
deux éditions distinctes.

## 3. Décision de modélisation : deux niveaux, pas un

Deux options se présentaient.

| | A — deux niveaux (`groupe` ⊃ `groupe_creneau`) | B — un seul niveau (on promeut `groupe_creneau`) |
| --- | --- | --- |
| Modèle | Nouvelle table `groupe` (« édition »), `groupe_creneau` devient son enfant | `groupe_creneau` devient l'unique périmètre |
| Grilles alternatives dans une même année | conservées telles quelles | il faut **cloner tout le référentiel** (150 animateurs, 60 stands) pour tester une autre grille |
| Migration | ajout d'une table + d'une colonne partout | renommage sémantique d'une notion existante, plus casse-gueule |
| Coût conceptuel | deux notions à expliquer | une seule |

**Retenu : A.** Le coût de B est le mauvais : dupliquer tout le référentiel pour
tester un autre découpage est exactement ce que `groupe_creneau` évite, et cette
capacité est utilisée (régénération de découpage, `groupe_source_id`). Les deux
notions répondent à deux questions différentes et restent distinctes.

Vocabulaire retenu dans l'UI, pour que « groupe » ne soit pas ambigu :

- **Groupe** (table `groupe`) : le périmètre complet — « Année 2025 » ;
- **Grille de créneaux** (table `groupe_creneau`) : une variante de découpage
  **à l'intérieur** d'un groupe.

Le code Java garde les noms `Groupe` / `GroupeCreneau` ; seuls les libellés
français des écrans changent.

## 4. Schéma

### 4.1 La table `groupe`

```sql
CREATE TABLE groupe (
    id      VARCHAR(64) PRIMARY KEY,
    nom     VARCHAR(255) NOT NULL,
    defaut  BOOLEAN NOT NULL DEFAULT FALSE,
    cree_le TIMESTAMP NOT NULL DEFAULT now()
);

-- Un seul groupe par défaut, garanti côté base (même motif que groupe_creneau.actif).
CREATE UNIQUE INDEX idx_groupe_defaut_unique ON groupe (defaut) WHERE defaut;

INSERT INTO groupe (id, nom, defaut) VALUES ('DEFAUT', 'Groupe par défaut', TRUE);
```

`defaut` n'est **pas** « le groupe courant » (celui-là est choisi par le client,
cf. §5) : c'est le repli pour tout appelant qui n'en désigne aucun — export CLI,
tâche planifiée, appel d'API direct.

### 4.2 Tables cloisonnées

Reçoivent en `V33` une colonne
`groupe_id VARCHAR(64) NOT NULL REFERENCES groupe(id) ON DELETE CASCADE`,
remplie à `'DEFAUT'` lors de la migration :

`animateur`, `stand`, `emplacement`, `typologie`, `groupe_creneau`,
`contrainte_ad_hoc`, `constraint_toggle`, `verrouillage_planning`,
`parametres_legaux`, `parametres_solveur`, `parametres_decoupage`,
`planning_resolution`.

Les tables filles héritaient du périmètre par leur seule clé étrangère —
`animateur_competence`, `stand_typologie`, `creneau`, `poste_affectation`, … —
mais le passage des clés primaires en composite (§4.3) fait entrer `groupe_id`
dans ces clés étrangères, donc dans les tables filles elles-mêmes (`V34`).
`groupe_id` n'y est pas un périmètre indépendant : c'est la colonne de tête
d'une FK, contrainte à rester égale à celle du parent.

Chaque table porte en plus une FK directe vers `groupe(id) ON DELETE CASCADE` :
supprimer un groupe devient une seule instruction qui emporte tout son
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
en composite `(groupe_id, id)`. **On est passé en composite** : le surcoût est
concentré dans une migration (réécriture d'une vingtaine de contraintes FK)
alors que le préfixage polluerait durablement les identifiants, les URLs et les
YAML de scénario. Les requêtes du repository gagnent de toute façon un prédicat
`groupe_id = ?`.

`creneau.id` reste un `BIGINT` identity global (déjà unique) — inutile d'y
toucher ; seul `groupe_id` lui est ajouté, pour porter la FK composite vers
`groupe_creneau`.

Les tables singleton (`parametres_legaux`, `parametres_solveur`,
`parametres_decoupage`, `planning_resolution`) perdent leur `CHECK (id = 1)` :
leur clé primaire devient `groupe_id`, une ligne par groupe. Une ligne absente
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

Aucune perte de donnée : une base existante devient un mono-groupe `DEFAUT`,
strictement identique à ce qu'elle était. Aucune sémantique de suppression n'est
changée non plus — chaque `ON DELETE` existant est reconduit à l'identique, les
FK composites nullables utilisant la forme `ON DELETE SET NULL (colonne)` de
PostgreSQL 15+ pour ne pas tenter d'annuler aussi `groupe_id`.

## 5. Comment le serveur sait quel groupe est demandé

**Décision : le client le désigne à chaque requête**, via un en-tête
`X-Groupe-Id`, avec repli sur le groupe `defaut` s'il est absent ou inconnu.

L'alternative — un drapeau `actif` en base comme pour `groupe_creneau` — serait
plus simple mais globale : deux onglets ouverts partageraient forcément le même
groupe, ce qui interdit précisément le cas visé (« aller voir les résultats » de
2025 pendant qu'on travaille sur 2026). Basculer deviendrait aussi une écriture
en base, donc un événement concurrent entre utilisateurs.

Mise en œuvre :

- `GroupeHeaderFilter` (`ContainerRequestFilter`) dépose l'en-tête dans
  `GroupeRequestScope` (`@RequestScoped`) ;
- `GroupeContext` (`@ApplicationScoped`) le résout : surcharge liée au thread →
  en-tête de la requête **si le groupe existe** → groupe `defaut`. Jamais
  d'erreur 400 : un groupe supprimé dans un onglet resté ouvert ne doit pas
  casser l'écran. Les ids connus et l'id par défaut sont mis en cache, invalidé
  à chaque écriture sur `/api/groupes` ;
- le contexte n'est pas lui-même `@RequestScoped` parce qu'il doit servir des
  threads sans requête (worker du solveur) : `GroupeContext.executeDans(id, …)`
  lie explicitement un groupe au thread courant ;
- `ReferenceDataRepository` et `PlanningPersistenceService` ajoutent
  `groupe_id = ?` à toutes leurs requêtes. Être le point de passage unique de
  tout le SQL rend le changement mécanique et **vérifiable** : une requête sans
  prédicat de groupe s'y voit. La convention est portée par un helper,
  `prepareScoped`, qui lie le groupe au **premier** paramètre de l'instruction ;
- côté Angular, un `HttpInterceptor` (`core/groupe.interceptor.ts`) pose
  l'en-tête depuis `core/groupe-courant.ts`, dont la valeur est persistée en
  `localStorage`. C'est un module et non un service : l'intercepteur tourne à
  chaque requête, passer par un service le ferait dépendre du `HttpClient`
  qu'il intercepte.

Le verrou du solveur (`SolverJobService`) reste **global** : une résolution à la
fois pour toute l'instance, quelle que soit sa cible. Un verrou par groupe ferait
tourner deux solveurs Timefold simultanés sur la même JVM, ce que le
dimensionnement mémoire actuel n'anticipe pas. Le job capte en revanche son
`groupeId` **à la soumission**, sur le thread de la requête, et le rebinde sur le
worker : il continue d'écrire dans le groupe pour lequel il a été lancé même si
le navigateur a basculé entre-temps.

## 6. Ce que ça change ailleurs

- **Import de scénario** (`/api/reference-data/import-scenario`) : écrit dans le
  groupe courant, et lui seul. C'est le chemin nominal pour peupler un
  « Année 2026 » vierge sans toucher à 2025.
- **Dupliquer un groupe** — `POST /api/groupes/{id}/dupliquer` : `INSERT … SELECT`
  table par table avec le nouvel `groupe_id`, résultats de solveur
  (`poste_affectation`, `planning_resolution`) exclus. C'est la fonction qui rend
  le tout utilisable : « 2026 = 2025 moins les affectations ». Les identifiants
  métier sont recopiés à l'identique — c'est précisément ce que les clés
  composites autorisent ; seuls les `creneau.id`, générés par la base, sont
  réattribués, via une table temporaire de correspondance à travers laquelle les
  lignes qui les référencent sont réécrites.
- **Supprimer un groupe** : `ON DELETE CASCADE` emporte tout le référentiel ;
  confirmation explicite obligatoire côté IHM, et refus côté serveur de
  supprimer le groupe par défaut, le groupe courant, ou le dernier restant.
- **Réinitialiser la base** (`POST /api/planning/reset`) : ne vide plus que le
  groupe courant. C'est devenu un `DELETE` cloisonné là où c'était un `TRUNCATE`
  global — sans quoi réinitialiser 2026 effacerait 2025.
- **Dump SQL** (`DatabaseDumpService`) : reste global (c'est une sauvegarde de
  l'instance, tous groupes compris ; la table `groupe` y a été ajoutée en tête).
  Un export cloisonné par groupe existe déjà sous une autre forme — l'export de
  scénario YAML — qui, lui, suit le groupe courant.
- **Faisabilité, heures, export PDF/ICS, calendriers, volumétrie** : rien à
  changer dans leur logique, ils lisent tous le référentiel via
  `ReferenceDataService` et suivent donc le contexte.
- **Analyse de score et fraîcheur des données** (`ConstraintAnalysisStore`,
  `ReferenceDataChangeTracker`) : mémoire vive, désormais indexée par groupe —
  éditer 2026 ne doit pas faire passer le planning de 2025 pour périmé.
- **Journal de notifications** (frontend, `localStorage`) : une clé par groupe,
  pour la même raison.
- **Verrouillages de planning** : cloisonnés eux aussi. Leur identifiant reste
  un UUID global — donc pas de clé composite —, mais l'unicité d'une cible
  verrouillée devient « une fois par groupe **et** par grille de créneaux ».
- **Outils MCP** (`mcp/`) : servis hors du filtre JAX-RS, ils n'ont pas
  d'en-tête `X-Groupe-Id` et travaillent donc dans le groupe par défaut. C'est
  le comportement voulu pour un appelant qui ne désigne rien ; exposer le choix
  du groupe à l'assistant reste à faire.

## 7. Les écrans

### 7.1 Le bandeau « Groupe actuel »

`src/main/webui/src/app/shared/groupe-actuel-bar.ts`, posé une fois dans la
coquille applicative (`app.html`, en tête de `mat-sidenav-content`) et donc
présent sur **tous** les écrans :

- bandeau `sticky` sous la barre d'outils, qui reste visible au défilement ;
- libellé « Groupe actuel : *nom* », lu depuis `GroupeStore.courant()`, c'est-à-dire
  depuis `GET /api/groupes/courant` : c'est le groupe auquel le serveur a
  **réellement** résolu la requête, pas celui que le navigateur croit avoir
  choisi ;
- bouton « Changer » ouvrant un menu des autres groupes, masqué s'il n'y en a
  aucun, et lien « Gérer » vers la page Groupes ;
- la bascule écrit l'id en `localStorage` puis **recharge la page**, comme le
  fait déjà le changement de langue : elle remplace les données derrière tous les
  écrans ouverts d'un coup, et un rechargement est la seule garantie qu'aucune
  page ne continue d'afficher les lignes du groupe précédent. C'est une action
  rare et délibérée.

Il est complémentaire du bandeau `app-groupe-mismatch-banner`, qui reste
silencieux tant que tout est cohérent : l'un rappelle **où** on est, l'autre
prévient quand le planning affiché **n'est plus à jour** pour cet endroit.

### 7.2 La page « Groupes »

`src/main/webui/src/app/pages/groupes/` (route `/groupes`, entrée « Groupes » du
menu Planning) : liste des éditions, création — vide ou par duplication d'une
existante —, renommage en ligne, désignation du groupe par défaut, bascule et
suppression. Les actions impossibles y sont désactivées plutôt que refusées
après coup : on ne peut pas supprimer le groupe par défaut, le groupe consulté
dans cet onglet, ni le dernier restant.

## 8. Ordre de mise en œuvre

Les quatre étapes ont été livrées ensemble mais restent séparables, chacune sans
régression :

1. `V32`/`V33` (table `groupe` + colonnes), `GroupeContext` et le prédicat
   `groupe_id` dans `ReferenceDataRepository` — à ce stade tout fonctionne
   encore en mono-groupe `DEFAUT`, aucun comportement visible ne change.
2. CRUD `/api/groupes` + `GroupeStore` et intercepteur côté Angular ; le bandeau
   de §7.1 bascule sur ces sources.
3. `V34`/`V35` (clés composites, paramètres par groupe) — c'est le pas qui
   autorise réellement deux groupes aux identifiants identiques.
4. Duplication de groupe, cloisonnement de l'import de scénario, de la
   réinitialisation, de l'analyse et des notifications ; suppression de groupe
   avec ses garde-fous.
