# Politique de sécurité

## Signaler une vulnérabilité

**N'ouvrez pas d'issue publique.** Écrivez à **planning@sylvain.dev**, ou
utilisez le [signalement privé de GitHub](https://github.com/sylvainmetayer/planning-equipes/security/advisories/new)
si l'option est active sur le dépôt.

Merci d'inclure ce qui permet de reproduire : version affichée en bas de la page
*Débogage*, étapes, effet obtenu, et l'effet attendu.

Le projet est maintenu par une seule personne, sur son temps libre. Les
délais visés pour une vulnérabilité signalée :

| Étape | Délai |
| --- | --- |
| Accusé de réception | 7 jours |
| Première évaluation (confirmée ou non, gravité) | 14 jours |
| Correctif publié sur `main` | 90 jours au plus, bien moins pour une faille grave |
| Divulgation publique (*coordinated disclosure*) | à la publication du correctif, par un avis de sécurité GitHub, ou au bout de 90 jours en accord avec vous |

Vous serez tenu informé du traitement, et crédité dans l'avis si vous le
souhaitez.

## Ce qui nous intéresse particulièrement

L'application manipule des données personnelles de personnes planifiées, dont
des **mineurs** (nom, prénom, date de naissance, adresse électronique,
disponibilités). Sont donc prioritaires :

- tout accès à des données d'animateur sans authentification valide ;
- toute fuite entre **éditions** — le cloisonnement du référentiel est la
  frontière de données du produit ;
- tout contournement de l'authentification administrateur ou de l'espace
  animateur (jetons d'accès, codes à usage unique, cookies de session) ;
- toute exposition de données nominatives par le serveur MCP, qui est
  explicitement conçu pour ne pas en laisser sortir.

## Périmètre

Le dépôt couvre l'application. Un déploiement donné relève de son exploitant :
reverse proxy, TLS, sauvegardes, mot de passe administrateur et variables
d'environnement ne sont pas dans le périmètre de ce projet. Voir
[`docs/securite.md`](../docs/securite.md) pour la liste des points à vérifier
côté exploitation.

## Versions soutenues

Seule la branche `main` reçoit des correctifs. Aucune version antérieure n'est
maintenue.
