# Écrire les référentiels par l'API

Les référentiels (stands, animateurs, créneaux, typologies, emplacements, ajustements) suivent tous le même schéma CRUD, cloisonné par édition. Vous saurez comment les identifiants sont attribués, ce que renvoie une écriture refusée pour modification concurrente ou calcul en cours, comment contrôler et dériver la grille de créneaux, et comment importer un fichier. Les [horaires des stands](api-horaires-des-stands.md) ont leur page. Les incohérences que l'API signale sans refuser sont dans [les avertissements de saisie](api-avertissements-de-saisie.md).

## Identifiants et codes

Un identifiant n'est jamais choisi par l'appelant ([ADR 0050](repo:docs/decisions/0050-identifiants-generes-par-edition.md)). Le `POST` de création l'attribue et le rend : `A1`, `A2`… pour un animateur, `S…` pour un stand, `T…` pour une typologie, `L…` pour un emplacement, `C…` pour un ajustement, numérotés dans chaque édition ; `E1`, `E2`… pour une édition. Un `id` présent dans le corps d'une création est ignoré, et un numéro libéré par une suppression ne sert plus. Un ajustement fait exception : on le modifie en renvoyant son `POST` avec son identifiant, qui doit exister (`400` sinon).

Les stands, typologies et emplacements portent un `code` facultatif (`STRATEGIE`, `JEU-LIBRE`), unique dans l'édition (`409` s'il est pris) et jamais de la forme d'un identifiant (`400`) : c'est la clé que les fichiers d'import citent. Une typologie citée par un stand ou un animateur (`typologiesProposees`, `competences`, `souhaits`) s'écrit par identifiant ou par code, et c'est l'identifiant qui est enregistré. Un nom d'édition de la forme `E` suivi d'un nombre est refusé (`400`) : l'argument `edition` des outils MCP le prendrait pour un identifiant.

Les typologies forment un référentiel référencé par clé étrangère : un `id` inconnu répond `400`, supprimer une typologie encore utilisée aussi. `polyvalent` désigne la typologie des profils polyvalents, au plus une à la fois : la poser sur une autre retire le drapeau de la précédente.

## Ce qu'une écriture exige

- Un stand porte au moins une typologie : `POST /api/stands` et `PUT /api/stands/{id}` répondent `400` sur `typologiesProposees` vide, en nommant le stand. Le contrôle ne joue qu'à l'écriture : une ancienne ligne sans typologie se lit et se résout encore, et se voit refuser à sa prochaine sauvegarde.
- Un créneau requiert une date, un début et une fin (`400` sinon). Une fin antérieure ou égale au début est acceptée : c'est ainsi que s'écrit un créneau qui franchit minuit (20:00 à 00:00 dure quatre heures), et lui seul lit une fenêtre de stand datée du lendemain.
- Les contraintes ad hoc et les verrouillages sont des états : on les supprime et on les recrée. Une paire d'animateurs ne peut pas être à la fois en incompatibilité et en affinité (`400`) ; une cible déjà verrouillée renvoie `200` sans doublon. Voir [les exceptions ad hoc](exceptions-ad-hoc.md) et [les verrous](plan-verrous-et-publication.md).

## Modification concurrente

Sans garde, deux onglets qui enregistrent la même fiche l'un après l'autre laissent le second écraser le premier en silence. Chaque ligne des six référentiels porte donc `modifieLe`, la date de sa dernière écriture, que le `GET` renvoie et que le formulaire renvoie telle quelle ([ADR 0023](repo:docs/decisions/0023-modification-concurrente-par-horodatage.md)).

| `modifieLe` envoyé | Effet |
| --- | --- |
| égal à celui de la ligne | écrit |
| différent | `409` sans rien écrire, corps `{ message, code: "MODIFICATION_CONCURRENTE", modifieLe }` avec la valeur courante |
| absent ou `null` | écrit sans contrôle (un import, un script, un écrasement délibéré) |

Le `code` distingue ce `409` de celui du solveur occupé. L'IHM propose Recharger (rien n'est écrit) ou Écraser quand même (le même payload repart sans `modifieLe`) ; fermer le dialogue sans choisir laisse la saisie à l'écran.

Les autres écritures qui portent la précondition :

- L'édition en masse envoie un `PUT` par ligne : une ligne modifiée ailleurs est refusée seule, et le compte rendu demande de refaire la sélection, le dialogue tenant encore l'horodatage périmé.
- `PUT /api/ouvertures-stands/grille` (voir [les horaires](api-horaires-des-stands.md)) en porte un par stand (`stands[].modifieLe`).
- `PUT /api/animateurs/competences/grille` en porte un par fiche (`animateurs[].modifieLe`) mais répond `200` avec un compte rendu par ligne : `WRITTEN`, `STALE` (le `409` de la fiche, avec son `modifieLe` courant) ou `REJECTED` avec son motif. Une ligne périmée n'annule pas les autres.
- Un webhook (`PUT /api/webhooks/{id}`) la lit dans `modifiedAt`, un préréglage de consigne (`PUT /api/consignes/prereglages/{id}`) dans `modifieLe`. Une consigne, posée sur plusieurs dates, porte une table `date → horodatage` : une date sans entrée n'est pas contrôlée, une entrée pour une date que la demande ne pose pas est refusée (`400`), et le refus d'une date annule toute la pose.
- Le `PUT` de chacune des quatre sections des paramètres de l'édition (`/api/parametres/legal`, `organisation`, `notifications`, `calcul`) lit `modifieLe` dans le corps, celui que le `GET` de la section a renvoyé : une section écrite depuis répond le même `409`. Les anciennes racines (`/api/parametres-legaux`, `-qualite`, `-solveur`, `-notifications`) renvoient aussi `modifieLe` mais l'ignorent à l'écriture : le dernier qui écrit gagne, comme en 3.x.
- Les outils MCP `modifier_*` acceptent l'horodatage en argument facultatif ; omis, ils relisent la ligne juste avant d'écrire.

Les imports ne la portent pas : ils remplacent ou fusionnent un référentiel entier. La vérification fait partie de l'écriture : un seul ordre SQL dont le `ON CONFLICT DO UPDATE` porte la précondition et ne renvoie aucune ligne quand elle échoue, car lire puis écrire laisserait passer deux enregistrements séparés d'une milliseconde. La création se vérifie de même : un identifiant tiré du compteur déjà pris (base retouchée à la main) répond `409` au lieu de remplacer la ligne. Le mécanisme prévient la seconde session sans verrouiller personne. La persistance d'un calcul ne touche pas `modifieLe`, sinon toute fiche ouverte entrerait en conflit après chaque résolution.

## Supprimer, et ce que la suppression emporte

`GET /api/stands/usages`, `GET /api/animateurs/usages` et `GET /api/creneaux/usages` chiffrent ce qui référence une sélection, pour que la confirmation le dise avant de supprimer : postes pourvus du plan enregistré, ajustements manuels, verrouillages, consignes (toujours zéro pour un animateur). Répéter `id` compte plusieurs entités et renvoie un total agrégé, ce qui laisse une suppression en lot à un seul appel ; le client redécoupe sur la longueur encodée de la requête, les identifiants étant du texte libre.

- Le décompte informe sans bloquer : aucun seuil.
- Un `id` inconnu compte pour zéro (pas de `404` : le dialogue porte sur des lignes déjà affichées et la suppression elle-même dira qu'une ligne a disparu). Une requête sans `id`, ou avec un `id` de créneau non numérique, répond `400` ; `/api/creneaux/usages` lit ses `id` en texte pour que la conversion échoue dans la ressource et non avant, en `404`.
- Un siège vide ne compte pas.
- Les quatre compteurs ne forment pas un inventaire : zéro partout se dit en les nommant, jamais « rien ne le référence », car d'autres tables suivent en cascade sans être comptées (échanges, horaires et ouvertures d'un stand, compétences et souhaits).

Supprimer un créneau ou un stand emporte les postes qui s'y trouvaient et eux seuls (`stand_id` et `creneau_id` sont `NOT NULL`). Supprimer un animateur vide ses postes sans les supprimer : `animateur_id` est nullable, et un poste sans animateur est une place non pourvue, visible dans `postesNonPourvus` et les écrans de couverture au lieu de faire paraître le plan mieux couvert qu'il ne l'est. Ces trous apparaissent dans un plan que personne n'a redemandé de résoudre : ils sont réels. En lot, chaque suppression est sa propre transaction.

## Écrire pendant un calcul

Tant qu'une résolution tient le solveur de l'édition, écrire est refusé (`409`, le corps portant le job et un `message` lisible tel quel) pour : supprimer et modifier un stand, un animateur ou un créneau, réinitialiser l'édition (`POST /api/planning/reset`), importer un scénario, réaffecter un poste (assistant de réparation, mode jour J, `affecter_poste`) et accepter un échange.

L'atterrissage du calcul supprime et réinsère tous les sièges depuis le plan de départ, réécrit date et heures des créneaux, et réenregistre stands et animateurs depuis les objets capturés au démarrage. Un siège réaffecté pendant le calcul reviendrait à son ancien occupant, un échange accepté disparaîtrait en laissant la demande `ACCEPTEE`, un animateur supprimé ressusciterait, donnée personnelle comprise. La réinitialisation et l'import laisseraient en plus emplacements, horaires et contraintes ad hoc effacés, et les anciens créneaux réapparaîtraient à côté des nouveaux. Les créations passent : une entité absente au démarrage n'est nommée par aucun poste du résultat.

- Le refus est cantonné à l'édition du job, comparée à l'édition courante : un calcul sur une variante de repli ne bloque rien ailleurs, et un calcul lancé sur A bloque A même si l'onglet est passé sur B. Une résolution seulement en file ne bloque rien.
- Il passe avant tout le reste de la méthode : un `404`, un `400` ou un `409 REFERENTIEL_FIGE` s'effacent derrière lui.
- Il se pose sur la méthode de service par `@RefusedWhileSolving` (un intercepteur CDI), jamais dans la ressource : la route REST et l'outil MCP rencontrent le même garde. `RefusedWhileSolvingStructuralTest` fait échouer le build sur une méthode d'écriture ni annotée ni classée ouverte avec son motif, et sur une méthode annotée appelée par `this`, qui contournerait l'intercepteur. Là où aucun intercepteur ne peut tourner (une classe qui n'est pas un bean), un appel explicite, argumenté dans ce test, tient sa place.

Les autres écritures passent (typologies, emplacements, contraintes ad hoc, paramètres légaux, verrouillages, poids des contraintes, créations) : l'atterrissage ne les défait pas, mais le calcul en cours ne les reflétera pas. L'IHM verrouille ces saisies le temps du calcul (`editingLocked`). Un assistant MCP n'a pas d'écran : la réponse de l'outil porte `RESOLUTION_EN_COURS` dans `avertissements` ([ADR 0020](repo:docs/decisions/0020-avertir-dans-la-reponse-d-ecriture.md)), avec le même périmètre que le refus, et une écriture refusée garde son `409`. Voir [les outils MCP](mcp-outils.md) et [le calcul](api-calcul.md).

## La grille de créneaux

Un créneau se crée seul (`POST /api/creneaux`) ou en lot par **le générateur de créneaux** ([ADR 0126](repo:docs/decisions/0126-un-generateur-de-creneaux-unique.md)), qui produit des dizaines de créneaux d'un coup depuis l'une de trois sources : les gabarits (les journées types de la 3.x) appliqués à leurs dates, les horaires des stands, ou un motif récurrent. Trois gestes valent pour toutes : `POST /api/generateur-creneaux/apercu` n'écrit rien et rend le verdict sur la grille qui résulterait, `POST /api/generateur-creneaux/application` écrit, `POST /api/generateur-creneaux/retrait` retire des dates du calendrier des gabarits (leurs créneaux restent).

```json
POST /api/generateur-creneaux/apercu
{ "source": "RECURRENCE",
  "recurrence": { "jours": "JOURS_SEMAINE", "dateDebut": "2026-07-06", "dateFin": "2026-07-19",
    "joursSemaine": ["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY"],
    "exclusions": ["2026-07-14"],
    "fenetres": [ { "heureDebut": "09:00", "heureFin": "12:00" },
                  { "heureDebut": "14:00", "heureFin": "18:00" } ] } }
→ { "source": "RECURRENCE", "nombreGeneres": 18, "creneaux": [ … ], "controle": { … } }
```

`jours` reprend le sélecteur des horaires de stand (`TOUS`, `JOURS_SEMAINE`, `PLAGE`, `DATES`) ; une récurrence ajoute sans jamais remplacer. Une génération qui répète un créneau existant ressort en `DOUBLON` (`ERREUR`) ; `400` sur une demande mal formée. Un créneau est une vacation : un chevauchement le même jour est la forme normale de deux relèves décalées, jamais une anomalie ([ADR 0037](repo:docs/decisions/0037-une-grille-est-toujours-des-vacations.md)). Les routes de la 3.x (`/api/creneaux/recurrence`, `/api/creneaux/derivation`, `/api/journees-types`) restent des alias déclarés obsolètes jusqu'en 5.0, avec leurs anciennes formes.

`GET /api/creneaux/controle` rend les anomalies de la grille (`severite` `ERREUR` ou `AVERTISSEMENT`, `type`, `date`, `message`), celles d'ouverture des stands et le rapport de faisabilité (`null` sans stand ou sans créneau). Deux plafonds de durée s'y lisent : au-delà de `parametresLegaux.dureeVacationMaxMinutes` (6 h par défaut, seuil de l'art. L3121-16) un avertissement `VACATION_TROP_LONGUE`, au-delà de ce que laisse le repos quotidien une erreur `REPOS_QUOTIDIEN_IMPOSSIBLE`. `GET /api/creneaux/diagnostic` décrit la grille en place : nombre de vacations, dates, relais repas, `joursFeries`.

**Jours fériés.** Le contrôle ajoute `VACATION_JOUR_FERIE` (une ligne par date) quand des vacations tombent un jour férié et que l'édition compte au moins un mineur disponible ce jour-là, car seuls les majeurs y siègent (art. L3164-6). Sans mineur disponible, il se tait : un festival ouvre légitimement le 14 juillet. Rien n'est bloqué, la contrainte dure existe déjà ; le message compte les mineurs sans les nommer. `GET /api/jours-feries?debut=AAAA-MM-JJ&fin=AAAA-MM-JJ` rend les fériés d'une plage (`[{ date, label }]`) pour les écrans de saisie. C'est un fait calendaire, aucune édition n'y est lue mais la connexion d'administration reste exigée ; deux ans au plus, `400` sur une plage inversée, trop longue ou une date illisible. Le périmètre est celui de `JoursFeries` (les onze jours de l'art. L3133-1, métropole hors Alsace-Moselle), et le client ne calcule jamais Pâques : le libellé voyage avec la date.

**La source `STANDS` : dériver la grille des horaires de stands.** Quand les horaires existent déjà, la grille en découle : chaque heure où un stand ouvre ou ferme est une coupure, et chaque tranche entre deux coupures où un stand au moins est ouvert devient un créneau. La demande porte `derivation: { dateDebut, dateFin, heureFermeture, dureeMinimaleMinutes, remplacer }` :

- `heureFermeture` donne une fin aux fenêtres « jusqu'à la fermeture » (`00:00` pour minuit : le dernier créneau franchit minuit).
- Une tranche plus courte que `dureeMinimaleMinutes` (15 par défaut) rejoint la suivante, ou la précédente à défaut ; un trou plus court est refermé plutôt que de couper le créneau en deux.
- `remplacer` juge et, à l'écriture, remplace toute la grille, le plan résolu partant avec elle ; sinon les créneaux s'ajoutent.

Seuls les jours qu'un stand déclare « ouvert sur ces fenêtres » participent : un stand muet sur un jour est ouvert quand les autres le sont. La réponse porte les créneaux, les `coupures`, les `joursSansFenetre` et le verdict `controle` ; `400` sans aucune fenêtre.

**La source `GABARITS`.** Les gabarits se gèrent sous `/api/generateur-creneaux/gabarits` et leur calendrier sous `PUT /api/generateur-creneaux/calendrier` ; `GET /api/generateur-creneaux` rend les deux et les dates en écart. L'application matérialise le calendrier par différence (`application` : créneaux conservés, mis à jour, créés, supprimés avec leurs sièges) ; une date sans gabarit n'est jamais touchée. `POST /api/generateur-creneaux/reconnaissance` fait le chemin inverse et remplace les gabarits par ceux que la grille implique. Un créneau créé sur un jour que l'édition ne comptait pas déplie le modèle des stands, comme une saisie à la main ([ADR 0104](repo:docs/decisions/0104-les-horaires-d-un-stand-sont-une-liste-de-plages-datees.md)).

## Imports par fichier

Les imports sont partiels et prévisualisés : deux `POST` au même corps, `…/analyse` qui rend un rapport sans ouvrir de transaction, puis la route d'écriture qui relit le même fichier, rejoue toutes les vérifications et écrit les lignes acceptées en une transaction. L'écriture reçoit le fichier et jamais le rapport affiché, de sorte qu'un rejeu ou un corps fabriqué repasse par les mêmes contrôles ; le fichier n'est jamais écrit sur disque ([ADR 0021](repo:docs/decisions/0021-import-tabulaire-partiel-et-previsualise.md), [ADR 0022](repo:docs/decisions/0022-import-de-la-grille-des-stands.md)).

| Import | Routes | Corps |
| --- | --- | --- |
| Animateurs | `POST /api/animateurs/import-csv/analyse`, `POST /api/animateurs/import-csv`, `GET /api/animateurs/import-csv/exemple` | `{ fileName, content, mapping, replaceAnimateurs, replaceJoursIndisponibles }` |
| Grille des stands | `POST /api/stands/import-grille/analyse`, `POST /api/stands/import-grille`, `GET /api/stands/import-grille/exemple` | `{ fileName, content }` ; l'exemple est la grille actuelle de l'édition, réimportable telle quelle |

Pour les animateurs, `mapping` associe un champ à un index de colonne (`null` : champ absent, jamais écrasé) ; un `mapping` absent demande la correspondance proposée d'après les en-têtes, un mapping fourni mais vide est pris tel quel. Le rapport est le même pour l'aperçu et l'écriture (`applied` les distingue) : `rows[]` porte par ligne son numéro physique, son verdict (`CREATED`, `UPDATED`, `REJECTED`), ses motifs de refus et ses avertissements.

Refus portant sur le fichier entier, avant toute écriture : `400` pour un fichier vide, `.xlsx`, `.xls`, `.ods`, binaire, de plus de 1 000 000 caractères ou 5 000 lignes, pour un fichier non UTF-8 (le message cite un extrait), pour un mapping ne désignant ni identifiant, ni prénom, ni nom, et pour un remplacement complet demandé alors qu'une ligne est rejetée ; `409` sans créneau dans l'édition ou pendant une résolution. L'import de la grille ne crée ni ne supprime de stand et répond `400` aussi quand aucune colonne ne correspond à un créneau. Les formats de fichier (colonnes, dates et heures, années sur deux chiffres, rattachement d'une colonne à un créneau) sont décrits dans [les imports CSV](imports-csv.md).
