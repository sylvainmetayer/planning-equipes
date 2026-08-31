# 0020 — Avertir dans la réponse d'écriture, pas dans un contrôle à part

- **Statut** : accepté, implémenté
- **Date** : août 2026
- **Portée** : référentiel, API, IHM

## Contexte

Trois incohérences de saisie sont fréquentes et coûteuses, et rien ne les
signalait au moment où elles se font : une indisponibilité posée hors des dates
de l'événement (elle ne recouvre aucun créneau, donc elle ne protège personne),
une date de naissance qui rend l'animateur mineur pendant l'événement, un
créneau qu'aucun stand n'est ouvert à couvrir.

Aucune des trois n'est *certainement* insatisfiable, et la doctrine du dépôt —
posée par la [0010](0010-contraintes-ad-hoc-contradiction-plutot-que-budget.md)
— est de ne refuser que cela. Un organisateur a le droit de saisir les trois :
il prépare une grille, il corrige une date après coup, il connaît son terrain.
Ces trois cas appellent donc un avertissement, et un avertissement n'a pas de
place dans la hiérarchie `BusinessError`, qui porte un code HTTP de refus.

Restait à décider **où** l'avertissement se calcule, et **par où** il revient.

## Options envisagées

**(A) Le recalcul côté navigateur.** Le front connaît déjà les créneaux, les
animateurs et les stands : il peut refaire les trois règles en TypeScript au
moment où le formulaire se ferme.

**(B) Un point d'entrée de vérification séparé.** `POST /api/…/verification`,
appelé avant (ou après) l'écriture, répondant la liste des remarques.

**(C) L'avertissement dans la réponse d'écriture**, à côté de l'entité écrite.

## Décision

**(C)**.

**(A) duplique une règle métier dans un langage qui ne la teste pas.** Le
statut mineur est dérivé de `dateNaissance` à la date du créneau — c'est un
invariant du domaine, avec trois régimes légaux derrière — et le
réimplémenter en TypeScript, c'est accepter que les deux versions divergent
sans que rien ne le dise. L'amplitude d'ouverture d'un stand est pire encore :
elle suppose de développer les horaires récurrents, ce que le backend fait déjà
et que le front ne fait pas. Et un avertissement calculé dans le navigateur ne
profite ni à l'import ni aux outils MCP.

**(B) ajoute un aller-retour et une fenêtre d'incohérence.** Vérifier puis
écrire laisse un intervalle pendant lequel un autre onglet ajoute un créneau et
déplace les bornes ; écrire puis vérifier double la latence de chaque
enregistrement pour une information que le serveur tenait déjà. Le point
d'entrée serait aussi une deuxième porte à cloisonner, à authentifier et à
documenter, pour un service que l'écriture rend gratuitement.

**(C) tient parce que la réponse est le seul instant où le serveur sait à la
fois ce qui a été écrit et dans quel état est le reste de l'édition.** Une
seule source de vérité, testable en Java, disponible pour tout appelant.

## Conséquences

- **La forme des réponses d'écriture change.** `POST`/`PUT /api/animateurs`
  répond `{ animateur, avertissements }` et `POST`/`PUT /api/creneaux` répond
  `{ creneau, avertissements }` au lieu de l'entité nue. C'est une rupture pour
  un client qui lisait l'entité à la racine, et c'est pourquoi le contrat JSON
  gelé la rend bruyante. Les autres référentiels ne bougent pas : une clé
  `avertissements` absente veut dire « rien à signaler ».
- **Les bornes de l'édition se dérivent.** Une `Edition` ne porte ni dates ni
  drapeau « en cours » ; l'événement court du premier au dernier créneau, et
  une édition sans créneau n'a pas de bornes du tout — elle n'émet donc aucun
  avertissement. Inventer une borne là serait un faux positif sur l'écran par
  lequel une édition commence.
- **Un avertissement qui crie au loup ne sert plus à rien**, et c'est la
  contrainte de conception principale. Les horaires récurrents sont développés
  contre le créneau qu'on écrit avant toute comparaison ; une fermeture
  méridienne commune à tous les stands n'est pas signalée (c'est un horaire,
  pas une erreur) ; un débordement de moins d'un quart d'heure est ignoré.
- **Ce n'est pas une règle métier de plus.** Aucune contrainte Timefold n'est
  ajoutée ni modifiée : le solveur appliquait déjà le régime des jeunes
  travailleurs et ne plaçait déjà personne sur un stand fermé. L'avertissement
  ne fait que le dire à la saisie.
- **Un avertissement ne se lève que sur ce que l'écriture change.** Sur une
  modification, chaque règle est lue contre la fiche telle qu'elle était avant.
  L'édition en lot envoie la fiche entière fusionnée, une requête par ligne :
  sans cette comparaison, ajouter une compétence à trente bénévoles finirait sur
  un message citant chaque mineur de la sélection — c'est-à-dire sur le cri au
  loup que la décision cherche justement à éviter. Coût assumé : une lecture du
  référentiel des animateurs par modification.
- **Ce que ces messages ont le droit de dire est borné par le journal du
  navigateur.** L'IHM recopie toute bulle dans un journal `localStorage` qui
  survit à la déconnexion. Un avertissement nomme donc un animateur par son
  identifiant seul, jamais par son identité ni par sa date de naissance ; et la
  phrase disant qu'une personne est mineure est affichée sans être journalisée,
  parce qu'en nommant le jour de ses 18 ans elle laisserait recalculer sa date
  de naissance. Cette conséquence est portée au registre (`rgpd.md` §7).
- Les chemins d'écriture qui ne passent pas par l'IHM restent **non couverts et
  volontairement** : un import de scénario a son propre rapport d'impact, et les
  outils MCP continuent d'appeler les écritures nues — les y raccorder serait
  une décision à part, sur un transport qui ne rend pas d'entité à commenter.
