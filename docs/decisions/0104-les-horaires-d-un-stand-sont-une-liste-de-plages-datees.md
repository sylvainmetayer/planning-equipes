# 0104 — Les horaires d'un stand sont une liste de plages datées

- **Statut** : accepté, implémenté (modèle, migration, format YAML) · l'écran suivra dans son propre changement
- **Date** : novembre 2026 · révisé le 26 novembre : « modèle gardé, déplié ensuite » (§ 1, 3, 5, 6, 7, 8) · plus tard le même jour : l'origine d'une plage est stockée (§ 9)
- **Portée** : modèle du stand, résolution des horaires, migration de la base, format du scénario YAML (version 5), sauvegardes avant vidage

## Contexte

Un stand disait ses horaires de trois façons : des **règles récurrentes**
(« tous les jours de 10 h à 12 h puis de 14 h à la fermeture », avec une
portée `TOUS`, `JOURS_SEMAINE`, `PLAGE` ou `DATES`), des **exceptions datées**
(ouvertures et fermetures) qui primaient sur elles, et, au-dessus de l'ensemble,
la consigne d'édition ([0043](0043-consigne-d-edition-fermer-une-bande-sans-rien-detruire.md)).
Trois couches, un résolveur qui les replie à chaque lecture, une spécificité à
arbitrer entre règles, une lecture particulière d'un jour que personne ne cite
([0033](0033-un-stand-qui-declare-ses-ouvertures-est-ferme-ailleurs.md)), un
compactage, un élagage et un rapport d'anomalies pour dire quand deux règles se
marchaient dessus. L'organisateur n'a jamais demandé une règle : il demande de
poser « chaque lundi de 10 h à 12 h », et la règle stockée était la façon dont
l'application s'en souvenait.

## Décision

1. **Un stand porte une liste de plages datées**, `Stand.plages` : un jour, des
   heures (la fin peut manquer : « jusqu'à la fermeture »), un mode
   (`OUVERTURE` ou `FERMETURE`), un effectif pour une ouverture, un motif. La
   résolution ne lit que cela. Une récurrence reste, tant que l'édition n'a
   pas de dates, le **modèle** du stand (« samedi et dimanche, 10 h–18 h »,
   `Stand.horaires`) ; dès que des créneaux existent, il est **déplié** en
   plages sur les jours de l'événement (§ 5).
2. **La résolution ne lit que des plages.** Un jour sans plage est ouvert toute
   la journée ; un jour qui porte une ouverture n'est ouvert que sur elles ; un
   jour qui ne porte que des fermetures est ouvert hors d'elles ; un jour ne
   porte jamais les deux modes. La consigne reste posée au-dessus, comme avant
   (sa réécriture en plages est un changement à part).
3. **Le stockage ne bouge pas** : les plages vivent dans les deux tables
   `stand_ouverture` et `stand_indisponibilite`, qui portaient déjà exactement
   cela, et le modèle dans les deux tables de règles de la 3.x,
   `stand_horaire` et `stand_horaire_fenetre`, inchangées et non renommées (leurs
   identifiants sont ceux que l'éditeur actuel relit). La migration `V153` ne
   copie aucune plage existante, ne supprime aucune règle : elle **convertit en
   plages les règles des éditions qui ont des créneaux** et laisse les règles
   en place, modèle de leur stand.
4. **La conversion est celle que l'application faisait à chaque lecture**, jour
   par jour, sur les jours de l'événement — du premier au dernier créneau de
   l'édition, plus le lendemain d'un créneau qui passe minuit : une exception
   datée garde son jour ; sinon les règles de portée la plus précise gagnent et
   leurs fenêtres se réunissent ; deux règles de même portée et de modes
   opposés donnent l'ouverture ; un stand dont les règles déclarent des
   ouvertures est **fermé** un jour qu'aucune ne nomme (0033, appliqué une fois
   pour toutes) ; sinon rien. Elle est écrite deux fois — en SQL pour la base,
   en Java (`HorairesRecurrents`) pour les fichiers et les outils — et un test
   joue les deux contre le condensé de ce que la 3.x calculait sur chacun des
   scénarios livrés, jour par jour.
5. **Une édition sans créneau n'a pas de jour** où poser ses règles : elles
   restent le **modèle** du stand, ni converties, ni perdues, sans
   avertissement. Le modèle est **déplié à la création des créneaux** — le
   moment le plus sûr, parce que tout créneau naît par une seule porte
   (`CreneauRepository`, que la création, la modification, la création en lot,
   l'import, les journées types et le remplacement de la grille traversent) et
   parce qu'une résolution ne doit pas écrire. Un créneau qui ouvre un jour
   que l'édition ne comptait pas (le sien, et le lendemain s'il passe minuit)
   déplie le modèle de chaque stand sur ce jour, avec la lecture du § 4 :
   **idempotent** par construction. Un jour n'est nouveau qu'une fois, un jour
   qui porte déjà une plage la garde, une plage supprimée à la main ne revient
   pas, et un deuxième créneau sur le même jour n'ajoute rien. Le modèle
   **reste** une fois déplié — l'éditeur actuel le relit, il déplie les jours
   qui s'ajouteront, il est la source du futur générateur de créneaux : une
   règle envoyée à une édition qui a déjà des créneaux est stockée **et**
   dépliée aussitôt sur ses jours (les plages que l'ancien modèle avait
   dépliées, marquées comme telles (§ 9), sont retirées d'abord, une plage
   saisie à la main reste) ;
   renvoyer le modèle stocké tel quel ne déplie rien.
6. **Le format YAML passe en version 5** : la clé `plages` remplace
   `ouvertures` et `indisponibilites` d'un stand, et `horaires` devient le
   modèle. Le maillon 4 → 5 convertit un fichier plus ancien sur les jours des
   créneaux du fichier ; sans créneau dans le fichier, les règles restent sous
   `horaires`, sans rien dire ([0101](0101-schema-yaml-2-et-chaine-de-conversions.md)).
   Les versions 3 et 4 sont celles des paramètres et des niveaux de règle,
   prises entre-temps par d'autres changements.

7. **Pont temporaire pour l'écran de la 3.x**, retiré avec l'éditeur de
   plages : l'éditeur d'horaires actuel envoie et relit les règles dans la
   fiche du stand (`horaires`, avec leurs identifiants). Le serveur les traite
   comme au § 5 : gardées comme modèle sans créneau, dépliées aussitôt sinon,
   comme le fait déjà l'outil d'assistant `ajouter_horaire_stand`. Une écriture
   sans `horaires` laisse le modèle tel quel, `horaires: []` l'efface. Jamais
   de refus, jamais de règle perdue en silence.

8. **L'assistant voit et efface le modèle ; le compactage disparaît.** Les
   vues MCP d'un stand (`lister_stands`, `consulter_stand`) portent le modèle
   (`horaires`). `ajouter_horaire_stand` d'une règle identique à une règle
   existante ne change rien. `effacer_horaires_stand` revient : il efface le
   modèle **et** les plages que ce modèle a dépliées (celles qui portent la marque
   du § 9, sur quelque jour que ce soit), et garde les plages saisies à la main. Le compactage des horaires (la route
   `POST /api/stands/compactage-horaires`, l'outil `compacter_horaires_stands`)
   est retiré dès la 4.0 : une plage datée n'a plus de règle à rejoindre.

9. **Une plage dit d'où elle vient.** Chaque plage porte `depliee` : vrai quand
   l'application l'a dépliée du modèle (le dépliage du § 5, la migration du § 3,
   le maillon 4 → 5 du § 6), faux quand quelqu'un l'a saisie. La colonne vit
   dans les deux tables de plages (défaut faux), la clé `depliee` dans le
   fichier YAML (absente = saisie), et `sourceOfDay` en tire trois réponses :
   `DEFAUT` (aucune plage), `REGLE` (que des plages dépliées : le régime normal
   du stand) et `EXCEPTION` (au moins une plage saisie). Seule une exception
   est une « exception » : la présélection d'une consigne, qui range à part les
   stands aux horaires posés à la main, ne confond plus un stand ordinaire avec
   eux. L'origine est dite par le serveur : à l'écriture d'une fiche, une plage
   garde sa marque si elle est identique, à l'identifiant près, à une plage
   stockée marquée ; une plage modifiée, ou écrite par un client, est une
   saisie. La saisie de la grille de stands réécrit la fiche entière avec la
   même règle : une plage que la grille laisse telle quelle garde sa marque,
   ce qu'elle change devient saisi. La marque suit le contenu : une plage
   saisie identique à une plage dépliée redevient dépliée, ce qui est
   acceptable, l'effet sur le stand étant le même.

## Ce que ça coûte

- **Un modèle et des plages se côtoient** dans une édition qui en a les deux,
  et une colonne de plus à garder en phase (copie d'édition, réécriture après
  un calcul, fichier YAML, échange avec un worker). Retirer les plages d'un
  ancien modèle ne se recalcule plus : on retire les marquées, et une plage
  saisie qui leur ressemble en tout point reste. L'éditeur de plages, qui
  remplacera le pont, a le dernier mot.
- **Un fichier YAML d'une édition datée porte la règle et ses plages** : plus
  long qu'un fichier de plages seules, mais il se relit comme la base que la
  migration a convertie.
- **Un horaire quotidien sur une longue édition pèse davantage en base** : une
  ligne par jour et par fenêtre au lieu d'une règle.
- **La migration ne redescend pas.** Retour : l'export YAML d'avant la montée,
  relu par la 3.x, ou la sauvegarde de la base.

## Alternatives écartées

- **Recalculer l'origine depuis le modèle** (la première rédaction du § 8 :
  déplier à blanc et comparer) : sans colonne, mais `sourceOfDay` aurait dû lire
  le modèle et les créneaux à chaque cellule, et une plage saisie identique à
  une plage dépliée aurait été prise pour elle. La marque règle les deux.

- **Garder les règles comme troisième couche de la résolution** : c'est l'état
  d'avant. Le modèle n'est lu que pour être déplié ; la résolution, les
  analyses et le solveur ne voient que des plages.
- **Ne rien garder** (la première rédaction de cette décision) : une édition
  sans créneau perdait ses règles ou les rangeait dans une archive que rien ne
  lisait, et le parcours qui commence par les horaires des stands, puis crée les
  créneaux, devenait impossible.
- **Déplier à la résolution plutôt qu'à la création des créneaux** : la
  résolution lit à chaque écran et ne doit pas écrire ; une lecture qui change
  la base rend deux écrans successifs incohérents.
- **Ne garder le modèle que sans créneau** (consommé dès qu'il y a des
  jours) : un seul créneau créé le consommerait et les jours suivants
  retomberaient sur « ouvert toute la journée » ; l'écran actuel perdrait la
  règle qu'il relit. Le dépliage par jour nouveau évite les deux.
- **Une colonne JSON sur `stand`** pour le modèle : plus compacte, mais l'écran
  actuel relit l'identifiant de chaque règle (copie d'un stand à l'autre) ; les
  tables de règles de la 3.x le portent déjà.
- **Une seule table `plage_stand`** (une table, une colonne de mode) : plus
  propre sur le papier, mais il fallait recopier les plages existantes — la
  partie de la migration où une perte serait silencieuse — pour un gain que
  personne ne voit. À reprendre si le stockage doit un jour porter une plage
  d'un autre genre.
- **Convertir les règles en Java dans la migration** (migration Java de
  Flyway) : l'empreinte gelée des migrations ne couvre que les fichiers SQL, et
  le test de migration rejoue le fichier tel quel dans une transaction.
- **Convertir aussi pour une édition sans créneau, sur une plage de dates
  devinée** : inventer des jours pour une édition qui n'en a pas serait écrire
  des horaires que personne n'a demandés.
