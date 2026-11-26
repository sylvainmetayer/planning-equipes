# 0105 — Les variables de marque et de mentions légales sont groupées

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : configuration, déploiement
- **Précise** : [0040](0040-mentions-legales-exigees-au-demarrage.md) (le refus de démarrer sans mentions reste ; seuls les noms changent)
- **Limité par** : [0110](0110-budget-de-calcul-d-instance-file-en-memoire-quota-public.md) (le refus ne vaut plus que sur une instance publique)

## Contexte

La marque blanche et les mentions légales se réglaient par vingt-six
variables d'environnement : quatorze `BRANDING_*` et douze `LEGAL_*`. Un
déploiement les recopiait une à une dans son compose, son `.env` et sa CI, et
la palette des PDF seule en demandait cinq.

## Décision

Huit variables. Quatre ne changent pas (`BRANDING_PRODUCT_NAME`,
`BRANDING_ORGANISATION`, `BRANDING_SUPPORT_EMAIL`, `LEGAL_DEMO_INSTANCE`).
Quatre sont **groupées**, de la forme `clé=valeur;clé=valeur`, un point-virgule
littéral s'écrivant `\;` : `BRANDING_IMAGES`, `BRANDING_COLORS`,
`LEGAL_MENTIONS` et `LEGAL_ACCESSIBILITE`. Les clés reprennent le suffixe de
l'ancien nom.

Les propriétés que l'application lit (`planning.branding.*`, `planning.legal.*`)
ne bougent pas : un intercepteur de configuration les répond d'abord depuis la
variable groupée, puis depuis l'ancien nom. Aucun consommateur ne change. Les
anciens noms sont lus pendant toute la 4.x avec un avertissement au démarrage
(valeur jamais imprimée), la clé groupée l'emportant quand les deux sont
posées, et retirés en 5.0. Une clé inconnue ou un segment sans `=` est
signalé et ignoré.

Le refus de démarrer sans les cinq mentions exigées reste en place ; il nomme
maintenant les clés manquantes de `LEGAL_MENTIONS`. Le retirer ou le limiter à
une instance publique relève d'un autre changement.

## Alternatives écartées

- **Une liste séparée par des virgules, lue nativement par la configuration** :
  une valeur omise décale les suivantes, donc la position ne dit plus
  la clé, et une couleur `rgb(1,2,3)` porte des virgules.
- **Un fichier de configuration monté** : un geste d'exploitation de plus pour
  quatre lignes, et rien dans le compose actuel n'en monte.
- **Garder une variable par valeur** : c'est l'état que ce regroupement quitte.

## Conséquences

- Une valeur libre (adresse, durée annoncée) contenant un `;` doit l'échapper ;
  l'oublier coupe la valeur et fait apparaître une clé inconnue dans le journal
  de démarrage, jamais un refus.
- Un fichier `.env` lu par docker compose veut des guillemets autour d'une
  valeur qui contient un espace ou un `#`.
