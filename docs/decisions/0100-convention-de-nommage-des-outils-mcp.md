# 0100 — Les outils MCP se nomment `<objet>_<verbe>`, et une table dit où est passé chaque ancien nom

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : outils MCP, guide de migration 4.0
- **Prolonge** : [0097](0097-mcp-par-oauth-et-rotation-de-la-cle.md) (le MCP par OAuth)

## Contexte

Les 157 outils publiés en 3.1.0 se nomment au gré de leur auteur :
`lister_stands`, `creer_stand_complet`, `equite_planning`, `volumes`. Le
préfixe est tantôt un verbe, tantôt un objet, et les familles ne se retrouvent
pas à l'oeil. La 4.0 regroupe les outils par intention : chaque PR de modèle
touche les siens, et ils ne doivent changer de nom qu'une fois.

## Décision

1. **Un nom d'outil est `<objet>_<verbe>`**, en minuscules séparées par `_` :
   l'objet d'abord (`stand`, `planning`, `animateur`, `edition`, `consigne`,
   `verrou`…), pour que les outils d'un même objet se suivent dans la liste ;
   le verbe en dernier, pris dans l'ensemble fermé `lire`, `lister`, `creer`,
   `modifier`, `supprimer`, `analyser`, `simuler`, `lancer`, `arreter`,
   `importer`, `exporter`, `publier`, `envoyer`. Un outil qui regroupe
   plusieurs réglages d'un même objet prend l'objet seul comme nom et une
   section en argument (`parametres`, argument `section`).
2. **Le fichier `src/main/resources/mcp-renommages.tsv` est la table de
   correspondance** : une ligne par nom publié sur `v3.1.0`, `ancien` puis
   `nouveau`, ou `retiré` quand l'outil disparaît. Tant qu'une PR de modèle ne
   l'a pas renommé, le nouveau nom d'un outil est son ancien.
3. **Chaque PR de modèle renomme ses outils directement dans la convention**
   et met à jour sa ligne de la table ; la PR de clôture des outils traite
   ceux qu'aucun modèle ne touche.
4. **Pas d'alias.** Un outil n'est publié que sous son nom courant : le
   client est un assistant qui relit la liste des outils à chaque connexion,
   la table et le guide de migration suffisent, et des `@Tool` en double
   doubleraient les quatre tests structurels MCP.
5. **Deux tests tiennent la table.** `McpRenommageTest` échoue sur un nom de
   `v3.1.0` sans ligne (la liste est figée dans
   `src/test/resources/mcp-outils-v3.1.0.txt`), sur un nouveau nom que le
   serveur ne publie pas, et sur un nom changé qui ne suit pas la convention.

## Conséquences

- Les tests structurels dérivent aujourd'hui les hints du préfixe du nom
  (`McpAnnotationsStructurelleTest`) : la première PR qui renomme un outil
  adapte cette dérivation au verbe final.
- Aucun outil n'est renommé par cette décision.
