# 0084 — Sauvegarder l'édition (scénario et planning) avant de la vider, sans purge, et la restaurer d'un geste

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : schéma PostgreSQL (migration), service, API, RGPD
- **Complète** : [0015](0015-sauvegarde-par-pg-dump-restauration-hors-application.md) (le dump nocturne, qui ne restaure qu'une instance entière)

## Contexte

Deux gestes vident **une** édition : la réinitialisation (« Vider cette
édition », `reinitialiser_donnees`) et l'import d'un scénario, qui remplace
stands, animateurs, créneaux, affectations et contraintes ad hoc. Un troisième
vide tout : l'import d'un export SQL complet, qui supprime chaque édition avant
de la réécrire ; c'est un geste d'exploitation sur l'instance, hors du champ de
cette décision (voir Conséquences). Aucun ne se défaisait :

- les **instantanés** ne portent que les affectations, et leur restauration
  refuse dès qu'un stand, un créneau ou un animateur qu'ils nomment a disparu —
  exactement ce que laisse un vidage ;
- le **dump nocturne** (0015) se restaure hors de l'application, sur la base
  entière : revenir sur l'erreur d'une édition ramène toutes les autres à la
  veille au soir.

Le format qui sait reconstruire une édition, et elle seule, existe déjà :
l'export de scénario, que l'import relit.

## Décision

1. **Avant chaque vidage, l'export de scénario de l'édition est rangé dans
   `sauvegarde_avant_vidage`** (édition, origine `RESET`, `IMPORT` ou
   `RESTORE`, date, contenu YAML), **avec son planning** (colonne `plan` : les
   sièges, au format des instantanés, et les verrous, sous les identifiants de
   l'édition à cet instant). Une édition à moitié construite — une équipe sans
   grille, une grille sans stands — est sauvegardée comme les autres. Seule
   une édition qui ne contient rien de ce qu'un vidage efface ou qu'un import
   écrase (référentiel, planning, paramètres, poids, journées types) n'en
   reçoit pas. Un état identique à la dernière sauvegarde de l'édition n'est
   pas rangé deux fois.
2. **Pas de sauvegarde, pas de vidage.** La réinitialisation écrit la
   sauvegarde dans la transaction même qui vide
   les tables. L'import, fait de plusieurs écritures, l'écrit dans la sienne
   **avant la première** (les paramètres) : un échec l'arrête avant qu'il ait
   rien touché. Ce qui peut le refuser passe d'abord : le fichier est lu et
   validé, le gel vérifié, et la sauvegarde elle-même est refusée pendant une
   résolution, dans l'édition que vise le fichier. Ce qui peut encore le
   refuser après elle laisse cette sauvegarde, qui n'évince rien. C'est l'inverse de
   l'instantané automatique, qui ne fait jamais échouer une résolution : une
   résolution ne détruit rien qu'un instantané seul ramènerait, un vidage
   détruit tout.
3. **Aucune purge automatique.** Toutes les sauvegardes restent jusqu'à la
   suppression de leur édition (en cascade, donc à la purge annuelle) ou
   jusqu'à ce qu'un administrateur en supprime une
   (`DELETE /api/planning/sauvegardes-avant-vidage/{id}`, journalisé, limité
   à l'édition). Une borne de cinq, purgée à chaque vidage, a d'abord été
   retenue : trois relectures successives ont trouvé un geste qui lui faisait
   évincer la sauvegarde même qu'il fallait garder — la sauvegarde qu'on
   restaurait, puis l'état d'avant une restauration relancée, puis la
   sauvegarde visée par un retour en arrière. Chaque règle d'épargne en
   appelait une autre ; sans purge, la famille disparaît.
4. **La restauration est un seul geste**, `POST
   /api/planning/sauvegardes-avant-vidage/{id}/restauration`, dans cet ordre :
   1. les refus, avant toute écriture : résolution en cours, famille gelée,
      sauvegarde que l'édition ne détient pas, fichier que l'import refuserait
      — lecture, stands invalides, exceptions ad hoc contradictoires ;
   2. la sauvegarde de l'état actuel (origine `RESTORE`), qui nomme la
      sauvegarde restaurée et dira si la restauration a abouti. Si la dernière
      sauvegarde de l'édition est celle d'une restauration **échouée de la
      même sauvegarde**, on la réutilise au lieu de sauvegarder l'édition à
      moitié restaurée : elle reste le vrai état d'avant ;
   3. l'import du scénario **par l'import lui-même** (`ScenarioImportService`),
      qui recrée en plus, par la création habituelle, les stands et créneaux
      qu'aucun siège ne relie (l'import ordinaire les écarte et reste tel quel) ;
   4. le planning, retrouvé par clés métier (point 5) ; la restauration échoue
      si un planning non vide ne retrouve **aucun** siège ;
   5. les verrous, par l'écriture habituelle d'un verrou, ses contrôles et ses
      avertissements ; la sauvegarde de l'étape 2 est alors marquée « aboutie ».

   La liste (`GET …`, de la plus récente à la plus ancienne, avec l'origine,
   la date et, pour une restauration, la sauvegarde visée et son issue) et le
   fichier (`GET …/{id}`, journalisé comme l'export de scénario) restent, pour
   choisir et lire une sauvegarde avant de la restaurer.
5. **Le planning se retrouve par des clés qui ne bougent pas.** Le réimport
   donne de nouveaux identifiants (0050) : un créneau se retrouve par sa date
   et ses heures (la clé que les verrous stockent déjà), un animateur par son
   adresse puis par ses prénom et nom, un stand par son code puis par son nom,
   lus dans le scénario de la même sauvegarde et normalisés comme l'import les
   compare (adresses et noms sans espaces autour ni casse, codes tels
   qu'écrits). Une clé ne désigne que si elle nomme exactement une ligne de la
   sauvegarde et une ligne compatible de l'édition ; sinon — rien, ou une
   ambiguïté comme l'adresse partagée d'une famille — on essaie la suivante.
   Le nom, la plus faible, ne désigne rien s'il est ambigu d'un côté ou de
   l'autre, et jamais un homonyme dont l'adresse est une autre (la règle
   `samePerson` de l'import). Une ligne que deux entrées désignent ne va à
   aucune. Ce qui n'est pas retrouvé est listé, siège par siège et verrou par
   verrou, par identifiants : le siège revient vide (animateur) ou pas du tout
   (stand, créneau). Comme le référentiel vient de la même sauvegarde que le
   planning, un manque est une anomalie, et la réponse la dit.
   **Aucun siège n'est refusé pour une règle dure** — aucune écriture de siège
   de l'application ne le fait ; la réponse donne le score du plan restauré,
   lu après les verrous.
6. **Aucun outil MCP** : le contenu porte nom, prénom et date de naissance des
   animateurs, que rien ne fait sortir par MCP.
7. **La table suit son édition** (`ON DELETE CASCADE`) et **ne part pas dans
   l'export SQL** : elle répète, une fois par vidage, des données que le dump
   porte déjà.

## La restauration n'est pas atomique

Le dépôt n'a pas de transaction qui enjambe plusieurs services : une
transaction est un seul `write` de `JdbcEditionScope`, et l'ajout d'une
transaction ambiante a été écarté (la méthode qui prend une `Connection`
rejoint la transaction de l'appelant, celle qui n'en prend pas valide seule —
c'est écrit dans sa signature). L'import enchaîne une dizaine de services qui
lisent et écrivent chacun sur leur connexion ; le rendre atomique demanderait
de passer une connexion à chaque lecture et écriture du chemin, ou de revenir
sur ce choix. Le geste est donc **ordonné, pas atomique**, et la reprise est
prévue :

- un refus (étape 1) n'écrit rien ;
- un échec à l'étape 2 n'a rien vidé ;
- un échec plus loin laisse l'édition à moitié restaurée. La réponse d'erreur
  dit **à quelle étape** l'opération s'est arrêtée et **quelle sauvegarde**
  porte l'état d'avant **la première tentative** : la restaurer remet
  l'édition comme elle était, aux identifiants techniques près. Une erreur
  métier répond `409`, une panne `500` avec le même message au journal ;
- relancer la même restauration après un échec réutilise cette sauvegarde-là
  et la nomme encore, quel que soit le nombre de tentatives ; la lancer deux
  fois de suite avec succès sauvegarde chaque fois l'état remplacé. Aucune
  sauvegarde ne disparaît en chemin.

## Alternatives écartées

- **Une transaction unique**, par une connexion ambiante ou passée à chaque
  lecture et écriture de l'import : voir la section précédente. Le coût est
  celui d'un chantier sur tout le référentiel, pour un geste rare dont la
  reprise est assurée autrement.
- **Restaurer en plusieurs gestes** (télécharger, réimporter, puis restaurer le
  planning), première forme de cette décision : le réimport est un vidage, qui
  sauvegardait l'état courant et purgeait — parfois la sauvegarde même qu'on
  restaurait —, et le planning seul remplaçait le plan courant sans filet.
- **Une borne avec des règles d'épargne** (la sauvegarde restaurée, celle
  d'avant, celles dont le contenu est le fichier importé) : voir le point 3.
  Chaque règle couvrait le cas qui l'avait fait naître, pas le suivant.
- **Porter les affectations dans le scénario** (la section `postes` lit un
  `animateurId`, et l'ignore). L'import changerait de sens pour tous les
  fichiers, pour un cas de secours.
- **Restaurer par un instantané.** Il référence des identifiants que le
  réimport ne rend pas : sa restauration refuse dès qu'un seul manque.
- **Rejouer la correspondance de l'import** (`ScenarioIdRemap`) au lieu de
  clés métier : elle tire des identifiants neufs pour ce qu'elle ne trouve pas.
- **Refuser chaque siège qui casse une règle dure.** Il faudrait un contrôle
  par siège que ni le déplacement ni la restauration d'un instantané ne font ;
  le score dit ce qu'il en est, le solveur répare.

## Conséquences

- Une restauration rend stands, créneaux (y compris ceux qu'aucun siège ne
  relie), animateurs, typologies, emplacements, paramètres, journées types,
  contraintes ad hoc, consignes, sièges et verrous ; **pas les validations de
  journée ni les demandes d'échange** : une validation est une relecture d'un
  plan qu'il faut relire à nouveau, une demande vit avec ses destinataires et
  ses jetons. Un stand ou un créneau que la création refuse est listé.
- **Les identifiants techniques changent** : les stands recréés et les
  créneaux reçoivent des identifiants neufs, les animateurs aussi quand
  l'édition avait été vidée. Ce qui les nommait hors de la sauvegarde ne les
  retrouve plus : un instantané dont la restauration refusera, faute de
  référence ; une validation de journée et une demande d'échange, déjà
  emportées par le vidage ; une ligne du journal des actions, qui garde
  l'ancien identifiant et ne nomme plus personne.
- L'import ordinaire d'un fichier de sauvegarde, fait à la main, reste
  possible mais n'est plus la procédure : il écarte les stands et créneaux sans
  siège et ne remet pas le planning.
- Deux stands de même nom sans code, ou deux animateurs de mêmes prénom et
  nom que l'adresse ne départage pas, ne se retrouvent pas : leurs sièges sont
  listés comme non restaurés.
- **Rejouer un export SQL complet efface toutes les sauvegardes avant
  vidage** : l'export ne les porte pas, et l'import supprime chaque édition,
  donc en cascade ses sauvegardes. Rien n'est sauvegardé avant lui ; un dump
  `pg_dump` restauré les ramène telles qu'il les avait
  ([`exploitation.md`](../exploitation.md) § Restaurer).
- Une édition sans créneau est exportée avec `festival.dateDebut: 1970-01-01`,
  que le format exige et que rien ne lit sans créneau.
- Chaque vidage coûte un export de scénario et une lecture du plan, faits sur
  d'autres connexions que la transaction du vidage, en lecture validée : une
  écriture concurrente entre la lecture et le vidage n'y figurerait pas. Le
  solveur, seul écrivain en arrière-plan, est déjà écarté par le refus pendant
  une résolution ; reste deux administrateurs simultanés.
- **Contrepartie de l'absence de purge** : des copies des données
  personnelles des animateurs — une par vidage — sont conservées aussi
  longtemps que l'édition. La purge annuelle (suppression de l'édition) les
  emporte ; une demande d'effacement sur une édition encore en cours doit en
  revanche supprimer, une à une, les sauvegardes qui portent la personne
  ([`rgpd.md`](../rgpd.md) §7). La table grossit d'une sauvegarde par vidage :
  quelques centaines de kilooctets à un mégaoctet chacune, selon la taille du
  planning (le plan pèse comme un instantané, le scénario en sus) ; supprimer
  celles qui ne servent plus est un geste d'administrateur.
