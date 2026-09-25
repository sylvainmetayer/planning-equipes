# 0053 — L'affichage mural s'ouvre par un jeton dédié, pas par une session admin

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : sécurité, API, schéma (migration `V107`), écran public
- **Prolonge** : [0019](0019-jeton-et-chemin-dedies-pour-l-abonnement-ics.md) (un jeton et un chemin dédiés)

## Contexte

La salle de contrôle veut une TV qui montre, toute la journée, les stands du
jour : qui tient quoi maintenant, qui prend la suite, où il manque quelqu'un.
Les écrans qui le disent déjà — Journée, Mode jour J — sont derrière la
**session admin** : un formulaire de connexion, huit heures glissantes.

Une TV reste allumée des jours dans une pièce où passent des bénévoles. Une
session admin ouverte dessus serait prolongée par le rafraîchissement de l'écran
lui-même, et donnerait à quiconque s'approche du clavier l'écriture sur tout le
planning, de toutes les éditions.

## Options envisagées

| Option | Ce qu'elle coûte |
| --- | --- |
| **A.** Une session admin sur la TV | L'écriture sur tout, sur un écran sans surveillance, qui ne se ferme jamais. Rien ne distingue la TV d'un poste d'administration |
| **B.** Un compte « lecture seule » | Un second rôle dans toute l'API, et une politique d'autorisation à tenir route par route. Le compte ouvrirait toutes les lectures, dont les fiches nominatives, pour un écran qui n'en montre qu'une |
| **C.** Un jeton dédié, sur un préfixe dédié | Une table, une migration, un garde, un écran de gestion — le patron de l'abonnement ICS |
| **D.** Un PDF exporté puis affiché | Figé au moment de l'export : ne montre ni le remplacement fait en Mode jour J, ni la vacation qui commence |

## Décision

**Option C.** Un lien d'affichage mural par écran, créé par l'administrateur.

- `GET /api/mural/{jeton}` — **une seule route** sous ce préfixe, exemptée de
  l'authentification admin comme `/api/abonnements/*`, pour la même raison : un
  proxy d'accès a besoin d'un motif d'URL qui ne désigne qu'elle.
- Le jeton ouvre **une lecture, pour une édition** : les stands du jour et
  leurs vacations, les places libres, les pauses sans relais, la consigne. Ni
  référentiel, ni export, ni écriture.
- Le jeton est **haché** en base (SHA-256), comme les sessions de l'espace, et
  n'est montré qu'à la création. Une copie de la base ne rouvre aucun écran.
- **Révocable** depuis Paramètres, **rattaché à une édition** et supprimé avec
  elle. Un jeton révoqué et un jeton inventé reçoivent la même réponse.
- Les noms s'affichent **prénom et initiale** par défaut ; le nom complet est
  une option du lien, choisie à sa création.
- La vue lit le **plan persisté**, pas le plan publié : la salle de contrôle
  doit voir un remplacement du Mode jour J avant toute republication. Et
  « maintenant » est l'heure du **serveur** (`JourJClock`), jamais celle de la
  TV.

## Conséquences

- **Le lien est un accès, pas une donnée** : il ne voyage ni dans l'export SQL
  ni dans la duplication d'une édition. Une adresse perdue se remplace, elle ne
  se relit pas.
- Contrairement à l'abonnement ICS, la route **a un plafond**, mais il ne
  compte par adresse que les lectures **refusées** : l'énumération de jetons y
  coûte du temps, tandis qu'un jeton déjà servi passe toujours. Un plafond sur
  toutes les requêtes d'une adresse a été écarté : derrière un proxy non
  déclaré, toutes les requêtes portent la même, et trente requêtes au hasard
  par minute suffisaient à éteindre tous les écrans. Un second plafond, large,
  borne les lectures par lien.
- Les lectures **ne sont pas journalisées** : une par minute et par écran
  noierait l'historique. La création et la révocation le sont.
- Un écran restreint à quelques emplacements le reste quand ils sont supprimés :
  il n'affiche plus rien plutôt que, sans prévenir, toute l'édition.
- Aucun outil MCP ne crée de lien : un jeton d'accès n'a pas à transiter par un
  assistant.
