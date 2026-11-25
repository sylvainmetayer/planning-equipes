# Les imports CSV

Vous savez ce que chaque import CSV écrit, comment une ligne reconnaît une fiche existante, quels contrôles tournent avant l'écriture et pourquoi certains choix (colonnes absentes, dates sur deux chiffres, remplacement complet refusé) protègent les données d'une édition.

Le scénario YAML décrit un événement entier et remplace le référentiel de l'édition ([Le fichier de scénario YAML](scenario-yaml.md)). Les imports CSV font l'inverse : ils ne touchent qu'un référentiel, ligne par ligne, et n'effacent rien par défaut. C'est ce qu'on utilise quand un organisateur arrive avec son tableur de bénévoles. Tout est lu et écrit dans l'édition désignée par `X-Edition-Id`.

L'onglet Importer de l'écran Fichiers (`/fichiers`) réunit sept imports CSV, une carte chacun, la carte ouverte étant portée par `?cible=`. Cinq sont des référentiels courts (typologies, emplacements, stands, créneaux, journées types), deux sont des imports plus riches (animateurs, grille des stands). Chaque écran de référentiel ouvre la même carte dans un dialogue par son bouton « Importer ». Les routes sont `/api/<référentiel>/import-csv/analyse` (aperçu, aucune écriture), `/api/<référentiel>/import-csv` (écriture) et `/api/<référentiel>/import-csv/exemple` ; la liste exacte est dans le contrat OpenAPI ([Les référentiels côté API](api-referentiels.md)).

## Ce que partagent les cinq référentiels courts

| Référentiel | Colonnes obligatoires | Facultatives |
| --- | --- | --- |
| Typologies | `code`, `libelle` | `ninja` |
| Emplacements | `code`, `nom` | `latitude`, `longitude` |
| Stands | `code`, `nom`, `typologies` | `effectifMin`, `effectifMax` |
| Créneaux | `date`, `heureDebut`, `heureFin` | `couverturePause` |
| Journées types | `nom`, `vacations` | `dates` |

Les en-têtes se reconnaissent à la casse et aux accents près, et plusieurs valeurs dans une case se séparent par `|`, `;` ou une virgule. Les imports se rangent dans l'ordre où les données se tiennent, et les dates passent avant les animateurs : un jour d'indisponibilité importé n'est conservé que si l'édition porte déjà le créneau correspondant.

- Une colonne absente, ou une case vide, n'efface rien. Un fichier de trois colonnes qui renomme des stands ne touche ni leurs horaires, ni leur emplacement, ni leurs indicateurs. C'est la doctrine de l'import additif de la grille des compétences ([ADR 0030](repo:docs/decisions/0030-grille-competences-import-additif.md)) appliquée à une fiche.
- Une ligne se reconnaît à son `code`, sinon à son nom, et une ligne connue est mise à jour au lieu d'être refusée : réimporter un fichier corrigé est le geste normal. La colonne `code` est obligatoire mais une case peut rester vide : la ligne désigne alors la seule ligne de l'édition qui porte ce nom (ce libellé, pour une typologie) à la casse et aux accents près, et en garde le code. Deux homonymes refusent la ligne en demandant un code, un nom inconnu crée une ligne.
- Aucune colonne `id` n'est lue et l'export n'en écrit plus : les identifiants sont numérotés dans chaque édition ([ADR 0050](repo:docs/decisions/0050-identifiants-generes-par-edition.md)) et `S4` désigne un autre stand dans l'édition voisine. Un fichier avec une colonne `id` et sans colonne `code` (un export ancien) est refusé, avec la consigne de renommer l'en-tête.
- Un code est unique dans l'édition, ne contient ni virgule, ni point-virgule, ni barre verticale, et n'a pas la forme d'un identifiant de son référentiel (`S4` pour un stand), pour ne jamais se confondre avec l'identifiant que les écrans affichent.
- Une typologie qu'un stand cite sans qu'elle existe est créée, libellé et code égaux à la valeur citée, et l'aperçu la nomme avant l'écriture. La colonne `typologies` cite une typologie par son code, ou par son libellé quand elle n'a pas de code (c'est ce que l'export écrit). Un stand sans effectif tient à une personne, ce que l'aperçu dit aussi.

L'aperçu n'écrit rien. L'écriture relit le fichier et refait tous les contrôles. Chaque ligne fautive est refusée seule, avec sa raison, sans bloquer les autres, et une ligne refusée ne laisse rien derrière elle, pas même la typologie qu'elle citait.

Un collage depuis un tableur est accepté sur chaque carte : les cellules, séparées par des tabulations, sont réécrites en CSV à `;` avec chaque cellule entre guillemets, puis envoyées comme un fichier `collage.csv`. Les guillemets sont nécessaires, car le serveur reconnaît le séparateur en comptant `,`, `;` et la tabulation hors guillemets et ne doit pas prendre les virgules de `09:00-12:00, 12:00-13:00 R` pour lui. Une fois l'import écrit, « Voir les N lignes importées » ouvre l'écran du référentiel filtré par `?ids=` ; « Tout afficher » retire le filtre.

### Créneaux et journées types

Ces deux référentiels n'ont pas de `code` que le fichier nomme, ce qui décide de leur clé :

- Un créneau est reconnu à son triplet `(date, heureDebut, heureFin)`. Son identifiant est engendré par la base et un import de scénario le réattribue, donc il ne voyage pas. Une ligne déjà présente n'écrit que son `couverturePause`, le reste étant la clé. Une fin antérieure ou égale au début passe minuit, comme dans le formulaire, mais un début égal à la fin est refusé : c'est une vacation sans durée.
- Une journée type est reconnue à son `nom`, à la casse près. Ses `vacations` tiennent sur une ligne (`09:00-12:00, 12:00-13:00 R, 14:00-20:00`, `R` marquant un relais repas), la même écriture compacte que celle des outils MCP. Ses `dates` sont fusionnées dans le calendrier : une date que le fichier nomme change de journée type, une date qu'il ne nomme pas garde la sienne.

L'import des journées types ne déplace aucun créneau. Un modèle est un générateur, jamais la vérité ([ADR 0032](repo:docs/decisions/0032-journees-types-nommees-vacations-fixes.md)) : matérialiser le calendrier reste le geste explicite « Appliquer » de l'écran Journées types, qui montre d'abord ce qu'il changerait. C'est aussi pourquoi cet import ne marque pas l'édition comme modifiée depuis la dernière résolution : rien de ce que le solveur lit n'a bougé.

### Dates et heures

Les dates se lisent dans les dialectes qu'un tableur écrit (`2026-07-10`, `10/07/2026`, `10-07-2026`, `10.07.2026`), les heures aussi (`09:00`, `9:00`, `9h`, `9h30`, `9`, `09:00:00`). Une année sur deux chiffres est résolue vers l'avenir (les dix dernières années et les quatre-vingt-neuf prochaines) : sur une date d'édition, `27` est la saison prochaine, pas 1927. C'est l'inverse d'une date de naissance, détaillée plus bas.

## L'import CSV des animateurs

Un import partiel : il ne touche que les animateurs, une ligne à la fois, et ne supprime rien tant qu'on ne le lui demande pas. Carte Animateurs de Fichiers (`/fichiers?cible=animateurs`), bouton « Importer » de la page Animateurs, routes `POST /api/animateurs/import-csv/analyse` (aperçu en lecture pure) et `POST /api/animateurs/import-csv` (écriture).

![Import CSV des animateurs : prévisualiser puis écrire](repo:docs/diagrammes/import-csv.svg)

<sub>Source : [`diagrammes/import-csv.puml`](repo:docs/diagrammes/import-csv.puml).</sub>

### Le fichier d'exemple

[`exemple-animateurs.csv`](repo:src/main/resources/scenarios/exemple-animateurs.csv), servi par `GET /api/animateurs/import-csv/exemple`, porte une douzaine de personnes fictives sur les neuf colonnes que l'import lit, et chaque règle du format y a une ligne : trois mineurs pendant l'événement (dont une qui devient majeure quatre jours après la fin), un manager, des compétences avec et sans niveau (`JEUX:DEBUTANT|ENF`, `DIV|REF:REFERENT|TROPH`), des souhaits, des jours d'indisponibilité multiples. Ses dates s'écrivent `AAAA-MM-JJ` : réenregistré dans un tableur, `12/09/2026` revient `12/09/26`, alors que la forme ISO ressort intacte d'Excel comme de LibreOffice.

Ses typologies et ses jours d'indisponibilité viennent de `festival-realiste-canicule` (du 1er au 16 septembre 2026), parce que l'import refuse une ligne qui nomme autre chose : déposé dans une édition aux dates ou typologies différentes, il se fait rejeter des lignes. C'est un modèle de forme dont les personnes sont inventées. `AnimateurCsvExempleTest` le réimporte par le vrai lecteur (zéro ligne rejetée, en-tête reconnu seul) ; une fixture régénérée qui renomme une typologie le fait échouer, et le CSV se retouche alors à la main.

### Un lecteur CSV, et seulement CSV

[`CsvParser`](repo:src/main/java/dev/sylvain/planning/service/referentiel/CsvParser.java) est écrit à la main, sans dépendance. Il suit la RFC 4180 et reconnaît ce qu'un tableur produit : séparateur `,`, `;` ou tabulation (le plus fréquent hors guillemets l'emporte), guillemets doublés, CRLF ou LF, marque d'ordre d'octets, retours à la ligne dans une cellule. Un `.xlsx` ou un `.ods` est refusé avec la marche à suivre. Chaque ligne du rapport porte son numéro de ligne physique dans le fichier, le seul repère sur lequel l'organisateur peut agir.

Le fichier doit être en UTF-8. Excel en français enregistre encore en Windows-1252 : le navigateur lit alors chaque accent comme un caractère illisible bien avant que le serveur reçoive un octet. L'import est refusé en nommant l'encodage et en citant un extrait, plutôt que de répondre « ce n'est pas du CSV » à quelqu'un qui vient d'en enregistrer un. Un fichier réellement binaire (une NUL, ou plus d'un dixième d'illisible) reste un refus de format.

### La correspondance de colonnes

L'écran propose une correspondance à partir des en-têtes, en français comme en anglais, puis laisse tout modifier, y compris tout effacer (une correspondance vide envoyée explicitement est respectée). Elle désigne les colonnes par leur rang, jamais par leur nom : un fichier peut porter deux fois le même en-tête, ou aucun. Une colonne déjà prise par un autre champ est déplacée, jamais partagée. Une colonne non associée n'est pas une colonne vide : le champ n'est pas touché sur les fiches existantes, ce qui rend un fichier de rattrapage à trois colonnes inoffensif.

`competences`, `souhaits` et `joursIndisponibles` acceptent plusieurs valeurs par cellule, séparées par `|`, `;`, `,` ou un retour à la ligne, jamais `/` que les dates `18/07/2030` utilisent. Une compétence s'écrit `typologie` ou `typologie:REFERENT`.

### L'année sur deux chiffres est lue, et dite

Un tableur retape toute cellule qui ressemble à une date et la réenregistre en forme courte : `2000-01-01` en ressort `01/01/00`. Refuser la ligne renverrait l'organisateur vers un fichier que le même tableur réécrirait au prochain enregistrement. L'import lit donc `J/M/AA`, `J-M-AA` et `J.M.AA` (jour avant mois) avec un pivot : l'année est la plus récente possible parmi celles qui se terminent par ces deux chiffres, jusqu'à un plafond qui dépend de ce que la date désigne.

| Date (aujourd'hui 2026-09-11) | Plafond | Lecture |
| --- | --- | --- |
| Naissance `01-01-00` | aujourd'hui | 2000-01-01 |
| Naissance `12/09/27` | aujourd'hui | 1927-09-12 |
| Naissance `12.09.26` | aujourd'hui | 2026-09-12, puis refusée comme invraisemblable, en citant cette lecture |
| Indisponibilité `5/9/26` | dernier jour de l'événement | 2026-09-05 |

Le plafond d'une indisponibilité est le dernier jour de l'événement, pas aujourd'hui : un événement se prépare l'année d'avant, et lu contre aujourd'hui `18/07/27` tomberait en 1927 puis serait refusé « hors des dates de l'événement » pour une raison que personne n'a écrite. La résolution reste stricte (`31/02/26` n'est pas une date) et le message conseille d'ouvrir le CSV par l'assistant d'import du tableur en forçant la colonne des dates au type « Texte ». Chaque ligne lue sur deux chiffres porte un avertissement avec la date comprise : l'aperçu est l'endroit où cette interprétation passe sous un œil humain avant l'écriture. Le contrôle de vraisemblance (date future, plus de 120 ans) s'applique à la date lue.

### Sur quoi une ligne reconnaît une fiche

Deux clés, dans cet ordre : l'adresse e-mail, puis « prénom + nom » comparés sans casse ni accents. Une ligne qui ne reconnaît aucune fiche en crée une, sous le prochain identifiant de l'édition. Le nom est le dernier recours et il peut être ambigu : une ligne dont le nom désigne deux fiches est rejetée en nommant les identifiants, plutôt que d'en choisir une. Deux lignes du même fichier qui désignent la même personne : la seconde est rejetée en citant le numéro de la première effectivement retenue. Une ligne rejetée pour une autre raison n'occupe pas l'identité, sinon la bonne ligne serait refusée au profit d'une mauvaise.

### Doublons probables : signalés, jamais refusés

La reconnaissance ne lit jamais la date de naissance. Deux lignes portant deux adresses pour la même personne créent donc deux fiches, et une ligne sans adresse se rattache par son seul nom à un homonyme, dont elle réécrit la date de naissance (donc le régime mineur ou majeur) et l'adresse où partent ses codes d'accès. Un doublon pèse sur le solveur : il voit deux personnes, compte leurs plafonds légaux séparément et peut affecter la même personne deux fois au même moment.

L'aperçu compare donc chaque ligne acceptée sur la clé (prénom, nom, date de naissance), prénom et nom normalisés comme pour la reconnaissance, sur l'effectif que l'écriture laisserait. Une ligne cite au plus trois autres lignes et trois fiches et compte les autres (« Probable doublon des lignes 3, 4, 5 et de 2 996 autres ») : sans ce plafond, trois mille lignes identiques citeraient chacune les 2 999 autres.

| Cas | Signal | `doublonDe` |
| --- | --- | --- |
| Deux lignes acceptées, même clé | « Probable doublon de la ligne 12 », sur les deux | `ROW` |
| La ligne donne à sa fiche la clé d'une fiche conservée | « Probable doublon de la fiche A12 » | `FICHE` |
| Idem en remplacement complet, contre une fiche supprimée | suppression d'une personne aussitôt redécrite | `REPLACED` |
| Rattachement par le seul nom à une fiche née un autre jour | « Homonyme ? », les deux dates | `NAMESAKE` |
| Rattachement par le seul nom, l'adresse ne désignant personne | « L'adresse e-mail de la fiche A12 sera remplacée » | `NEW_ADDRESS` (absent si `NAMESAKE` désigne déjà la fiche) |

Ce sont des avertissements, la ligne reste acceptée : des jumeaux partagent nom et date de naissance, deux homonymes naissent le même jour, et l'opérateur tranche, informé. Ne sont pas signalées deux fiches qui portaient déjà la même clé avant le fichier (le redire à chaque réimport apprendrait à ne plus lire l'avertissement) ni une ligne rejetée. Le rapport compte les lignes signalées (`doublonsProbables`), l'écran les marque d'une pastille et la confirmation d'« Importer » redit leur nombre. Les messages désignent une fiche par son identifiant, jamais par son nom ni une adresse ; seul « Homonyme ? » cite des dates de naissance, sans lesquelles on ne peut pas trancher, dans un rapport que seul l'écran d'import affiche (ni notifié, ni journalisé, ni exposé en MCP).

### Ce qu'une ligne doit porter

Un prénom, un nom et une date de naissance : les trois que le formulaire de la fiche et l'outil MCP `creer_animateur` refusent d'omettre, le CSV n'étant pas une porte dérobée vers une fiche que l'écran refuserait. La date est indispensable : tout le régime mineur ou majeur s'en déduit à la date de chaque créneau, et la colonne est `NOT NULL`. Une ligne qui met à jour une fiche reconnue par son adresse peut laisser l'un des trois vide.

Les cellules sont bornées par la largeur des colonnes : 128 caractères pour le prénom et le nom, 255 pour l'adresse. Une valeur plus longue rejette la ligne en donnant sa longueur, signature d'une colonne associée au mauvais champ ; sans ce contrôle l'aperçu serait tout vert avant que l'écriture n'échoue sur le fichier entier. Les typologies citées passent ligne par ligne par le contrôle de la saisie d'une fiche (`TypologieService.validateIds`) : une typologie inconnue coûte une ligne, pas le fichier.

### Jours d'indisponibilité

| Situation | Ce que fait l'import |
| --- | --- |
| Jour hors des dates de créneaux | Ligne rejetée, motif au rapport. L'espace animateur ne dessine que les jours de l'événement : un tel jour serait stocké, invisible, puis effacé par la première déclaration acceptée |
| Aucun créneau dans l'édition | Import refusé en bloc (`409`) avant toute lecture : sans dates de référence, rien n'est vérifiable |
| L'animateur a une déclaration en attente | Ligne acceptée avec un avertissement nominatif : la décision de l'administrateur remplacera les jours importés |
| L'animateur existe déjà | Ajout par défaut : les jours du fichier complètent ceux de la fiche. Une case de l'écran bascule en remplacement |

L'ajout est le défaut parce qu'un import de rattrapage ne doit pas effacer ce que les animateurs ont déclaré eux-mêmes pendant la collecte. Compétences et souhaits sont toujours ajoutés : retirer une compétence reste un geste de l'écran de référentiel.

### Ajout ou remplacement complet

Le YAML remplace tout, le CSV non, sauf demande explicite. Décoché (défaut), l'import ajoute et met à jour. Coché, les animateurs absents du fichier sont supprimés et leurs postes du planning persisté sont libérés, pas détruits. Le remplacement complet est refusé tant qu'une ligne est rejetée, sinon il supprimerait justement les personnes que ces lignes n'ont pas su décrire (le bouton est désactivé, avec le motif).

L'aperçu chiffre ce que la suppression emporte (places libérées, verrouillages retirés, contraintes ad hoc concernées) et nomme ce qui part en cascade et sans retour : déclaration de disponibilités, accusé de réception du planning publié, échanges de la foire au planning, code d'accès à l'espace animateur, compétences et souhaits.

### Deux appels, une transaction

L'analyse et l'écriture sont deux endpoints, et le second reçoit le fichier plutôt que le rapport : il relit tout et rejoue chaque vérification, si bien qu'un rechargement de page, un rejeu ou un corps fabriqué à la main ne fait passer aucune ligne devant un contrôle. L'écriture est atomique (une seule transaction), et le rapport n'annonce jamais 148 lignes écrites après un retour arrière. Le fichier est traité en mémoire et jamais écrit sur disque, car il porte des noms, des dates de naissance et parfois des mineurs. Deux plafonds le bornent : 1 000 000 de caractères et 5 000 lignes de données.

## L'import de la grille des stands

La carte Grille des stands (`/fichiers?cible=grille-stands`, `POST /api/stands/import-grille/analyse` puis `POST /api/stands/import-grille`) importe la matrice que l'organisateur tient dans son classeur : stands en lignes, jours et créneaux en colonnes, un effectif par case. Il transpose les règles de l'import des animateurs (deux appels, rejoué, en mémoire, sans suppression) et diffère sur trois points ([ADR 0022](repo:docs/decisions/0022-import-de-la-grille-des-stands.md)) :

- l'identité d'une ligne est le code du stand, sinon son nom exact, jamais son identifiant ;
- une colonne sans créneau est ignorée et listée, pas refusée ;
- un créneau sans colonne garde la case actuelle du stand, segments compris : un stand qui n'y ouvre que de 14 h à 15 h, ou à deux effectifs successifs, en ressort tel quel.

Une colonne plus étroite que son créneau (sept tranches du classeur sous quatre ou cinq créneaux) écrit une fenêtre à ses propres bornes et laisse le reste du créneau en l'état : le classeur s'importe colonne pour colonne et la grille de saisie montre ensuite les mêmes colonnes. Une bande dont la minute n'a qu'un chiffre (`9:5`) est refusée plutôt que complétée. Le bouton « Télécharger la grille actuelle comme modèle » rend l'édition telle qu'elle est, réimportable telle quelle ; le fichier d'exemple est servi par `GET /api/stands/import-grille/exemple`. Le plafond est de 2 000 lignes.

Le modèle écrit ses bandes `10h00-12h00` et non `10:00-12:00`, volontairement : Excel retype `13:00-16:00` en la date `30/11/1999 13:16:00`, la bande est perdue et le fichier revient avec des en-têtes qui ne nomment plus aucun créneau. Les deux formes sont lues à l'import, seule celle en `h` survit à un aller-retour par un tableur, et un test verrouille l'absence de `:` dans la ligne des bandes.

## La grille des compétences ne passe plus par un fichier

La matrice animateurs × typologies des appréciations ne s'échange plus par fichier, ni en export ni en import : elle se saisit exclusivement à l'écran `/animateurs?onglet=competences` ([ADR 0050](repo:docs/decisions/0050-identifiants-generes-par-edition.md)). Elle s'enregistre par `PUT /api/animateurs/competences/grille`, une ligne par fiche modifiée, chacune avec sa propre précondition de modification concurrente ([ADR 0023](repo:docs/decisions/0023-modification-concurrente-par-horodatage.md)). La réponse est un compte rendu par ligne (écrite, périmée, refusée) et une fiche refusée n'annule pas les autres. Deux choses refusent l'appel entier : un niveau qui n'est pas l'un des trois noms (la désérialisation le rejette avant le service) et un corps de plus de 2 000 lignes.
