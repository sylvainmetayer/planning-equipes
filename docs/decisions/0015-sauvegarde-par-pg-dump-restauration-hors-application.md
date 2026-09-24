# 0015 — Sauvegarder par `pg_dump`, restaurer hors de l'application

- **Statut** : accepté
- **Date** : août 2026
- **Portée** : service, API, image Docker, exploitation

## Contexte

L'application savait déjà produire un dump : l'écran *Paramètres* exporte un
script SQL de toutes les tables métier, et sait le rejouer. C'est un outil
d'analyse — déplacer un jeu de données bloquant d'une instance à l'autre — et il
a toujours eu deux propriétés qui l'empêchent d'être une stratégie de
sauvegarde : le fichier passe par le navigateur d'un administrateur, et il faut
que l'application soit debout pour l'obtenir comme pour le rejouer.

Or ce dont il faut se protéger est précisément l'instant où quelque chose vient
d'être effacé : un import qui écrase l'édition en cours la veille de
l'événement, une suppression en masse depuis un écran de référentiel. La
documentation d'exploitation prescrivait déjà un `pg_dump` quotidien à la
main — c'est-à-dire une sauvegarde qui existe si quelqu'un y a pensé.

## Options envisagées

| Option | Ce qu'elle coûte |
| --- | --- |
| Planifier l'export SQL applicatif existant | Le fichier ne porte **que les données**, sans schéma ni historique Flyway : il ne se restaure que sur une base déjà migrée, par une application debout. Il n'inclut pas non plus les tables hors métier. La sauvegarde dépendrait de l'objet qu'elle protège |
| Laisser la sauvegarde à l'hébergeur (cron, snapshot de volume) | Rien à écrire, mais rien à vérifier non plus : l'écran ne peut pas dire si la sauvegarde tourne encore, ce qui est la seule question qu'on lui pose |
| Lancer un vrai `pg_dump` depuis l'application | Une dépendance de plus dans l'image runtime — le client PostgreSQL, dont la version doit suivre celle du serveur |

## Décision

L'application lance **`pg_dump --format=custom`** chaque nuit à 4 h, dans un
répertoire désigné par `BACKUP_DIR`, et ne conserve que les
`BACKUP_RETENTION` copies les plus récentes (10 par défaut, bornes 1 et 30).

Le binaire vient du dépôt PGDG dans l'image applicative, en version 18 : un
`pg_dump` plus ancien que le serveur refuse de tourner, et cette version-là est
donc à faire évoluer avec l'image `postgres:` de la pile de production.

**La restauration reste hors de l'application.** Elle se fait sur la base, par
l'exploitant, avec `pg_restore`. Ce n'est pas une lacune à combler plus tard :
un bouton « restaurer tout » dans une interface web serait exactement le geste
que cette sauvegarde existe pour rattraper. Pour la même raison, les fichiers ne
sont pas téléchargeables depuis l'écran — ils portent les noms, dates de
naissance et adresses de tous les animateurs, mineurs compris.

## Conséquences

- Le format `custom` est **auto-suffisant** : schéma et données, restaurables
  sans que l'application ait jamais démarré. C'est ce que le premier choix ne
  savait pas offrir, et c'est ce qui fait la différence le jour où le conteneur
  applicatif ne repart pas ;
- l'image ne pouvait plus dire qu'elle n'écrit rien sur le disque. Le répertoire
  de sauvegarde est créé dans l'image, appartenant à l'utilisateur applicatif,
  pour qu'un volume monté là en reprenne les droits ;
- l'emplacement et la rétention sont des **variables d'environnement**, pas des
  réglages d'écran : un chemin de disque et le nombre de copies qu'un volume
  porte se décident avec ce volume. Ce que l'écran écrit est un seul booléen —
  celui qui **suspend** la sauvegarde. Il ne la met pas en service : c'est
  `BACKUP_DIR` qui le fait, et sans elle, rien n'est planifié ;
- une rétention hors bornes **refuse le démarrage** plutôt que d'être rognée en
  silence : une valeur qu'on croit à 30 et qui vaut 10 ne se découvre que le
  jour où l'on cherche le dump d'il y a trois semaines ;
- un échec ne se propage pas — il serait avalé par l'ordonnanceur — mais est
  **enregistré et affiché**. « La sauvegarde ne tourne plus depuis trois
  semaines » devient une phrase que l'écran dit, au lieu d'une ligne de journal
  que personne ne lit ;
- l'export SQL de l'écran *Paramètres* reste ce qu'il a toujours été : un outil
  d'analyse et de transfert entre instances, pas une sauvegarde.
- la restauration hors de l'application a reçu depuis un **script guidé**
  exécuté sur l'hôte (`scripts/restaurer.sh`, voir `docs/exploitation.md` § 5) :
  il automatise les étapes sans ouvrir aucune route, ce qui laisse cette
  décision intacte.
