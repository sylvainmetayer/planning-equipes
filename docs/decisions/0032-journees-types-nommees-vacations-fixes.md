# 0032 — Des journées types nommées génèrent les créneaux, qui restent la vérité

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : référentiel des créneaux, import de scénario, MCP, IHM

## Contexte

Pour l'édition 2027 le FESTIVAL tape ses vacations à la main — « 9-12 / 12-13 /
13-14 / 14-20 », les tranches de son classeur — et n'entend pas passer par
le découpage d'amplitudes : « un changement à la fois, l'outil sera déjà un
énorme changement ». La saisie se faisait créneau par créneau, ou par une
série récurrente sans nom ni mémoire : rien ne disait qu'un mardi est « un
jour normal » et un samedi « une nocturne », et rien ne portait les dates
de l'édition avant le premier créneau (les bornes se dérivent des créneaux,
voir `docs/domaine.md`). Le relais repas de 12-13 et 13-14 — la moitié des
sièges, arrondie au supérieur — n'était posé que par le découpage.

## Options envisagées

**(A) Les journées types deviennent la vérité, les créneaux une vue.**
Écarté : les postes, les verrous, les demandes d'échange et les bornes de
l'édition référencent des créneaux ; en faire une projection recalculée
aurait touché chaque lecteur pour un gain nul à l'écran.

**(B) Une série récurrente qui se souvient de son nom.** Écarté : la série
ajoute et n'entretient rien — modifier la journée type n'aurait aucun
effet sur ce qu'elle a produit, et le calendrier resterait implicite.

**(C) Un générateur persisté, appliqué par différence.** Retenu.

## Décision

Une **journée type** porte un nom et une liste de vacations, chacune avec
le drapeau de couverture de pause du créneau. Un **calendrier** affecte
chaque date à une journée type au plus. **Appliquer** matérialise les
créneaux par différence sur la clé naturelle `(date, début, fin)` — la clé
que le contrôle de grille appelle déjà un doublon : un créneau identique
garde son id, ses sièges et ses verrous ; un drapeau différent est mis à
jour en place ; un créneau manquant est créé ; un créneau que la journée
type ne nomme pas, sur une date gouvernée, est supprimé avec ses sièges,
après un aperçu chiffré. **Une date sans journée type n'est jamais
touchée.** L'application déclare la grille en vacations : une journée type
dit des vacations.

Une journée type modifiée après application ne propage rien : la carte
affiche les dates **en écart** et l'organisateur réapplique. La
**reconnaissance** fait le chemin inverse — chaque date aux mêmes
vacations est la même journée — et remplace journées types et calendrier ;
elle tourne après tout remplacement de la grille (import de scénario,
découpage), pour qu'une édition importée et une édition tapée se lisent
pareil. Un scénario peut porter sa propre section `journeesTypes:`, appliquée
après ses créneaux ; l'export l'écrit.

## Conséquences

- Les dates de l'édition se posent sur le calendrier, avant tout créneau.
- Le formulaire de créneau expose le relais repas ; la grille signale un
  relais hors de toute fenêtre repas.
- Le découpage, la dérivation depuis les stands et leurs paramètres ne
  s'affichent plus quand la grille est déclarée en vacations.
- Trois tables (`journee_type`, `journee_type_vacation`,
  `journee_type_date`), dans le dump et la duplication d'édition.
