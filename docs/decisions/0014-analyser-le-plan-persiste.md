# 0014 — Analyser le plan persisté, plutôt que résoudre pour jeter

- **Statut** : accepté — prolonge [0013](0013-diagnostic-par-le-score-director.md)
- **Date** : août 2026
- **Portée** : service, API, MCP, frontend

## Contexte

Le projet offrait deux façons d'obtenir une analyse de contraintes. La première
est le sous-produit d'une résolution : tout solve diagnostique son résultat dans
la foulée et alimente l'écran Contraintes. La seconde s'appelait « analyse » et
faisait exactement la même chose — **une résolution complète** — à ceci près
qu'elle ne persistait pas son planning : trois routes REST, un type de job
dédié, un outil MCP, un bouton « Actualiser ».

Le score qu'elle produisait ne décrivait donc aucun plan visible. Les écrans,
les exports et les outils MCP parlent tous du plan en base ; l'analyse parlait
d'un planning calculé pour l'occasion et aussitôt perdu. Deux symptômes le
disaient déjà : rafraîchir l'écran Contraintes tenait le solveur pendant tout
le budget d'un solve, et le mode d'emploi MCP conseillait de lancer une analyse
quand aucune n'était en mémoire — c'est-à-dire après un redémarrage, précisément
le moment où le plan persisté, lui, est toujours là.

La décision [0013](0013-diagnostic-par-le-score-director.md) a rendu cette
seconde façon inutile sans le dire : diagnostiquer ne coûte plus qu'un calcul
de score, et l'opération qui le fait sur le plan en base existait déjà pour la
restauration d'instantané.

## Options envisagées

| Option | Ce qu'elle coûte |
| --- | --- |
| Garder l'analyse-résolution | Un budget de solve pour décrire un planning que rien n'affichera, et un écran qui montre autre chose que ce que montrent les autres |
| La garder comme « que donnerait un solve, sans écraser le plan ? » | Ce besoin est déjà tenu par l'instantané pris avant chaque résolution : le plan précédent est restaurable, la question se pose donc en résolvant |
| N'exposer que le diagnostic du plan persisté | Perd la possibilité de scorer un problème envoyé dans le corps de la requête |

## Décision

Une seule façon d'obtenir une analyse hors résolution : **recalculer le score du
plan persisté**, avec les contraintes, pondérations et paramètres du moment.
`POST /api/constraints/diagnostic` côté écrans, `diagnostiquer_plan` côté MCP.

Les trois routes `analyze`, le type de job correspondant et l'outil MCP
`lancer_analyse` sont supprimés plutôt que dépréciés : leur maintien inviterait
à répondre « lance une analyse » à la question « pourquoi ce plan a-t-il des
violations ? », qui est la mauvaise réponse.

## Conséquences

- L'analyse en mémoire décrit **toujours** le plan persisté. Un écart entre ce
  qu'affiche l'écran Contraintes et le plan affiché ailleurs redevient un
  défaut, au lieu d'être un mode de fonctionnement possible.
- Le registre de jobs perd son seul type ni rejouable ni mettable en file : la
  reconstruction d'un job depuis son intention n'a plus de branche impossible à
  écrire. Une migration purge les lignes restantes, que la relecture au
  démarrage ne saurait plus interpréter.
- Le journal des jobs perd l'historique des analyses passées. Assumé : il ne
  porte que l'intention d'un job, jamais son résultat, et aucune de ces
  analyses n'a jamais écrit de plan.
- Ce qui n'est plus possible : noter un problème arbitraire envoyé dans le corps
  d'une requête, sans passer par le référentiel. Aucun écran ne le faisait ;
  un scénario s'évalue en l'important puis en le résolvant.
- Rafraîchir l'écran Contraintes ne tient plus le solveur, et reste donc
  disponible pendant qu'une résolution tourne.
