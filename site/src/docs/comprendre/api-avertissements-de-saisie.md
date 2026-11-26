# Avertissements de saisie

Certaines incohérences ne justifient pas un refus : la ligne est écrite et un avertissement voyage dans la réponse de succès, à côté de l'entité. Vous saurez quelles routes les renvoient, ce que signifie chaque `type`, quand il est émis et pourquoi il ne nomme jamais une personne. Le principe vient de la doctrine du dépôt, qui ne refuse que ce qui est certainement insatisfiable ([ADR 0020](repo:docs/decisions/0020-avertir-dans-la-reponse-d-ecriture.md)). Les écritures qui, elles, échouent sont décrites dans [écrire les référentiels](api-referentiels.md).

## Où ils apparaissent

Un organisateur a le droit de saisir une indisponibilité hors des bornes de l'édition, un animateur mineur pendant l'événement, un créneau qui déborde l'ouverture de tous les stands ou un horaire de stand qui ne recoupe aucun créneau. La réponse enveloppe alors l'entité :

```json
POST /api/animateurs  →  200
{
  "animateur": { "id": "A12", "prenom": "…", … },
  "avertissements": [
    { "type": "MINEUR_PENDANT_EVENEMENT",
      "message": "L'animateur A12 est mineur du 2026-07-08 au 2026-07-08 et devient majeur le 2026-07-09 …" }
  ]
}
```

Cinq ressources répondent ainsi, `avertissements` y étant toujours présent et vide quand tout va bien :

| Routes | Réponse |
| --- | --- |
| `POST /api/animateurs`, `PUT /api/animateurs/{id}` | `{ animateur, avertissements }` |
| `POST /api/creneaux`, `PUT /api/creneaux/{id}` | `{ creneau, avertissements }` |
| `POST /api/stands`, `PUT /api/stands/{id}` | `{ stand, avertissements }` |
| `POST /api/contraintes-ad-hoc` | `{ contrainte, avertissements }` |
| `POST /api/verrouillages` | `{ verrouillage, avertissements }` |

Les autres référentiels répondent l'entité nue : une clé `avertissements` absente veut dire rien à signaler. L'import de scénario n'en émet pas (un fichier vaut pour lui-même et son rapport d'impact dit ce qu'il change), et les outils MCP appellent les écritures nues et répondent l'entité seule, hors le code `RESOLUTION_EN_COURS` décrit dans [écrire pendant un calcul](api-referentiels.md#écrire-pendant-un-calcul). L'édition en lot est couverte : elle émet un `PUT` par ligne et regroupe les avertissements du lot en un seul message.

## Les types

| `type` | Ce qui le déclenche |
| --- | --- |
| `INDISPONIBILITE_HORS_EVENEMENT` | Un jour d'indisponibilité hors de l'intervalle `[premier créneau, dernier créneau]` : il ne recouvre aucun créneau et ne protège personne. |
| `INDISPONIBILITE_JOUR_SANS_CRENEAU` | Un jour d'indisponibilité dans l'intervalle mais sur une date sans créneau (un lundi de relâche entre deux week-ends). L'espace animateur ne l'affichera pas et la première déclaration de disponibilités appliquée l'effacera. L'import CSV refuse la ligne pour ce motif, la saisie manuelle avertit. |
| `MINEUR_PENDANT_EVENEMENT` | L'animateur est mineur au moins un jour de l'événement ; le message dit à partir de quelle date il devient majeur, ou qu'il est mineur du début à la fin. Émis seulement quand l'écriture pose ou change la date de naissance : être mineur est un état légitime, et le redire à chaque modification d'une autre colonne apprend à ignorer le message. |
| `MINEUR_SANS_REPRESENTANT` | Les contacts d'un mineur sont enregistrés sans représentant légal. Facultatif donc jamais refusé, mais le rapport de conformité liste ces personnes et la phrase le dit avant lui. Émis par l'écriture des contacts, pas par celle de la fiche. |
| `CRENEAU_HORS_OUVERTURE_STANDS` | Aucun stand n'est ouvert une seule minute du créneau : il n'ouvrira aucun poste. |
| `CRENEAU_DEBORDE_OUVERTURE_STANDS` | Le créneau commence avant que tous les stands n'ouvrent, ou finit après qu'ils ont tous fermé, d'au moins un quart d'heure. |
| `STAND_FENETRE_SANS_EFFET` | Une fenêtre du stand, d'une règle étendue comme d'une exception datée, ne recoupe aucun créneau de son jour : enregistrée, elle ne change rien. Le message cite jusqu'à cinq jours. |
| `STAND_EXCEPTION_HORS_EVENEMENT` | Une exception datée du stand nomme un jour hors de l'intervalle `[premier créneau, dernier créneau]`. Le lendemain d'un créneau qui franchit minuit n'est pas compté comme hors événement : le domaine lit vraiment cette date. |
| `STAND_JAMAIS_OUVERT` | Après l'écriture, le stand n'est ouvert sur aucun créneau. |
| `STAND_SANS_EMPLACEMENT` | Le stand est enregistré sans emplacement alors que ses voisins en ont un en commun : même racine de nom (« Buvette 3 » et « Buvette B » ont pour racine « Buvette »), à défaut mêmes typologies proposées. Il faut au moins deux voisins placés et un emplacement tenu par plus de la moitié d'entre eux. Émis seulement quand l'écriture change l'emplacement, le nom ou les typologies, et jamais sur une édition sans emplacement. Une suggestion, jamais une écriture : `GET /api/stands/suggestions-emplacement` donne la même lecture pour toute l'édition. |
| `AFFECTATION_FORCEE_JOUR_INDISPONIBLE` | L'affectation forcée ne tombe que sur des jours où tous les animateurs qu'elle nomme se sont déclarés indisponibles : elle ne pourra pas être tenue. Écrite quand même (le jour d'indisponibilité peut être retiré), et reprise comme cause bloquante par l'analyse de faisabilité tant qu'elle tient. |
| `AFFECTATION_FORCEE_MOTIF_LEGAL` | Aucune place du périmètre de l'affectation forcée n'accepte l'un des animateurs nommés, au titre d'une règle dure lisible sur le couple place et animateur : mineur la nuit, un jour férié, un stand réservé aux majeurs, le plafond quotidien dépassé. Le message nomme les règles du catalogue. |
| `AFFECTATION_FORCEE_SIEGE_VERROUILLE` | L'emploi du temps de chaque animateur nommé est verrouillé sur tout son périmètre sans qu'aucun d'eux y tienne déjà de place : le solveur ne peut ni bouger le verrou ni ignorer l'exception. Seuls les verrous `ANIMATEUR_CRENEAU` comptent ; les autres épinglent les places tenues et laissent les places vides remplissables. |
| `ARRIVEE_GROUPEE_JOURS_DIVERGENTS` | Les membres d'une arrivée groupée n'ont pas déclaré les mêmes jours d'indisponibilité : ces jours-là, le groupe ne pourra pas arriver ensemble. Le message donne le nombre de jours et le premier. |
| `VERROUILLAGE_SUR_VIOLATION_DURE` | Le verrouillage écrit fige des situations qui cassent déjà une règle dure dans la dernière analyse. Écrit quand même : un verrou fige sans exempter ([ADR 0003](repo:docs/decisions/0003-verrouillage-par-pin-natif.md)) et les places restent jugées. Le message nomme les règles et le nombre de situations. Rien n'est dit sur une édition qu'aucune résolution n'a analysée : il n'y a rien à lire, et rien n'est deviné. |

Les trois types `AFFECTATION_FORCEE_*` et `ARRIVEE_GROUPEE_JOURS_DIVERGENTS` portent sur une exception ad hoc ; leur sens métier est dans [les exceptions ad hoc](exceptions-ad-hoc.md).

## Seul ce que l'écriture change est signalé

Sur une modification, chaque règle se lit contre la fiche telle qu'elle était avant : un jour d'indisponibilité déjà enregistré et une date de naissance non touchée ne produisent rien. L'édition en lot envoie un `PUT` par ligne portant la fiche entière fusionnée ; sans cette comparaison, cocher trente bénévoles pour leur ajouter une compétence finirait sur un message citant chaque mineur de la sélection. À la création, tout est nouveau et tout est examiné. Un avertissement qui se déclenche sur des données correctes cesse d'être lu.

Les trois avertissements de stand sur l'horaire (`STAND_FENETRE_SANS_EFFET`, `STAND_EXCEPTION_HORS_EVENEMENT`, `STAND_JAMAIS_OUVERT`) ne sont émis que si l'écriture touche à l'horaire (règles, fermetures, ouvertures) : renommer un stand qui n'a jamais ouvert n'est pas le moment de le dire. La grille de saisie `PUT /api/ouvertures-stands/grille` n'en émet aucun, ses fenêtres suivant les créneaux par construction.

## Les bornes de l'événement se dérivent

Une `Edition` ne porte ni dates ni drapeau « en cours » : l'événement court du premier au dernier créneau, la même dérivation que la collecte des disponibilités. Une édition sans aucun créneau n'a donc aucune borne et n'émet aucun avertissement : en inventer une produirait un faux positif sur l'écran même par lequel une nouvelle édition commence.

Les horaires des stands sont lus récurrences développées, et développées contre le créneau en cours d'écriture. Sans cela, un créneau créé sur une date neuve verrait fermés tous les stands programmés par règle, et l'avertissement crierait au loup sur la saisie la plus banale.

## Ce que les messages ne disent pas

Un avertissement nomme un animateur par son identifiant seul, jamais par ses nom et prénom ni sa date de naissance. Le navigateur recopie chaque message dans un journal `localStorage` qui survit à la déconnexion, et l'identité d'un mineur n'a rien à y faire (voir [la protection des données](../installer/rgpd.md)). L'IHM va plus loin sur un seul type : la phrase `MINEUR_PENDANT_EVENEMENT` est affichée mais pas journalisée, parce qu'en nommant le jour des 18 ans elle permettrait de recalculer la date de naissance.
