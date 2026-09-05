# 0023 — Détecter une modification concurrente par horodatage, sans verrou

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : référentiel, API, IHM, MCP

## Contexte

Un seul compte administrateur, partagé ([#294](https://github.com/sylvainmetayer/planning-equipes/issues/294)
n'existe pas), et plusieurs façons d'ouvrir la même fiche en même temps : deux
onglets, deux personnes, un assistant MCP pendant qu'un écran est ouvert. Rien
ne détectait le cas ([#362](https://github.com/sylvainmetayer/planning-equipes/issues/362)) :
la seconde sauvegarde écrasait la première, sans un mot. `ReferenceDataChangeTracker`
existait, mais il ne sait dire que « le référentiel a bougé depuis le dernier
solve », pas « cette fiche a bougé depuis que vous l'avez ouverte ».

## Options envisagées

**(A) Un verrou pessimiste** — la fiche s'ouvre en édition pour une session et
se ferme aux autres. Écarté : ~150 fiches, une poignée d'administrateurs, un
compte partagé qui rend les sessions indiscernables, et le verrou d'un onglet
oublié à rendre par une intervention. Coût disproportionné, dit l'issue.

**(B) Un compteur de version en mémoire**, dans `ReferenceDataChangeTracker`.
Écarté : il ne survit pas à un redémarrage — la première sauvegarde après un
déploiement passerait toujours — et il ne se compare à rien qu'un client
puisse relire.

**(C) Un horodatage persisté par ligne, renvoyé par le formulaire.** Chaque
table du référentiel porte `modifie_le` ; le `GET` le sert ; le `PUT` le
renvoie ; le service compare et refuse (`409`) quand la ligne a bougé. Un
en-tête `If-Match`/`412` aurait été la forme HTTP canonique ; le champ dans le
corps a été préféré parce que l'édition en masse envoie un `PUT` par ligne à
partir des lignes du store, qui portent déjà la valeur, et parce que les outils
MCP passent des arguments, pas des en-têtes.

## Décision

(C). Six règles, et l'ordre compte :

1. **La colonne est bumpée par toute écriture du référentiel** — service,
   import, édition en masse, MCP — via `modifie_le = now()` dans l'`UPSERT`
   lui-même, jamais par le code appelant, et rendue par `RETURNING` pour que
   l'entité renvoyée porte la valeur écrite.
2. **La persistance d'un solve ne la touche pas.** Elle réécrit le
   référentiel tel qu'il était au départ du calcul ; rien n'a changé, et la
   bumper ferait entrer en conflit toute fiche ouverte après chaque résolution.
3. **Absent, pas de contrôle.** Un `modifieLe` nul ou omis écrit sans
   comparer : c'est le mot de l'import, du script, de l'ancien client et de
   l'écrasement délibéré. Le contrôle ne protège que qui a chargé une fiche.
4. **Le refus est un `409` typé** — `BusinessError.Stale`, corps
   `{ message, code: "MODIFICATION_CONCURRENTE", modifieLe }` — parce que le
   client y répond par un choix et non par un bandeau, et qu'un `409` sans
   code se confond avec celui du solveur occupé.
5. **L'IHM propose deux issues et aucune par défaut** : *Recharger* (rien
   n'est écrit, le store est rafraîchi, le formulaire se ferme sur la version
   de l'autre session) ou *Écraser quand même* (le même payload repart sans
   `modifieLe`). Un formulaire qui resterait ouvert avec sa précondition
   périmée reboucle sur le même refus ; il se ferme.
6. **La comparaison se fait à la milliseconde.** La base garde la
   microseconde, un client passé par un `Date` non ; deux écritures de la même
   ligne dans la même milliseconde ne valent pas un faux conflit.

## Conséquences

- Six colonnes de plus (V67), un champ `modifieLe` sur cinq objets du domaine
  et sur `TypologieItem`, six lignes du contrat JSON déplacées délibérément.
- Le contrôle est **par ligne**, jamais par lot : l'édition en masse refuse la
  seule ligne modifiée ailleurs et écrit les autres, le compte rendu la nomme.
- Ce n'est pas un verrou : la seconde session est prévenue, elle n'est pas
  empêchée. Le jour où [#294](https://github.com/sylvainmetayer/planning-equipes/issues/294)
  distinguera les sessions, le message pourra dire *qui*, avec la même colonne.
- La résolution champ à champ reste hors périmètre : l'utilisateur recharge et
  ressaisit ce qu'il voulait, ce que l'issue posait comme limite.
