# 0104 — Les horaires d'un stand sont une liste de plages datées

- **Statut** : accepté, implémenté (modèle, migration, format YAML) · l'API, les outils MCP et l'écran suivent dans leurs propres changements
- **Date** : novembre 2026
- **Portée** : modèle du stand, résolution des horaires, migration de la base, format du scénario YAML (version 3), sauvegardes avant vidage

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
   (`OUVERTURE` ou `FERMETURE`), un effectif pour une ouverture, un motif. Il
   n'existe plus de règle stockée. Une récurrence est un **générateur** : elle
   écrit une plage par jour concerné (l'éditeur de plages en est le lieu, dans
   un changement à part).
2. **La résolution ne lit que des plages.** Un jour sans plage est ouvert toute
   la journée ; un jour qui porte une ouverture n'est ouvert que sur elles ; un
   jour qui ne porte que des fermetures est ouvert hors d'elles ; un jour ne
   porte jamais les deux modes. La consigne reste posée au-dessus, comme avant
   (sa réécriture en plages est un changement à part).
3. **Le stockage ne bouge pas** : les plages vivent dans les deux tables
   `stand_ouverture` et `stand_indisponibilite`, qui portaient déjà exactement
   cela. La migration `V141` ne copie donc aucune plage existante ; elle ne fait
   que **convertir les règles** en plages et renommer les deux tables de règles
   (`stand_horaire_avant_4_0`, `stand_horaire_fenetre_avant_4_0`), supprimées en
   5.0.
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
   sont **conservées à part**, dans les tables renommées, et un avertissement
   de migration nomme l'édition. Elles ne sont jamais perdues en silence. Le
   guide de passage demande en outre un export YAML de chaque édition avant la
   montée : relu en 4.0, il reconvertit les règles dès que l'édition a des
   créneaux.
6. **Le format YAML passe en version 3** : la clé `plages` remplace
   `horaires`, `ouvertures` et `indisponibilites` d'un stand. Le maillon 2 → 3
   convertit un fichier plus ancien de la même façon, sur les jours des créneaux
   du fichier, et avertit (rapport d'import) d'un stand dont les règles n'ont
   pas de jour ([0101](0101-schema-yaml-2-et-chaine-de-conversions.md)).

## Ce que ça coûte

- **Un jour ajouté après coup** (un créneau sur une date que l'événement ne
  comptait pas) n'a plus de règle pour le gouverner : le stand y est ouvert toute
  la journée, même s'il « n'ouvrait que le week-end ». C'est le prix de ne plus
  stocker de règle ; le générateur de l'éditeur est fait pour le payer d'un
  clic.
- **Un horaire quotidien sur une longue édition pèse davantage en base** : une
  ligne par jour et par fenêtre au lieu d'une règle.
- **La migration ne redescend pas.** Retour : l'export YAML d'avant la montée,
  relu par la 3.x, ou la sauvegarde de la base.

## Alternatives écartées

- **Garder les règles et ajouter les plages** : c'est l'état d'avant, avec un
  modèle de plus. Les trois couches, et les analyses qui les arbitrent,
  restent.
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
