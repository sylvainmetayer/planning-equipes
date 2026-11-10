# 0084 — Sauvegarder l'édition (scénario et planning) avant de la vider, dans la transaction du vidage

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

1. **Avant chacun des deux gestes, l'export de scénario de l'édition est rangé
   dans `sauvegarde_avant_vidage`** (édition, origine `RESET` ou `IMPORT`,
   date, contenu YAML), **avec son planning** (colonne `plan` : les sièges,
   au format des instantanés, et les verrous, sous les identifiants de
   l'édition à cet instant). Une édition à moitié construite — une équipe
   importée sans grille, des stands sans animateurs — est sauvegardée comme
   les autres : le format relit une section vide. Seule une édition vide
   (aucune ligne dans les tables qu'un vidage efface) n'a rien à perdre et
   n'en reçoit pas. Un état identique à la dernière sauvegarde de l'édition
   n'est pas rangé deux fois : un geste réessayé ne chasse pas la sauvegarde
   utile.
2. **Pas de sauvegarde, pas de vidage.** La réinitialisation écrit la
   sauvegarde et purge les plus anciennes dans la transaction même qui vide
   les tables. L'import, fait de plusieurs écritures, l'écrit dans la sienne
   **avant la première** (les paramètres) : un échec l'arrête avant qu'il ait
   rien touché. Ce qui peut le refuser passe d'abord : le fichier est lu et
   validé, le gel vérifié, et la sauvegarde elle-même est refusée pendant une
   résolution, dans l'édition que vise le fichier. Ce qui peut encore le
   refuser après elle (une contradiction, un contrôle de stand) laisse cette
   sauvegarde — l'état d'avant, utile si les paramètres ont déjà été écrits —
   mais **n'évince rien** : la borne n'est appliquée qu'une fois l'import
   passé, et un réessai du même import ne range rien de plus. C'est l'inverse
   de l'instantané automatique, qui ne fait jamais échouer une résolution : une
   résolution ne détruit rien qu'un instantané seul ramènerait, un vidage
   détruit tout.
3. **Cinq sauvegardes par édition**, les plus anciennes purgées après chaque vidage effectif —
   la borne des instantanés automatiques : de quoi couvrir un après-midi
   d'erreurs, pas une archive. Une constante, pas un réglage.
4. **La restauration se fait en deux temps.** Le référentiel revient par un
   import : la liste (`GET /api/planning/sauvegardes-avant-vidage`) et le
   fichier (`…/{id}`, journalisé comme l'export de scénario), réimporté par
   Fichiers › Importer. Le planning revient ensuite par
   `POST …/{id}/planning`, qui remplace le plan persisté.
5. **Le planning se retrouve par des clés qui ne bougent pas.** Le réimport
   donne de nouveaux identifiants (0050) : un créneau se retrouve par sa date
   et ses heures (la clé que les verrous stockent déjà), un animateur par son
   adresse puis par ses prénom et nom (la règle de l'import), un stand par son
   code puis par son nom, lus dans le scénario de la même sauvegarde et
   comparés comme l'import les compare (sans espaces autour, sans casse). La
   correspondance est **prudente et injective** : une clé qui désigne
   plusieurs lignes de la sauvegarde ou de l'édition est une ambiguïté et
   interdit le repli sur la clé suivante — deux homonymes, l'un parti, et le
   nom donnerait ses sièges à l'autre ; une ligne de l'édition que deux
   entrées de la sauvegarde désignent ne va à aucune. Ce qui n'est pas
   retrouvé ne revient pas : le siège revient vide (animateur) ou pas du tout
   (stand, créneau), et la réponse le dit, siège par siège, par identifiants.
   Les verrous passent par l'écriture habituelle d'un verrou, ses contrôles et
   ses avertissements ; un verrou dont la cible est déjà verrouillée est
   compté à part, jamais comme restauré. **Aucun siège n'est
   refusé pour une règle dure** — aucune écriture de siège de l'application ne
   le fait ; la réponse donne le score du plan restauré.
6. **Aucun outil MCP** : le contenu porte nom, prénom et date de naissance des
   animateurs, que rien ne fait sortir par MCP.
7. **La table suit son édition** (`ON DELETE CASCADE`) et **ne part pas dans
   l'export SQL** : elle répète, jusqu'à cinq fois, des données que le dump
   porte déjà.

## Alternatives écartées

- **Restaurer d'un geste dans l'application** (« revenir avant le vidage »).
  Ce serait un import de plus, avec ses refus (gel, résolution en cours) et son
  journal, pour un geste rare ; télécharger puis importer suffit et réutilise
  le chemin existant.
- **Porter les affectations dans le scénario** (la section `postes` lit un
  `animateurId`, et l'ignore). L'import changerait de sens pour tous les
  fichiers, pour un cas de secours.
- **Restaurer par un instantané.** Il référence des identifiants que le
  réimport ne rend pas : sa restauration refuse dès qu'un seul manque.
- **Rejouer la correspondance de l'import** (`ScenarioIdRemap`) au lieu de
  clés métier : elle tire des identifiants neufs pour ce qu'elle ne trouve pas,
  et, dans une édition vidée, ne retrouve rien — un stand sans code n'y a
  d'autre identité que son nom.
- **Refuser chaque siège qui casse une règle dure.** Il faudrait un contrôle
  par siège que ni le déplacement ni la restauration d'un instantané ne font ;
  le score dit ce qu'il en est, le solveur répare.
- **Capturer dans l'import la seule transaction de `importFromPlanning`.** Les
  paramètres sont écrits avant elle : la sauvegarde aurait porté ceux du
  fichier, pas ceux de l'édition.

## Conséquences

- Réimporter une sauvegarde puis restaurer son planning rend stands,
  créneaux, animateurs, typologies, emplacements, paramètres, journées types,
  contraintes ad hoc, consignes, sièges et verrous ; **pas les validations de
  journée ni les demandes d'échange** : une validation est une relecture d'un
  plan qu'il faut relire à nouveau, une demande vit avec ses destinataires et
  ses jetons.
- Deux stands de même nom sans code, ou deux animateurs de mêmes prénom et
  nom dont l'adresse ne suffit pas, ne se retrouvent pas : leurs sièges sont
  listés comme non restaurés.
- **Rejouer un export SQL complet efface toutes les sauvegardes avant
  vidage** : l'export ne les porte pas, et l'import supprime chaque édition,
  donc en cascade ses sauvegardes. Rien n'est sauvegardé avant lui ; un dump
  `pg_dump` restauré les ramène telles qu'il les avait
  ([`exploitation.md`](../exploitation.md) § Restaurer).
- Chaque vidage coûte un export de scénario et une lecture du plan, faits sur
  d'autres connexions que la transaction du vidage, en lecture validée : une
  écriture concurrente entre la lecture et le vidage n'y figurerait pas. Le
  solveur, seul écrivain en arrière-plan, est déjà écarté par le refus pendant
  une résolution ; reste deux administrateurs simultanés.
- **RGPD** : la purge annuelle (suppression de l'édition) emporte les
  sauvegardes. Une demande d'effacement sur une édition encore en cours doit en
  revanche les supprimer aussi, à la main ([`rgpd.md`](../rgpd.md) §7).
