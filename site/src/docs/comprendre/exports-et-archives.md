# Exports et archive de fin d'événement

Vous savez ce que contient chaque export (CSV des heures et de l'équité, PDF individuel et global, ICS), pourquoi ils s'ouvrent correctement dans un tableur, et comment l'archive de fin d'événement les réunit sans rien recopier.

Tout est généré côté serveur, dans l'édition désignée par `X-Edition-Id`. Les exports se trouvent sur l'écran Fichiers (onglet Exporter, `/fichiers?onglet=exporter`) et sur les écrans qui les produisent (Planning, Diffusion du planning, espace animateur).

## Les CSV : conventions communes

Tout CSV que rend le serveur passe par `CsvDownload.attachment` ([`CsvDownload`](repo:src/main/java/dev/sylvain/planning/api/CsvDownload.java)), qui préfixe le contenu d'un BOM UTF-8. Excel ignore le `charset=utf-8` de la réponse dès que le fichier est sur le disque et retombe sur la page de code du système : sans la marque, un nom accentué s'affiche `Métayer`. LibreOffice n'en a pas besoin et ne s'en trouble pas. `CsvParser` retire une marque de tête avant de lire l'en-tête, donc un modèle téléchargé puis renvoyé tel quel s'importe à l'identique (deux tests le vérifient sur le fichier réellement servi).

Les nombres décimaux prennent une virgule (`4,00`) et le séparateur de champs reste `;` : la paire qu'attend un tableur français. Avec un point, la cellule est lue comme du texte, la colonne s'aligne à gauche et `=SOMME()` répond zéro, ce qui est pire qu'un total faux parce que cela ressemble à une réponse.

Un texte qu'un tableur exécuterait comme une formule (il commence par `=`, `+`, `-`, `@`, une tabulation ou un retour chariot) sort précédé d'une apostrophe, la marque qu'Excel et LibreOffice lisent comme « ceci est du texte » (`CsvFormulaGuard`). Un nom saisi dans un formulaire ne doit pas devenir une formule dans le tableur de l'organisateur. Un nombre n'en porte pas : une longitude négative reste un nombre, et une heure ou un total d'heures (`12:30`, `-1,50`) aussi. La règle vaut pour chaque CSV du serveur, y compris `heures-planning.csv`, `equite-planning.csv`, `plan-formation.csv`, `intendance-repas.csv` et `diff-publication.csv`. Chaque import CSV retire cette apostrophe à la lecture, si bien qu'un fichier exporté se réimporte à l'identique ; « Exporter cette liste » sur les écrans de référentiel applique la même règle dans le navigateur.

### Les six référentiels

`GET /api/reference-data/export-csv` réécrit les six référentiels de l'édition (animateurs, créneaux, emplacements, gabarits, stands, typologies) dans un ZIP, un fichier par référentiel, chacun à cocher, pour un tableur. Seul celui des animateurs se réimporte, par l'[import CSV](imports-csv.md) : il reprend l'en-tête de `exemple-animateurs.csv` et exige une édition qui a déjà ses créneaux, puisque sans dates un jour d'indisponibilité serait refusé. Les cinq autres ne se relisent pas : le YAML de l'édition est leur chemin d'entrée.

`ReferentielCsvExportServiceTest` rejoue le fichier des animateurs dans une autre édition (zéro ligne refusée) et vérifie que les fiches s'y retrouvent à l'identique, cellules à valeurs multiples comprises.

Une case qui contient un séparateur possible (`;`, virgule, tabulation) sort entre guillemets, et pas seulement quand elle contient celui du fichier. Le lecteur devine le dialecte en comptant les trois candidats hors guillemets sur tout le fichier : une colonne de vacations pose trois virgules par ligne contre deux points-virgules d'en-tête, et le fichier qu'on venait d'écrire se relisait comme un fichier à virgules.

## Les heures pour la paie

`GET /api/planning/hours/export?source=publie|persiste` rend `heures-planning.csv` (« Heures pour la paie (CSV) » de la page Planning) : une ligne par animateur, une colonne par semaine ISO (`2026-W33`), puis `total`, `dimanche`, `jours feries`, `dont dimanches feries`, `nuit paie (apres 22h)`, `amplitude max (h)` et `jours au-dela du seuil d'amplitude`. La colonne de nuit est la seule trace de la borne fixe de 22 h dans l'application : la soirée est l'heure réglable des paramètres légaux, et la paie garde sa nuit à part ([ADR 0055](repo:docs/decisions/0055-une-seule-soiree-la-nuit-de-la-paie-a-part.md)). `GET /api/planning/hours` rend le même rapport en JSON, avec la source lue, sa date et `publicationAvailable`.

Le serveur lit le plan, le navigateur ne l'envoie pas. Le fichier doit pouvoir être garanti devant une paie ou une inspection, ce qu'un planning posté par un onglet ne permettait pas : il disait ce que l'onglet tenait à un instant, ni ce que la base contenait ni ce qui avait été publié. Deux sources sont possibles :

| `source` | Ce qui est lu |
| --- | --- |
| `publie` | la publication en vigueur : les sièges figés à la publication, relus comme l'espace animateur les relit, contre le référentiel d'aujourd'hui |
| `persiste` | le plan enregistré |

Sans paramètre, c'est la publication quand il y en a une, le plan enregistré sinon. `publie` avant toute publication est un `409` (un état de l'édition) : un fichier vide dirait à tort que personne n'a travaillé. Pour le plan publié, un créneau qui existe encore donne ses heures actuelles (un créneau supprimé garde celles que la publication a notées), les pauses déduites et la soirée suivent les paramètres légaux actuels, et un animateur ou un stand supprimé depuis sort du fichier. Le fichier ne se répète donc à l'identique d'un appel à l'autre que tant que ni la publication ni ces référentiels ne bougent.

La première ligne nomme la source et sa date (`Source : plan publié le 12/07/2026 à 18:30`, ou `Source : plan enregistré, résolu le …`). Le plan enregistré n'a pas d'autre date que sa dernière résolution ou restauration : un siège retouché à la main n'en laisse aucune, et le fichier dit laquelle plutôt que de la faire passer pour celle de la dernière modification. La ligne d'en-tête des colonnes vient ensuite.

## L'équité

`GET /api/planning/equite/export` rend `equite-planning.csv` (« Équité (CSV) » de la page Planning) : le tableau d'équité lu sur le plan persisté, une ligne par animateur affecté, dans l'ordre de l'écran. Les colonnes sont `heuresTotal`, une par semaine ISO, puis `heuresSoiree`, `heuresWeekEnd`, `heuresJourFerie`, `postes`, `postesPenibles`, `standsDistincts`, `typologiesDistinctes`, `emplacementsDistinctsParJourMax`, `tauxSouhaits`, `tauxAppreciation`, `joursTravailles`, `joursRepos` et `plusLongueSerie`. Les taux sont des rapports (`0,75`), qu'un tableur met en pourcentage d'un clic. La synthèse (médiane, min, max, écart-type) n'y figure pas : elle se recalcule dans le tableur, et l'écran la montre.

## Le planning individuel en PDF

Le document remis à un animateur est un livret en trois parties, dans cet ordre, chaque page numérotée « n / N ». Il affiche l'horaire effectif du poste et non celui, plus large, de son créneau : un poste réduit par une fermeture partielle montre les heures réellement couvertes. Quand le stand a un emplacement géocodé, le PDF porte un lien OpenStreetMap.

1. Vue d'ensemble. L'en-tête (nom, édition, période, nombre de jours dont ceux de repos), quatre chiffres (heures, créneaux, stands, jours travaillés), la frise « Vos N jours en un coup d'œil » (une ligne par jour, repos compris, sur un axe horaire commun, une barre par poste, le total du jour à droite), la légende des couleurs, puis les stands regroupés par emplacement avec leur typologie.
2. Vos journées. Un bloc par jour, puis une carte par créneau : heures effectives, stand, typologie, lieu (cliquable quand l'emplacement est géocodé), coéquipiers, durée. Les repas et les pauses restent sous la vacation qui les doit, dans les mêmes mots que l'espace animateur. Un jour de repos garde sa ligne, un jour sous consigne porte son bandeau (« Horaires modifiés — … »), et un jour n'est jamais coupé entre deux pages.
3. Avec qui, et où. Les coéquipiers comptés (les plus fréquents d'abord), les vacations en nombre (montage, démontage) avec leur effectif, les lieux avec les dates qui y sont passées.

Deux règles de fond. La couleur d'une barre porte la typologie du stand, jamais le stand : douze stands sur six catégories donnent une légende lisible. Le référentiel ne stocke pas de couleur, et `TypologiePalette` attribue huit teintes dans l'ordre des identifiants, ce qui rend le document reproductible d'une exécution à l'autre ; un stand à plusieurs typologies prend la première. Et au-delà de huit coéquipiers sur la même ligne (`AnimateurPlanningView.SEUIL_NOMS`), le document écrit l'effectif (« Avec 104 personnes ») et aucun nom : le montage rassemble une centaine de personnes, dont la liste nominative n'apprend rien.

Un animateur sans affectation garde l'état vide (« Aucune affectation pour cet événement. ») et le lien de son espace. Ce lien est la donnée d'authentification de l'espace : il est long et personne ne le recopie depuis une feuille imprimée, d'où le QR code du cartouche « Votre espace en ligne » (`QrCodeEspace`, encodage `zxing-core`, modules dessinés en vectoriel). L'adresse reste écrite en dessous, pour une photocopie trop pâle et pour qui veut voir où mène le code. Sur le livret, le cartouche ferme la vue d'ensemble quand elle lui laisse la place et ferme le document sinon, car une bande seule sur sa propre page coûterait une feuille pour une phrase.

### Le livret ou la feuille

La feuille à plier est une option du PDF individuel : le paramètre `feuille=true`. L'ancien paramètre `format=livret|feuille` reste accepté comme alias (liens déjà imprimés, mails, clients MCP) et `feuille=true` l'emporte. Une valeur inconnue vaut `livret` : une faute de frappe sur un téléchargement ne mérite pas un 400.

| Option | Document | Pour qui |
| --- | --- | --- |
| (défaut) | A4 portrait, plusieurs pages, les trois parties ci-dessus | l'espace animateur, les mails de publication, l'export d'un animateur |
| `feuille=true` | une feuille A4 paysage recto-verso : recto un calendrier semaine par ligne avec mini-frise et créneaux, verso les coéquipiers, les grandes équipes et les lieux | l'impression en masse par l'organisation, un tirage par personne au lieu de cinq |

Les deux nomment l'édition sous le nom de l'animateur (une personne revenue d'une année sur l'autre a deux documents à distinguer) et composent la même vue interne, `AnimateurPlanningView`, ce qui les empêche de dire deux choses différentes. Les deux sorties sont offertes à l'animateur (« Télécharger le livret PDF », « Télécharger la feuille recto-verso ») comme à l'organisation (écran Diffusion du planning). Le paramètre `feuille` est accepté sur `GET /api/espace-animateur/{jeton}/planning.pdf`, `POST /api/planning/export/pdf/animateur/{animateurId}`, `POST /api/planning/export/pdf/all` et `POST /api/planning/export/bundle/all`.

## Le planning global en PDF

`GlobalPlanningPdf` produit le PDF de l'organisation en A4 paysage, un document à sommaire plutôt que deux tableaux (`GET /api/planning/export/pdf/global`). Les pastilles de jour, les noms de stand et les lettres de l'alphabet sont des liens internes, car un planning de soixante pages qu'il faut faire défiler est un planning que personne ne lit jusqu'au bout.

1. Sommaire. La période, cinq chiffres (jours, stands, sièges et ceux non pourvus, animateurs, heures) et les entrées des sections.
2. Vue d'ensemble. La grille stands × jours : le nombre d'animateurs différents présents sur le stand ce jour-là, sur une échelle à quatre paliers (1-2, 3-4, 5-8, 9 et plus), stands groupés par emplacement, avec une dernière ligne « Animateurs présents » par jour. Au-delà de 24 jours, la grille est découpée en plusieurs pages plutôt que réduite à rien.
3. Par journée. Un jour par page : stands en lignes, fenêtres d'ouverture en colonnes, noms dedans, « — » pour un stand fermé. Au-delà de huit fenêtres distinctes, la journée s'écrit une ligne par créneau.
4. Par stand. Chaque stand, jour par jour, dans la même forme.
5. Animateurs de A à Z. Une ligne par personne : total d'heures, nombre de créneaux, heures de chaque jour, sur la même échelle.

Les sièges que personne ne tient sont écrits en couleur d'accent (« Aucun animateur affecté », « 3 non pourvus ») : c'est exactement ce que l'organisateur ouvre ce document pour trouver. La marque blanche s'applique aux deux PDF : logo, bandeau et palette viennent des clés `pdf-*` de `BRANDING_IMAGES` et `BRANDING_COLORS` (`PdfTheme`), l'échelle du planning global est l'accent du déploiement dilué dans du blanc, et le pied de page de provenance (qui a généré le document, quand, depuis quelle édition et quel plan) est le même partout.

## Le calendrier ICS

Le même document ICS se sert de deux façons, qui diffèrent par le mode, le format restant le même : en téléchargement (une photo à l'instant du clic, `POST /api/planning/export/ics/animateur/{animateurId}` ou `POST /api/planning/export/ics/all`) ou en abonnement, sur une adresse permanente que l'agenda rappelle tout seul, `GET /api/abonnements/{token}/planning.ics` ([Les accès et comptes](acces-et-comptes.md)). Les `UID` des événements sont stables d'un appel à l'autre, condition pour qu'un client d'agenda mette à jour ses rendez-vous au lieu d'en créer en double. Comme le PDF, l'ICS affiche l'horaire effectif du poste, et un stand géocodé donne `LOCATION` et `GEO`.

## L'archive de fin d'événement

`GET /api/exports/archive-evenement` (onglet Archive de Fichiers, `/fichiers?onglet=archive`) écrit en un seul ZIP les exports d'une édition terminée. Chaque partie est opt-in par un paramètre booléen ; n'en demander aucune est un `400` ; un ZIP réduit à son manifeste serait trompeur.

| Paramètre | Entrée du ZIP | Générateur | Tiré du plan |
| --- | --- | --- | --- |
| (toujours) | `LISEZMOI.txt` | le manifeste | non |
| `pdfGlobal` | `planning-global.pdf` | `GET /api/planning/export/pdf/global` | oui |
| `equite` | `equite.csv` | `GET /api/planning/equite/export` | oui |
| `heures` | `heures.csv` | `GET /api/planning/hours/export` sans `source` : la publication en vigueur s'il y en a une, le plan enregistré sinon | oui |
| `referentiels` | `referentiels/*.csv` | les six fichiers de `GET /api/reference-data/export-csv` | non |
| `scenario` | `scenario.yaml` | `GET /api/planning/export-scenario` | non |
| `publication` | `publication.csv` | `GET /api/planning/publication/export` | non |
| `individuels` (+ `feuille=true`) | `plannings-individuels/` | le PDF et l'ICS de chaque animateur, ceux de `POST /api/planning/export/bundle/all` | oui |

Les générateurs sont ceux des écrans, jamais une copie : un fichier de l'archive est, à l'octet près, celui que son écran aurait téléchargé au même instant (BOM compris pour les CSV). `ArchiveEvenementResourceTest` le vérifie pour `equite.csv`, `heures.csv`, `referentiels/*.csv` et `scenario.yaml`, et réimporte `scenario.yaml` dans une édition vide : c'est l'équivalent léger, cloisonné et réimportable du [dump SQL](restaurer-une-edition.md#le-dump-sql-doutillage-de-test), qui n'entre pas dans l'archive parce qu'il porte toutes les éditions et tous les jetons.

Une exception est assumée : les plannings individuels n'impriment pas le lien de l'espace. Le PDF qu'un animateur reçoit porte son adresse en clair et en QR code, et cette adresse est son jeton d'accès ; une archive gardée un an n'a pas à en contenir cent cinquante. Ils sont donc rendus d'un plan dont les fiches ne portent aucun jeton, même mise en page sans l'encadré. Aucune partie ne contient `accessToken` ni `abonnementToken`, ce que le test vérifie en cherchant les deux jetons d'une fiche dans chaque entrée.

Le manifeste est un texte brut (CRLF, UTF-8) lisible sans l'application : l'édition, la date de génération, la version, la dernière résolution et son score (« inconnu » sans analyse en mémoire), la mention d'une résolution en cours (l'archive porte alors le dernier plan enregistré), la dernière publication (« aucune publication »), le nombre de journées relues, la liste des fichiers et la mention RGPD (responsable de traitement et durée de conservation, clés `responsable-traitement` et `conservation` de `LEGAL_MENTIONS`). Le fichier s'appelle `archive-<édition>-<AAAA-MM-JJ>.zip`, le nom de l'édition réduit à des caractères sans accent.

L'archive est écrite en flux. Tout ce qui peut refuser (le choix vide, le scénario d'une édition vide, les lectures du manifeste) se joue avant le premier octet ; les entrées s'écrivent ensuite une à une dans la réponse, les plannings individuels en dernier, un document à la fois. Une panne en cours d'écriture ne peut plus devenir un code d'erreur : elle coupe le téléchargement, et le ZIP tronqué ne s'ouvre pas. L'action est journalisée comme un export (`EXPORT_ARCHIVE_EVENEMENT`, avec les noms des parties emportées, refus compris) par le filtre de réponse, donc au moment où la réponse part et avant le flux : un téléchargement coupé en route reste inscrit comme réussi ([Tâches de fond, notifications et journal](taches-de-fond-et-notifications.md)).
