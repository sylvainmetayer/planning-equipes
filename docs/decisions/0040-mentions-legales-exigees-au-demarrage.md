# 0040 — Exiger les mentions légales au démarrage, pas dans une case à cocher

- **Statut** : accepté
- **Date** : septembre 2026
- **Portée** : configuration, exploitation, conformité

## Contexte

`/api/mentions-legales` est **publique** et le reste par construction : c'est
la page que doit pouvoir lire quelqu'un qui n'est pas connecté, ou un animateur
dont le lien a expiré — exactement le lecteur qui a besoin de savoir à qui
écrire.

Les sept variables `LEGAL_*` étaient toutes facultatives, vides par défaut, et
la page annonce alors elle-même qu'elle n'a rien à dire. Sur une instance en
service, c'est une page de mentions légales **absente** : la LCEN veut un
éditeur et un hébergeur identifiés, le RGPD une base légale, une durée de
conservation et une adresse pour exercer ses droits.

L'obligation était déjà écrite — `securite.md` la liste dans « Avant d'ouvrir :
la liste courte », `exploitation.md` §3 s'intitule « à renseigner, pas à
laisser vides ». Rien ne la tenait.

Et l'asymétrie sautait aux yeux une fois vue : `DefaultSecrets` **refuse le
démarrage** d'un déploiement resté sur le mot de passe d'exemple, pendant que
la page publique pouvait sortir vide sans un mot dans les journaux. Une
checklist que personne ne coche ne protège de rien, et c'est le seul de ses
points qu'une vérification au démarrage peut tenir.

## Options envisagées

| Option | Ce qu'elle coûte |
| --- | --- |
| Laisser la case à cocher | L'état actuel : l'engagement est écrit trois fois et rien ne l'applique. C'est précisément ce qui a produit l'écart |
| Avertir au démarrage (`Log.warn`) | Un avertissement dans un journal que personne ne relit après un déploiement réussi. Le même défaut que la case, avec une ligne de plus |
| Exiger les sept variables | Casse le repli documenté du responsable de traitement sur l'éditeur, et exige un directeur de publication de tout éditeur — ce que la LCEN ne fait pas |
| Déduire « instance en service » de `PUBLIC_URL` ou du mode de lancement | Plus court, et faux : une instance de démonstration a elle aussi une URL publique et tourne elle aussi en mode production |

## Décision

**Cinq des sept variables font échouer le démarrage** en production quand elles
sont vides ou blanches : `LEGAL_EDITEUR`, `LEGAL_HEBERGEUR`, `LEGAL_CONTACT`,
`LEGAL_BASE_LEGALE`, `LEGAL_CONSERVATION`. La vérification est lue comme
`DefaultSecrets` — un contrôle au démarrage, pas une validation sur le mapping
de configuration : ce qui compte n'est pas la forme d'une valeur mais que
quelqu'un l'ait écrite.

**Les deux autres restent facultatives, et pas par oubli.**
`LEGAL_RESPONSABLE_TRAITEMENT` vide **retombe sur l'éditeur** par conception ;
l'exiger séparément contredirait son propre repli. Un directeur de publication
n'est pas exigé de tout éditeur.

**L'échappatoire est explicite** : `LEGAL_DEMO_INSTANCE=true` déclare une
instance qui n'est pas en service, qui démarre alors avec une page vide. Ce
qu'une instance est *faite pour* ne se lit nulle part ailleurs dans sa
configuration — c'est une décision d'exploitant, elle s'écrit.

Le message d'échec **nomme toutes les variables manquantes d'un coup**, et
nomme l'échappatoire. Les corriger une par une coûterait cinq redémarrages, et
un message qui ne dit pas comment sortir se lit comme une impasse.

## Conséquences

- **Une instance déjà en service qui n'a jamais renseigné ces variables ne
  redémarrera pas** à la montée de version. C'est le comportement voulu —
  l'obligation existait, rien ne la tenait — mais il se prépare avant la
  fenêtre de mise à jour. `exploitation.md` §3 le dit à cet endroit.
- Le contrôle ne tourne **qu'en production** : dev et tests gardent les défauts
  vides, comme ils gardent les deux mots de passe d'exemple, et pour la même
  raison — c'est ce qui fait marcher un premier `quarkus:dev` sans réglage.
- Il vérifie qu'un texte **existe**, jamais qu'il est exact. Un éditeur
  fantaisiste passe. C'est la limite de tout contrôle automatique ici, et la
  raison pour laquelle `exploitation.md` garde sa vérification par `curl` après
  déploiement.
- Ce que ce contrôle **ne couvre pas** reste entier : la durée annoncée par
  `LEGAL_CONSERVATION` est désormais garantie *présente*, pas *tenue*. Son
  application est manuelle, et le reste — voir
  [0016](0016-purge-manuelle-avant-automatisation.md). Exiger la variable rend
  d'ailleurs l'écart plus visible : on promet maintenant à coup sûr quelque
  chose que rien n'applique.
