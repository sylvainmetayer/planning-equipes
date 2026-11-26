# 0113 — Les mesures d'une édition n'ont qu'une table, `mesure_edition`, et qu'un écran

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : persistance (`kpi_historique`, `kpi_realise`), API, écran, outils MCP
- **Prolonge** : [0092](0092-le-realise-se-calcule-a-la-demande.md) (le réalisé à la demande), [0100](0100-convention-de-nommage-des-outils-mcp.md) (noms des outils)

## Contexte

Deux tables disaient « ce que l'édition a mesuré » sans se connaître :
`kpi_historique` (une ligne par résolution terminée, les indicateurs du plan) et
`kpi_realise` (une ligne par typologie et une pour l'événement, le réalisé figé
d'une édition terminée). Même raison d'être — la mesure survit à l'édition qui
la porte, l'édition suivante la lit —, même absence de clé étrangère, mêmes
libellés recopiés. Mais deux chemins d'API, deux écrans (le bas de *Versions du
plan*, un onglet d'*Aujourd'hui*) et trois outils MCP, pour une seule question :
« qu'avons-nous mesuré, et que valait l'édition d'avant ? ».

## Décision

1. **Une table, `mesure_edition`**, avec une colonne `nature` : `INDICATEURS`
   (le `jsonb` d'une résolution, comme avant) ou `REALISE` (les compteurs du
   réalisé, colonnes nulles pour l'autre nature, un `CHECK` qui dit lesquelles
   sont exigées). Le `BIGSERIAL` d'identifiant sert aux deux.
2. **Toujours sans clé étrangère** : la mesure survit à l'édition, c'est
   l'objet de la table. Elle reste donc hors cloisonnement par édition
   (`TABLES_HORS_EDITION`), les libellés et les bornes de l'événement restent
   recopiés.
3. **« Écrit une seule fois » est conservé tel quel** : un index unique partiel
   `(edition_id, typologie_id) WHERE nature = 'REALISE'` porte l'`ON CONFLICT DO
   NOTHING` que portait la clé primaire de `kpi_realise`. Refiger une édition
   reste une suppression SQL de ses lignes `REALISE` par l'exploitant, jamais
   une route.
4. **Migration sans perte, par copie** (`V148`) : les lignes des deux tables sont
   recopiées, **les identifiants de l'historique conservés** (ce sont ceux que
   les clients connaissent) et la séquence repart après eux ; la date de gel
   devient `cree_le`. Les anciennes tables ne sont pas supprimées : elles
   deviennent `kpi_historique_avant_4_0` et `kpi_realise_avant_4_0`, que
   l'application ne lit ni n'exporte et que la 5.0 supprimera, comme les autres
   archives de la 4.0. `MesureEditionMigrationTest` joue la migration sur une
   base remplie à la manière de la 3.x et compte les lignes avant et après.
5. **API** : `/api/mesures-edition/indicateurs` (liste, suppression par id) et
   `/api/mesures-edition/realise` (grille, `/detail`, `/export`, `/precedente`).
   Les chemins `/api/kpi/historique` et `/api/planning/realise` restent des
   alias `@Deprecated` jusqu'en 5.0.
6. **Un écran**, *Mesures d'édition* (`/mesures`), avec deux vues dans l'adresse :
   `?vue=realise` (par défaut) et `?vue=indicateurs`. Les anciennes adresses
   (`/realise`, `/aujourdhui?onglet=realise`) y mènent, leurs autres paramètres
   gardés. Il n'a pas d'entrée de menu (les treize sont les moments du cycle) :
   un lien en tête d'*Aujourd'hui*, là où était l'onglet, la palette et l'aide
   y mènent.
7. **MCP** : les trois outils prennent la convention de l'ADR 0100 :
   `lister_mesures_edition`, `supprimer_mesure_edition`,
   `comparer_realise_planifie`. Ils restent trois : les deux premiers ne sont
   pas cloisonnés par édition (une édition se compare à la précédente), le
   troisième l'est, et un outil ne peut pas à la fois porter et ne pas porter
   l'argument `edition` que `McpEditionStructurelleTest` exige de chacun.

## Options écartées

- **Renommer sans copier, en gardant deux tables** : c'était ne rien fusionner.
- **Une table pour l'historique et le réalisé en `jsonb`** : le réalisé est lu
  par des requêtes (la dernière édition terminée avant une date, l'ordre des
  événements), qui veulent des colonnes ; l'historique, lui, change d'indicateurs
  et veut du `jsonb`. Les deux formes coexistent dans la même table, chacune
  nulle chez l'autre.
- **Supprimer les anciennes tables dans la migration** : un retour à la 3.x ou
  une relecture par l'exploitant perdrait les lignes ; l'archive coûte une table
  que personne ne lit.
- **Un seul outil MCP à argument `vue`** : voir le point 7.

## Conséquences

- L'écran *Versions du plan* garde, pour l'instant, une ligne par résolution :
  c'est une chronologie des versions, que les indicateurs des résolutions
  complètent sans la remplacer.
- Un dump SQL de la 3.x (`INSERT INTO kpi_historique`, `kpi_realise`) ne se
  rejoue plus sur une base 4.0 : le dump est un outil de test, l'import des
  scénarios (YAML) est le chemin des données.
