# 0016 — Purger à la main d'abord, automatiser quand la charge le justifie

- **Statut** : accepté — prolonge [0015](0015-sauvegarde-par-pg-dump-restauration-hors-application.md)
- **Date** : août 2026
- **Portée** : exploitation, base de données, service

## Contexte

`LEGAL_CONSERVATION` est une **chaîne affichée** : la durée de conservation
apparaît dans la politique de confidentialité, et **rien dans le code ne
l'applique**. Ni les fiches animateurs, ni les instantanés de plan, ni les
journaux de jobs ne disparaissent à l'échéance.

Ce n'est pas un défaut cosmétique : une durée annoncée est un **engagement
écrit, opposable**. Ne rien annoncer serait moins exposant que d'annoncer une
durée qu'on n'applique pas.

[0015](0015-sauvegarde-par-pg-dump-restauration-hors-application.md) a
automatisé la **sauvegarde** ; elle n'automatise pas la **suppression**, et les
deux ne se ressemblent qu'en surface : une sauvegarde oubliée se rattrape à la
sauvegarde suivante, une suppression de trop ne se rattrape pas.

La suppression, elle, existe déjà et fonctionne : supprimer une édition emporte
en cascade les tables qui portent `edition_id` — animateurs, plannings,
instantanés et jetons partent avec. Ce qui manque n'est pas le mécanisme, c'est
**son déclenchement à date**.

## Options envisagées

| Option | Ce qu'elle coûte |
| --- | --- |
| Écrire la tâche planifiée tout de suite | Une suppression automatique et irréversible de données personnelles, écrite avant d'avoir vu une seule échéance réelle passer. Le premier bug ne se rattrape pas : il n'y a rien à restaurer qu'un dump |
| Ne rien faire et retirer la durée annoncée de la page | Enlève l'engagement au lieu de le tenir — et le RGPD demande une durée, l'absence n'est pas une réponse |
| Procédure manuelle écrite, automatisation ensuite | Dépend d'un humain qui l'exécute à date fixe, et donc d'un rappel |

## Décision

**La purge est manuelle d'abord**, décrite pas à pas dans
[`exploitation.md`](../exploitation.md) §6 : dump chiffré archivé, suppression
des éditions échues depuis l'écran *Éditions*, vérification qu'un jeton
supprimé répond bien `404`, inscription au journal du registre
([`rgpd.md`](../rgpd.md) §5).

L'automatisation vient **quand plusieurs instances tournent en parallèle** —
c'est le nombre de clients, pas le calendrier, qui la rend nécessaire : à une
instance, une procédure annuelle tient ; à trois, l'oubli devient la règle.

Ce qu'elle devra porter, à ce moment-là :

- une **tâche planifiée** qui supprime les éditions dont la durée est échue ;
- un **journal de ce qui a été purgé**, alimenté par la tâche et repris au
  registre — une purge automatique sans trace serait un recul par rapport à la
  procédure manuelle, qui, elle, en laisse une ;
- un **test qui prouve que la cascade n'oublie aucune table** portant
  `edition_id`. C'est la garantie que l'automatisation ne peut pas laisser
  derrière elle des données orphelines qu'aucun écran ne montre plus.

## Conséquences

- La durée annoncée n'est tenue **que si quelqu'un exécute la procédure**. Tant
  que c'est le cas, l'écart est documenté comme tel dans `rgpd.md` §7 : le
  registre décrit les mesures, il ne prétend pas qu'elles sont automatiques.
- Le dump pris avant chaque purge est ce qui rend la suppression rattrapable.
  Il devra le rester après automatisation : une tâche qui supprime sans
  sauvegarde préalable serait plus risquée que l'oubli qu'elle corrige.
- **La sauvegarde nocturne de [0015](0015-sauvegarde-par-pg-dump-restauration-hors-application.md)
  ne tient pas ce rôle.** Elle tourne en rotation — les `BACKUP_RETENTION`
  copies les plus récentes — donc l'état d'avant une purge en sort au bout de
  quelques jours, précisément quand on viendrait le chercher. L'archive de
  conservation est un dump **sorti de la rotation** : renommé, chiffré, rangé
  hors machine. La rotation ne touche jamais un fichier qui ne porte pas son
  nom de série, ce qui rend l'opération sûre — mais elle reste à faire.
- Un cas reste **non couvert par la purge, manuelle comme automatique** :
  l'effacement demandé par une personne sur une édition **encore active**. La
  purge raisonne par édition échue ; une demande d'effacement ne se refuse pas
  au motif que l'événement n'est pas terminé, et n'a pas de délai de tolérance.
  Aujourd'hui c'est une suppression manuelle de la fiche. Automatiser la purge
  ne le règle pas — c'est une opération distincte, à outiller séparément.
