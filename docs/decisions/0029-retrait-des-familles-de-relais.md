# 0029 — Les familles de relais sont retirées

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : découpage, créneaux, stands, génération des postes, grille de saisie, import de scénario, MCP, base
- **Remplace** : [0026](0026-famille-de-relais-attribut-du-stand.md)

## Contexte

Le découpage savait générer plusieurs **familles** de vacations aux coupures
décalées (`nombreFamillesDecalage`, `dureeDecalageMaxMinutes`), chaque stand
relayant avec sa seule famille. Le mécanisme est né quand le solveur butait sur
le pic de relève : si tous les stands relèvent à la même minute, les sièges à
pourvoir doublent à cet instant. L'ADR 0026 avait ensuite fait de la famille
un attribut persisté du stand, pour qu'un stand ajouté ne déplace pas les
autres.

En septembre 2026, le constat est que la notion **n'a jamais servi en
production** : les éditions réelles ont une grille à une seule famille, et le
chevauchement des vacations suffit au pic. Pendant ce temps, chaque écran qui
touche à la grille devait comprendre les familles : cases inertes dans la
grille de saisie, colonne conditionnelle sur les créneaux, champ de la fiche
caché sur une grille à une famille, colonnes d'import posées « sur tous les
créneaux de la bande ». Le dernier symptôme a été un scénario portant
`famille: 0..4` sur des créneaux à une seule famille, qui bloquait tout
enregistrement de fiche sans rien montrer.

## Décision

La notion disparaît entièrement :

- plus de `famille` sur les créneaux ni sur les stands, plus de
  `nombreFamillesDecalage` ni de `dureeDecalageMaxMinutes` dans les paramètres
  de découpage, ni dans l'API, le YAML de scénario ou les outils MCP ;
- le générateur de vacations produit une seule variante par amplitude, la coupe
  posée à la cible bornée dans la plage retenue, comme pour une famille unique ;
- la génération des postes apparie tout stand à tout créneau ouvert ;
- la migration `V73` supprime les créneaux des familles 1 et plus, avec les
  affectations, verrous, demandes d'échange et résolutions qui s'y appuyaient,
  puis retire les colonnes. Une édition à grille décalée ne garde que sa
  première famille et son plan est à recalculer ;
- un scénario qui porte encore `famille:` ou `nombreFamillesDecalage:` est
  refusé, comme toute clé inconnue (`ScenarioBinder`). Les scénarios livrés
  sont réécrits sans ces clés.

## Conséquences

- La grille « blocs décalés » étudiée sur l'événement de référence (162
  animateurs au lieu de 175 grâce à quatre familles décalées) n'est plus
  constructible. Si le besoin revient, il se repensera comme des créneaux
  ordinaires aux heures décalées, pas comme des variantes.
- Les scénarios `festival-realiste` et `festival-realiste-canicule` (découpage
  automatique à cinq familles) changent de grille : leurs empreintes de test
  sont régénérées.
- L'ADR 0026 est remplacé ; la stabilité d'un stand ajouté est acquise sans
  attribut, puisqu'il n'y a plus de répartition à stabiliser.
