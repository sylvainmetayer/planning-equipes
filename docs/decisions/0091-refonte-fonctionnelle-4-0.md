# 0091 — Refonte fonctionnelle 4.0 : un geste, un mécanisme

- **Statut** : accepté
- **Date** : novembre 2026
- **Portée** : tout le produit — modèle, solveur, écrans, MCP, import/export, authentification
- **Voisine de** : [0087](0087-un-second-moteur-de-recherche-arbitre-par-timefold.md) et [0088](0088-le-moteur-direct-devient-le-moteur-par-defaut.md) (les deux moteurs), [0090](0090-les-reglages-avances-replies-par-ecran.md) (réglages avancés repliés)

## Contexte

Au fil des versions 2.x et 3.x, plusieurs écrans, routes et réglages se sont
mis à répondre à la même question de deux façons : deux manières de dire
quand un stand ouvre, deux de générer la grille des créneaux, plusieurs gels
qui se recouvrent, des versions du plan de trois sortes, des règles qui
n'existent que par couples majeur/mineur. Un audit fonctionnel de l'ensemble
a fixé ce qui reste, ce qui fusionne et ce qui part. Cet ADR en garde le
cadre : le détail de chaque lot vit dans les ADR et la documentation de
référence qui l'implémentent, et ils renvoient ici.

## Décisions

1. **Un geste = un mécanisme.** Quand deux fonctions répondent à la même
   question, on garde la plus générale et l'autre devient une présentation
   de celle-là. On ne retire une fonction qu'après lecture du code qui en
   dépend, jamais sur la seule absence d'usage observé.
2. **Un seul modèle de plages datées pour les horaires des stands.** La
   récurrence hebdomadaire reste un générateur de l'écran, pas un second
   modèle stocké. La grille des créneaux a un générateur unique (horaires des
   stands et journées types fusionnés) ; le créneau saisi à la main reste une
   édition.
3. **Trois mécanismes de gel, pas davantage** : *Figer* (le verrou, à trois
   types), la phase de l'édition (préparation, calcul, publiée, terminée) qui
   absorbe le gel du référentiel par famille, et l'état de la journée
   (à relire, relue, figée), qui absorbe la validation de journée. Le drapeau
   de passé figé reste, pour rejouer d'anciennes éditions.
4. **Le niveau de chaque règle d'organisation est un réglage de l'édition** :
   obligatoire, préférence forte ou préférence légère. « Obligatoire » est
   une vraie obligation du plan, jamais franchie. Les règles légales restent
   toujours obligatoires et en lecture seule. Les règles voisines fusionnent
   quand une seule, paramétrée, dit la même chose (plafonds par âge, jours
   d'affilée, lieu, repas).
5. **Deux moteurs gardés, Timefold arbitre.** Le moteur direct reste le
   défaut et Timefold reste le moteur qu'une instance peut choisir à la place
   (`SOLVER_ENGINE=timefold`) ; Timefold continue de re-scorer chaque plan, et
   un écart fait échouer la résolution sans repli. Aucune décision ne retire
   Timefold. Le calcul déporté garde son worker et son contrat ; le lanceur de
   machine à la demande est retiré.
6. **Une version du plan, de trois origines** (manuelle, avant calcul,
   publiée), comparables entre elles. Il n'existe pas de « plan alternatif »
   comme concept : travailler une variante, c'est dupliquer une édition.
7. **Authentification par OIDC, avec un compte local de secours.** Le compte
   administrateur local est fermé par défaut quand OIDC est actif, mais reste
   réouvrable par une variable d'instance, et chaque ouverture est
   journalisée. Le mode d'en-tête d'un proxy disparaît à la livraison d'OIDC.
8. **Des écrans en moins, des onglets en plus** : un menu court, un écran par
   question, les variantes en onglets de l'écran. Les réglages d'expert restent
   repliés (voir 0090).
9. **Les ruptures sont annoncées, jamais silencieuses.** Les noms changés
   de variables et de règles gardent un alias et un avertissement pendant
   toute la 4.x ; les outils MCP renommés sont listés ancien → nouveau dans
   `site/src/docs/comprendre/mcp.md`, sans alias ; le lecteur YAML convertit l'ancien format. Un numéro de version de format explicite refuse un fichier plus
   récent que le produit.

## Conséquences

- Le catalogue des règles, le moteur direct et le test différentiel suivent
  chaque fusion de règle : une règle existe toujours deux fois, et le test
  dit si la seconde écriture est juste.
- La documentation de référence est réduite en proportion des écrans
  retirés ; les ADR passés sont conservés et marqués « remplacé » vers ceux de
  la refonte.
- Les études sans suite de code (0028, 0073, 0080) passent à « étude close » :
  elles restent lisibles comme raisonnement, sans laisser croire qu'un travail
  est en attente.
- Ce qui retire une fonction décrite par un ADR précédent le dit dans son
  propre ADR et renvoie ici.
