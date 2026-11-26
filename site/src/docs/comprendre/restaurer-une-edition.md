# Repartir d'une édition, revenir en arrière

Vous savez choisir entre scénario, duplication et modèle d'année pour démarrer une édition, retrouver et restaurer l'état d'une édition avant un import ou un vidage, et ce que fait (et ne fait pas) le dump SQL réservé aux tests.

## Trois façons de repartir d'une édition

| Geste | Ce que ça fait | Les personnes |
| --- | --- | --- |
| Scénario YAML | décrit un événement entier et remplace le référentiel de l'édition visée ([le fichier](scenario-yaml.md)) | oui, section `animateurs` |
| Duplication d'édition (`POST /api/editions/{id}/dupliquer`) | crée une nouvelle édition et y recopie tout le référentiel de la source : stands, emplacements, typologies, plages de stand, créneaux, dosages de contraintes, paramètres et préréglages de consigne. Ni le planning résolu ni les consignes datées : « 2026 = 2025 moins les affectations » | oui, par défaut |
| Modèle d'année (`…/dupliquer?avecAnimateurs=false`) | la même duplication, sans les personnes | non |

Il n'y a pas de bibliothèque de modèles à part : la liste des éditions en tient lieu, et appliquer un modèle, c'est dupliquer. Une table de gabarits parallèle serait un second référentiel redondant pour le même service.

Le mode modèle d'année laisse derrière lui `animateur` et tout ce qui pend à une personne (`animateur_competence`, `animateur_jour_indispo`, `animateur_souhait`), ainsi que les ajustements manuels, puisque chaque type de `contrainte_ad_hoc` nomme au moins un animateur : une copie pointant sur des personnes absentes serait pire que pas de copie. `stand_typologie` reste, car elle porte un stand et une typologie, jamais quelqu'un.

Le défaut ne change pas. Dupliquer avec les gens est le geste d'une édition « plan canicule » créée en cours de festival, qui doit garder son équipe et son envoi à tous. Décocher la case est le cas inverse, préparer 2027 depuis 2026, et là recopier noms, dates de naissance et courriels de personnes qui ne se sont pas réinscrites est un problème de minimisation et de durée de conservation, pas un confort ([ADR 0009](repo:docs/decisions/0009-edition-unique-porteur-de-variantes.md) pour le rôle de l'édition, [Une édition, une partition des données](cloisonnement-par-edition.md) pour le cloisonnement).

## Les sauvegardes avant vidage

Avant d'écrire quoi que ce soit, l'import d'un scénario, « Vider cette édition », `reinitialiser_donnees` et la restauration elle-même rangent dans `sauvegarde_avant_vidage` **un seul export YAML** de l'édition visée : son scénario, et en dernière section son planning (`plan:`, les sièges et les verrous) ([ADR 0084](repo:docs/decisions/0084-sauvegarder-l-edition-avant-de-la-vider.md), en partie remplacé par [l'ADR 0112](repo:docs/decisions/0112-la-copie-avant-vidage-est-un-export-yaml-avec-son-plan.md)). Si cette sauvegarde échoue, rien n'est vidé. Les sauvegardes prises par une version 3.x, dont le planning est resté dans une colonne à part, se lisent comme le même fichier et restent restaurables.

- Rien ne les purge : elles restent jusqu'à la suppression de l'édition, ou jusqu'à ce qu'un administrateur en supprime une (`DELETE /api/planning/sauvegardes-avant-vidage/{id}`).
- Une édition à moitié construite (une équipe sans grille, une grille sans stands) est sauvegardée comme les autres. Seule une édition qui ne contient rien (ni référentiel, ni planning, ni paramètres, poids ou journées types) n'en reçoit pas, et un état déjà sauvegardé en dernier ne l'est pas deux fois.
- Un import refusé pendant une résolution n'écrit rien, un import refusé plus loin peut laisser sa sauvegarde.
- Aucun écran ne les liste : elles ne s'atteignent que par l'API.

### Les retrouver et les restaurer

Avec l'en-tête `X-Edition-Id` :

1. `GET /api/planning/sauvegardes-avant-vidage` liste les sauvegardes de l'édition, la plus récente d'abord. Chaque ligne dit son origine (`RESET`, `IMPORT`, `RESTORE`) et sa date (`creeLe`). Une ligne `RESTORE` dit aussi quelle sauvegarde la restauration visait (`restaureId`, vide si celle-ci a été supprimée) et son issue (`restaurationAboutie` : `true` aboutie, `false` non aboutie ou inconnue) ; sa ligne est l'état juste avant cette restauration. Le fichier d'une sauvegarde se lit par `GET /api/planning/sauvegardes-avant-vidage/{id}`.
2. `POST /api/planning/sauvegardes-avant-vidage/{id}/restauration` la restaure.

La restauration est **l'import habituel du fichier de la sauvegarde**, précédé de la sauvegarde de l'état actuel :

1. les refus, avant toute écriture : résolution en cours, famille gelée, fichier que l'import refuserait (lecture, stands invalides, exceptions ad hoc contradictoires, nommées alors par les identifiants de la sauvegarde) ;
2. la sauvegarde de l'état actuel ;
3. l'import du fichier, qui recrée aussi les stands et créneaux qu'aucun siège ne relie, puis remet le planning de sa section `plan:` (les sièges, puis les verrous par l'écriture habituelle d'un verrou).

La réponse donne `sauvegardeAvant`, l'identifiant de la sauvegarde de l'état remplacé : restaurer celle-ci défait la restauration.

### Une opération qui n'est pas atomique

Le dépôt n'a pas de transaction qui enjambe l'import, fait de plusieurs services (voir l'ADR). Une restauration qui échoue après la sauvegarde de l'état actuel laisse l'édition à moitié restaurée. L'erreur nomme la sauvegarde de l'état juste avant cette tentative : `409` pour une erreur métier, `500` pour une panne, avec le même message au journal. D'autres refus de l'import, plus rares (une consigne, une journée type que le fichier décrit mal), peuvent tomber après l'écriture des paramètres : la reprise s'applique alors aussi.

Pour reprendre, restaurez la sauvegarde nommée par l'erreur, qui remet l'édition comme elle était, ou relancez la restauration une fois la cause levée. Chaque tentative sauvegarde l'édition qu'elle remplace (sauf état identique à la dernière sauvegarde), donc le travail fait entre deux tentatives n'est jamais perdu. L'erreur ne nomme que la dernière : pour revenir plus loin (avant une série de tentatives, par exemple), choisissez dans la liste d'après les dates, les origines, les sauvegardes visées et les issues. Des relances après un échec à mi-chemin écrivent une ligne par tentative, car chaque échec laisse un état différent (les créneaux recréés changent d'identifiant) ; elles s'accumulent et se nettoient par la suppression manuelle. Une restauration réussie dont l'issue n'a pas pu être notée est rapportée réussie avec un avertissement, sa ligne restant « non aboutie ».

### Comment le planning retrouve ses places

Le planning se retrouve sur ce qui ne change pas au réimport : un créneau par sa date et ses heures, un animateur par son adresse puis par ses prénom et nom, un stand par son code puis par son nom, tous normalisés comme l'import les compare. Une clé ne désigne que si elle nomme une seule ligne de chaque côté, sinon on essaie la suivante (une adresse partagée par une famille laisse le nom départager). Un nom ambigu ne désigne rien, pas plus qu'un homonyme dont l'adresse est une autre ; une ligne que deux entrées désignent ne va à aucune. Mieux vaut un siège vide signalé qu'un siège donné à quelqu'un d'autre.

La réponse compte les sièges pourvus, les verrous écrits et ceux déjà posés, donne le score du plan restauré (lu après les verrous) et les avertissements, et liste par identifiants ce qui n'a pas pu revenir. Un siège dont l'animateur est introuvable revient vide, un siège dont le stand ou le créneau l'est ne revient pas, un stand ou un créneau que la création refuse est signalé (`referentielNonRestaure`). Le référentiel et le planning viennent de la même sauvegarde, donc ces listes sont normalement vides : ce qui y figure est une anomalie. Si aucun siège d'un planning non vide ne revient, la restauration échoue et la reprise ci-dessus s'applique.

### Ce qui ne revient pas

- Les validations de journée et les demandes d'échange ne sont pas sauvegardées.
- Les identifiants techniques changent (stands recréés, créneaux, animateurs d'une édition vidée) : un instantané qui les nommait ne se restaure plus, et le journal des actions garde les anciens.

Réimporter soi-même le fichier d'une sauvegarde (Fichiers, Importer) ne remet que le scénario : l'import ignore la section `plan:` et le dit dans ses avertissements, parce qu'un planning posé par un fichier échapperait aux contrôles des autres écritures de sièges et de verrous. Seule la restauration remet le planning, et les stands et créneaux sans siège. Les noms, dates de naissance, adresses et téléphones des animateurs sont dans ce fichier : traitez-le comme la base ([durée de conservation](../installer/rgpd-limites-a-consigner.md#les-copies-avant-vidage)).

## Le dump SQL d'outillage de test

Aucun écran n'offre plus l'export SQL à l'opérateur. Sauvegarder la base, c'est le `pg_dump` de nuit ([ADR 0015](repo:docs/decisions/0015-sauvegarde-par-pg-dump-restauration-hors-application.md), [Sauvegarde et restauration](../installer/sauvegarde-et-restauration.md)), et échanger un jeu de données, c'est le [scénario YAML](scenario-yaml.md). Les routes `GET /api/database/export` et `POST /api/database/import` subsistent pour les suites de tests, qui amorcent et restaurent la base par elles. Elles répondent `404` sauf si `planning.outillage-test.dump-sql` vaut `true` (variable `OUTILLAGE_TEST_DUMP_SQL`, posée par les profils `%test` et `%dev` et par la pile E2E), et ne figurent pas dans la configuration d'exploitation. Le service est [`DatabaseDumpService`](repo:src/main/java/dev/sylvain/planning/service/export/DatabaseDumpService.java).

L'opération est globale à l'instance : toute la base, toutes éditions comprises. L'import rejoue le script en une transaction et n'accepte que `INSERT`, `DELETE` et `TRUNCATE` sur les tables métier. Les consignes d'édition (`consigne_edition`, `consigne_edition_fenetre`, `consigne_edition_ouverture`, `consigne_edition_creneau`) et leurs préréglages (`prereglage_consigne`, `prereglage_consigne_fenetre`) en font partie : elles changent les sièges qu'une résolution reçoit, et une restauration sans elles rebâtirait une journée nominale que l'organisateur avait fermée. Rejouer un export SQL complet efface toutes les sauvegardes avant vidage, de toutes les éditions.

### Un dump se rejoue sur le schéma où il a été pris

Un dump est une liste de lignes taillées par les migrations appliquées au moment de l'export : le script nomme les colonnes et écrit les valeurs comme le schéma les rangeait, sans conversion. Une migration qui renomme une colonne ou en change le type le rend illisible pour les versions suivantes. Son en-tête nomme donc le schéma de la base qui l'a produit, soit la dernière migration appliquée :

```sql
-- Planning Équipes database dump
-- Generated at 2026-10-02T08:00:00Z
-- Schema version: 122
-- Replay with the "Import SQL" admin action.
```

L'import refuse en `400`, avant d'écrire quoi que ce soit, un dump dont l'en-tête nomme un autre schéma que celui de la base, et le message donne les deux versions. Pour comparer, lisez `head -5 mon-dump.sql` d'un côté, et de l'autre la page Débogage, qui liste la dernière migration appliquée par chaque version, ou :

```sql
SELECT version FROM flyway_schema_history
WHERE success AND version IS NOT NULL
ORDER BY installed_rank DESC LIMIT 1
```

Un dump qui ne cible pas le même schéma se rejoue sur une instance au schéma du dump, qu'on met ensuite à jour avant d'en exporter un nouveau. Un script sans la ligne (exporté avant qu'elle existe, ou quelques instructions écrites à la main) est rejoué sans vérification : à l'opérateur de s'assurer qu'il date du même schéma. Pour garder un jeu de données au-delà d'une montée de version, c'est l'export de scénario YAML qui voyage.
