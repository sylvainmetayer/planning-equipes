# Groupes — cloisonner le référentiel et les résultats de solveur

**Statut : proposition de conception.** Seul le bandeau « Groupe actuel » décrit
en [§7](#7-le-bandeau-groupe-actuel-implémenté) est implémenté à ce jour ; tout
le reste décrit la cible et le chemin pour y aller.

## 1. Le besoin

Aujourd'hui la base ne contient **qu'un seul référentiel** : un jeu de stands,
d'animateurs, de typologies, d'emplacements, de paramètres légaux et un planning
persisté. Préparer l'édition 2026 signifie donc écraser les données de 2025 —
et avec elles le résultat de solveur qu'on aimerait pouvoir relire, comparer, ou
reprendre comme point de départ.

L'objectif : pouvoir tenir côte à côte un groupe « Année 2025 » (archivé,
consultable, avec ses stands et ses animateurs de l'époque) et un groupe
« Année 2026 » (en cours), **sans qu'aucune donnée de l'un ne fuite dans
l'autre**, et basculer de l'un à l'autre en un clic.

## 2. Ce qui existe déjà : `groupe_creneau`

La notion de groupe existe déjà, mais **cloisonne uniquement les créneaux** :

- table `groupe_creneau (id, nom, actif, groupe_source_id)`, créée en `V10` ;
- `creneau.groupe_creneau_id` référence ce groupe ;
- un index unique partiel (`WHERE actif`) garantit **un seul groupe actif** ;
- le solveur ne construit son problème que sur les créneaux du groupe actif
  (`PlanningService.construireDepuisReferenceData` →
  `ReferenceDataService.listCreneauxGroupeActif`) ;
- `planning_resolution` mémorise pour quel groupe le dernier calcul a été fait,
  ce qui alimente le bandeau d'avertissement `app-groupe-mismatch-banner`.

Le cas d'usage d'origine est « préparer une grille de créneaux de repli et la
basculer au dernier moment ». C'est une notion **plus étroite** que celle
demandée ici : deux grilles alternatives à l'intérieur d'une même édition, pas
deux éditions distinctes.

## 3. Décision de modélisation : deux niveaux, pas un

Deux options se présentent.

| | A — deux niveaux (`groupe` ⊃ `groupe_creneau`) | B — un seul niveau (on promeut `groupe_creneau`) |
| --- | --- | --- |
| Modèle | Nouvelle table `groupe` (« édition »), `groupe_creneau` devient son enfant | `groupe_creneau` devient l'unique périmètre |
| Grilles alternatives dans une même année | conservées telles quelles | il faut **cloner tout le référentiel** (150 animateurs, 60 stands) pour tester une autre grille |
| Migration | ajout d'une table + d'une colonne partout | renommage sémantique d'une notion existante, plus casse-gueule |
| Coût conceptuel | deux notions à expliquer | une seule |

**Retenu : A.** Le coût de B est le mauvais : dupliquer tout le référentiel pour
tester un autre découpage est exactement ce que `groupe_creneau` évite
aujourd'hui, et cette capacité est utilisée (régénération de découpage,
`groupe_source_id`). Les deux notions répondent à deux questions différentes et
doivent rester distinctes.

Vocabulaire retenu dans l'UI, pour que « groupe » ne soit pas ambigu :

- **Groupe** (nouveau, table `groupe`) : le périmètre complet — « Année 2025 » ;
- **Grille de créneaux** (existant, table `groupe_creneau`) : une variante de
  découpage **à l'intérieur** d'un groupe.

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

### 4.2 Tables à cloisonner

Reçoivent une colonne `groupe_id VARCHAR(64) NOT NULL REFERENCES groupe(id) ON DELETE CASCADE`,
remplie à `'DEFAUT'` lors de la migration :

`animateur`, `stand`, `emplacement`, `typologie`, `groupe_creneau`,
`contrainte_ad_hoc`, `constraint_toggle`, `parametres_legaux`,
`parametres_solveur`, `parametres_decoupage`, `planning_resolution`.

**N'en reçoivent pas** — elles héritent du périmètre par leur clé étrangère :
`animateur_competence`, `animateur_jour_indispo`, `animateur_souhait`,
`stand_typologie`, `stand_indisponibilite`, `stand_ouverture`,
`creneau` (via `groupe_creneau`), `creneau_stand_ouvert`, `contrainte_animateur`,
`poste_affectation` (via `stand` **et** `creneau`).

### 4.3 Clés primaires : passer en composite

`stand.id`, `animateur.id`, `typologie.id`, `emplacement.id` sont des
identifiants métier saisis par l'utilisateur (`tir-a-la-corde`, …). Le cas
d'usage visé — « les mêmes stands en 2025 et en 2026 » — les fait **collisionner
immédiatement** si la clé reste globale.

Deux sorties possibles : préfixer les identifiants à l'import
(`2026__tir-a-la-corde`, laid et visible par l'utilisateur), ou passer les clés
en composite `(groupe_id, id)`. **On passe en composite** : le surcoût est
concentré dans une migration (réécriture d'une dizaine de contraintes FK) alors
que le préfixage pollue durablement les identifiants, les URLs et les YAML de
scénario. Les requêtes du repository doivent de toute façon gagner un prédicat
`groupe_id = ?`.

`creneau.id` reste un `BIGINT` identity global (déjà unique, cloisonné via
`groupe_creneau`) — inutile d'y toucher.

Les tables singleton (`parametres_legaux`, `parametres_solveur`,
`parametres_decoupage`, `planning_resolution`) perdent leur
`CHECK (id = 1)` : leur clé primaire devient `groupe_id`, une ligne par groupe.

### 4.4 Migrations

Une migration par lot, pour rester relisible et rejouable :

| Version | Contenu |
| --- | --- |
| `V30` | table `groupe`, ligne `DEFAUT` |
| `V31` | colonnes `groupe_id` + backfill `'DEFAUT'` sur les tables de §4.2 |
| `V32` | bascule des clés primaires métier en `(groupe_id, id)` et réécriture des FK |
| `V33` | singletons de paramètres : PK `groupe_id`, une ligne par groupe |

Aucune perte de donnée : une base existante devient un mono-groupe `DEFAUT`,
strictement identique à ce qu'elle est aujourd'hui.

## 5. Comment le serveur sait quel groupe est demandé

**Décision : le client le désigne à chaque requête**, via un en-tête
`X-Groupe-Id`, avec repli sur le groupe `defaut` s'il est absent ou inconnu.

L'alternative — un drapeau `actif` en base comme pour `groupe_creneau` — est
plus simple mais globale : deux onglets ouverts partagent forcément le même
groupe, ce qui interdit précisément ce que la demande décrit (« on irait voir
les résultats » de 2025 pendant qu'on travaille sur 2026). Basculer devient
aussi une écriture en base, donc un événement concurrent entre utilisateurs.

Mise en œuvre :

- `GroupeContext` `@RequestScoped`, alimenté par un `ContainerRequestFilter`
  qui lit `X-Groupe-Id`, vérifie l'existence du groupe et retombe sur `defaut`
  sinon (jamais d'erreur 400 : un groupe supprimé dans un onglet resté ouvert
  ne doit pas casser l'écran) ;
- `ReferenceDataRepository` injecte ce contexte et ajoute `groupe_id = ?` à
  toutes ses requêtes — la classe est déjà le point de passage unique de tout
  le SQL, ce qui rend le changement mécanique et vérifiable ;
- côté Angular, un `HttpInterceptor` pose l'en-tête depuis un `GroupeStore`
  dont la valeur est persistée en `localStorage`.

Le verrou du solveur (`SolverJobService`) reste **global** : une résolution à la
fois pour toute l'instance, quelle que soit sa cible. Un verrou par groupe
ferait tourner deux solveurs Timefold simultanés sur la même JVM, ce que le
dimensionnement mémoire actuel n'anticipe pas. Le job enregistre en revanche son
`groupe_id`, et `planning_resolution` étant cloisonnée, chaque groupe conserve
son propre résultat et sa propre fraîcheur.

## 6. Ce que ça change ailleurs

- **Import de scénario** (`/api/reference-data/import-scenario`) : écrit dans le
  groupe courant, et lui seul. C'est le chemin nominal pour peupler un
  « Année 2026 » vierge sans toucher à 2025.
- **Dupliquer un groupe** — `POST /api/groupes/{id}/dupliquer` : `INSERT … SELECT`
  table par table avec le nouvel `groupe_id`, résultats de solveur
  (`poste_affectation`, `planning_resolution`) exclus par défaut. C'est la
  fonction qui rend le tout utilisable : « 2026 = 2025 moins les affectations ».
- **Supprimer un groupe** : `ON DELETE CASCADE` emporte tout le référentiel ;
  confirmation explicite obligatoire, et refus de supprimer le groupe courant
  ou le dernier restant.
- **Dump SQL** (`DatabaseDumpService`) : reste global (c'est une sauvegarde de
  l'instance). Un export cloisonné par groupe existe déjà sous une autre forme —
  l'export de scénario YAML — qui, lui, suit le groupe courant.
- **Faisabilité, heures, export PDF/ICS, calendriers** : rien à changer dans leur
  logique, ils lisent tous le référentiel via `ReferenceDataService` et suivent
  donc le contexte.
- **Volumétrie / notifications / problèmes** : à cloisonner aussi, sans quoi les
  compteurs d'un groupe s'affichent en travaillant sur un autre.

## 7. Le bandeau « Groupe actuel » (implémenté)

`src/main/webui/src/app/shared/groupe-actuel-bar.ts`, posé une fois dans la
coquille applicative (`app.html`, en tête de `mat-sidenav-content`) et donc
présent sur **tous** les écrans :

- bandeau `sticky` sous la barre d'outils, qui reste visible au défilement ;
- libellé « Groupe actuel : *nom* » ;
- bouton « Changer » ouvrant un menu des autres groupes, masqué s'il n'y en a
  aucun ;
- la bascule appelle l'API puis **recharge la page**, comme le fait déjà le
  changement de langue : elle remplace les données derrière tous les écrans
  ouverts d'un coup, et un rechargement est la seule garantie qu'aucune page ne
  continue d'afficher les lignes du groupe précédent. C'est une action rare et
  délibérée.

Il s'appuie aujourd'hui sur `PlanningResolutionStore`, déjà chargé au démarrage
pour le bandeau de désynchronisation, et sur les endpoints existants
(`GET /api/groupes-creneaux`, `PUT /api/groupes-creneaux/{id}/actif`) : il
affiche donc la **grille de créneaux active**. Quand la table `groupe` de §4
existera, seules les deux sources changent (`GroupeStore` et
`PUT /api/groupes/{id}`) — la présentation, elle, est déjà en place.

Il est complémentaire du bandeau `app-groupe-mismatch-banner`, qui reste
silencieux tant que tout est cohérent : l'un rappelle **où** on est, l'autre
prévient quand le planning affiché **n'est plus à jour** pour cet endroit.

## 8. Ordre de mise en œuvre suggéré

1. `V30`/`V31` (table `groupe` + colonnes), `GroupeContext` et le prédicat
   `groupe_id` dans `ReferenceDataRepository` — à ce stade tout fonctionne
   encore en mono-groupe `DEFAUT`, aucun comportement visible ne change.
2. CRUD `/api/groupes` + `GroupeStore` et intercepteur côté Angular ; le bandeau
   de §7 bascule sur ces sources.
3. `V32`/`V33` (clés composites, paramètres par groupe) — c'est le pas qui
   autorise réellement deux groupes aux identifiants identiques.
4. Duplication de groupe, cloisonnement de l'import de scénario, de la
   volumétrie et des notifications.
5. Suppression de groupe, avec ses garde-fous.

Les étapes 1 et 2 sont livrables séparément et sans régression ; l'étape 3 est
celle qui demande le plus de vigilance sur les tests d'intégration Flyway.
