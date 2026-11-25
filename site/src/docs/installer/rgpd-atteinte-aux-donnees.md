# Préparer la réponse à une atteinte aux données

Décidez maintenant qui vous appelez et sous quel délai : cela coûte dix minutes, et l'improviser un dimanche de festival coûte la relation. Voici la marche à suivre quand vous hébergez l'instance d'un client : ce qui compte comme atteinte, le délai à tenir, la personne à appeler, le contenu de l'alerte et le journal à tenir, y compris pour les atteintes qu'on ne notifie pas (art. 33).

Le sous-traitant ne notifie **jamais la CNIL** : il alerte le responsable de
traitement, **sans délai injustifié**, et c'est le responsable qui décide de
notifier l'autorité. Celui-ci dispose de 72 heures **à compter du moment où
l'hébergeur l'a prévenu** : tout retard pris ici est pris sur son délai, ce qui
oblige à chiffrer l'engagement.

## Ce qui compte comme atteinte

Une atteinte couvre plus qu'une intrusion : c'est tout ce qui touche à la
**confidentialité**, à l'**intégrité** ou à la **disponibilité** des données,
accidentelle ou non. Les trois se notifient, même si elles ne se traitent pas
pareil.

| Cas | Ce qu'il faut savoir avant d'appeler |
| --- | --- |
| Dump égaré : sauvegarde déposée en clair, envoyée par un canal non maîtrisé, oubliée sur une machine cédée | Ce qu'il contenait ; un dump est **complet** : noms, dates de naissance de mineurs, adresses, jetons d'espace (voir la sauvegarde dans [`exploitation.md`](exploitation.md)) |
| Accès non autorisé à l'administration | Depuis quand, ce qui a été consulté ou modifié, et si le mot de passe d'administration a servi ailleurs |
| Fuite de jetons d'espace animateur, d'abonnement au calendrier ou d'affichage mural | Les jetons voyagent **dans le chemin de l'URL** : des journaux d'accès de reverse proxy partagés, indexés ou transmis sont une atteinte, pas une négligence sans suite (voir [ce qui reste à la charge du reverse proxy](securite.md#ce-qui-reste-à-la-charge-du-reverse-proxy)). Un jeton d'abonnement fuité est **immédiatement exploitable sans second facteur**, et il apparaît dans ces journaux à chaque synchronisation d'un agenda, donc bien plus souvent que l'autre |
| Base ou sauvegarde perdue sans copie | C'est une atteinte à la **disponibilité** : elle se notifie, même sans le moindre accès d'un tiers |
| Envoi d'un planning individuel à la mauvaise adresse | Atteinte aussi, à sa mesure : l'erreur de destinataire est le cas le plus fréquent en pratique |

Deux cas n'en sont **pas** : une panne de l'instance sans perte de données
(c'est un incident d'exploitation, la rubrique « Savoir que ça tourne » de [`exploitation.md`](exploitation.md)), et un animateur qui
voit le planning d'un collègue par une fonction prévue pour ça
([consultation des postes d'un collègue](securite-limites-de-debit.md#consultation-des-postes-dun-collègue)).

## Le délai qu'on s'engage à tenir

| Étape | Engagement |
| --- | --- |
| Alerter le contact client | **Dans les 24 heures** suivant la prise de connaissance, même sans diagnostic complet ; le premier appel dit ce qu'on sait, pas ce qu'on a compris |
| Premier écrit qualifiant les faits | Dans les 48 heures |
| Compte rendu final | Une fois l'investigation close, sans échéance fixée à l'avance |

Notifier tôt et incomplet est explicitement prévu par le règlement : l'art. 33.4
autorise une information **par phases**. Attendre d'avoir tout compris est la
seule faute vraiment coûteuse.

## Qui on appelle

Appelez une personne nommée et un suppléant, jamais une adresse générique : un
`contact@` n'est lu par personne un dimanche. Ils sont nommés dans la
convention de sous-traitance (voir [la convention](rgpd-sous-traitance.md)) et reportés au registre (voir [le gabarit d'entrée](rgpd.md#remplir-une-entrée-du-registre)), avec un numéro
de téléphone, car l'incident peut être précisément celui qui empêche l'e-mail
d'arriver.

## Ce que contient l'alerte

L'art. 33.3 fixe le contenu ; le responsable reprendra ces éléments tels quels
pour la CNIL :

- la nature de l'atteinte, et les **catégories et le nombre approximatif** de
  personnes et d'enregistrements concernés ;
- les conséquences probables ;
- les mesures prises ou proposées, y compris pour en atténuer les effets ;
- le point de contact côté hébergeur.

Le nombre de personnes concernées se lit dans l'application : c'est l'effectif
des éditions touchées. Le rappeler ici évite d'avoir à le chercher sous
pression.

## Le journal des atteintes

L'art. 33.5 impose de **documenter toute atteinte, y compris celle qu'on ne
notifie pas** : ce registre permet de démontrer qu'on a bien qualifié
les faits. Il vit avec le registre des traitements et le journal des purges
(voir [le journal des purges](rgpd.md#tenir-le-journal-des-purges-et-des-restaurations)) :

| Colonne | Contenu |
| --- | --- |
| Découverte | Date et heure de la **prise de connaissance**, pas de la survenue : c'est elle qui déclenche les délais |
| Faits | Ce qui s'est passé, et par quel canal on l'a appris (sonde, suivi d'erreurs, signalement d'un animateur) |
| Périmètre | Éditions et catégories de données touchées, volume approximatif |
| Qualification | Atteinte ou non, **et pourquoi**, y compris quand la réponse est non |
| Notification | Qui a été prévenu, quand, par quel moyen |
| Suites | Mesures correctives, et ce qu'elles changent aux mesures de sécurité déclarées au registre |

## Ce qui manque pour tenir cet engagement

Un délai de 24 heures suppose de **savoir** qu'il s'est passé quelque chose.
La rubrique « Savoir que ça tourne » de [`exploitation.md`](exploitation.md) le dit déjà pour les pannes, et la
dépendance est la même ici : sans `SENTRY_DSN` renseigné ni sonde externe, une
instance peut être tombée, ou pire, sans que personne ne l'apprenne avant le
lundi. S'engager sur un délai de notification sans ces deux briques revient à
signer une obligation qu'on ne tiendra pas.
