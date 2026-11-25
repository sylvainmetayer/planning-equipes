# 0099 — Le lecteur voit sans rien écrire : un droit de compte, un refus du serveur par édition

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : droits des comptes, API, MCP par OAuth, exports
- **Prolonge** : [0093](0093-connexion-par-un-fournisseur-oidc-et-secours-ferme.md) (les comptes et leurs droits), [0097](0097-mcp-par-oauth-et-rotation-de-la-cle.md) (le MCP par OAuth)

## Contexte

Il manquait un droit pour qui doit tout voir d'une édition sans pouvoir y
toucher : un auditeur, une direction, un prestataire qui relit. L'organisateur
voit ce qu'il faut, mais il écrit ; lui retirer l'écriture bouton par bouton
dans l'interface n'aurait rien garanti, puisque l'API reste appelable.

## Décision

1. **Un droit de plus sur un compte**, `LECTEUR`, accordé pour **une édition**
   (jamais « toutes »), comme le droit RH. Il voit ce que voit l'organisateur
   dans cette édition — dates de naissance et statut de mineur compris —,
   mais ni les contacts (ils restent au droit RH, qui se cumule) ni rien de
   l'instance (`@InstanceOnly` et `@InstanceOnlyTool` le refusent comme un
   organisateur).
2. **Le serveur fait foi** (`LecteurFilter`). Pour un compte qui porte un droit
   de lecteur, toute requête hors `GET`/`HEAD`/`OPTIONS` répond `403`,
   **y compris un `POST` qui ne fait que calculer** : un lecteur ne lance ni
   solve ni calcul. Le refus tient à la porte de l'organisateur : les routes
   derrière une autre porte — la session elle-même (déconnexion, mot de
   passe), l'espace animateur, l'abonnement ICS, l'affichage mural, les
   préfixes RH et responsable — gardent leurs propres règles, sans quoi un
   lecteur ne pourrait ni se déconnecter ni écrire ce que son droit RH lui
   permet. Le front masque les actions principales, par confort.
3. **Le cumul se décide par édition.** Voir une édition, c'est un droit
   d'organisateur *ou* de lecteur ; y écrire, c'est un droit d'organisateur
   seul (`CompteService.writeScope`). Un compte lecteur de A et organisateur de
   B écrit dans B et pas dans A ; les droits RH et responsable de stand restent
   ce qu'ils sont (leurs préfixes d'API, leurs propres éditions) et ne donnent
   ni ne retirent rien. Une requête qui ne désigne aucune édition n'écrit que
   si un droit d'organisateur couvre toutes les éditions. Un compte sans droit
   de lecteur n'est jamais jugé par ce filtre : rien ne change pour lui.
4. **Les exports.** Autorisés : le PDF global (`GET`), les PDF et ICS
   individuels et leurs archives de PDF ou d'ICS (`POST`, marqués
   `@ExportForLecteur`, seule exception à la règle 2 : ils rendent ce que le
   client envoie, sans rien écrire ni résoudre). Refusés (`@NotForLecteur`) :
   tout CSV (listes, exports de rapports, modèles d'import), l'archive de fin
   d'événement, l'export de référentiels en archive, le scénario YAML et la
   sauvegarde avant vidage ; l'export SQL est de l'instance. Une route qui
   produit un CSV, une archive, du SQL ou du YAML sans être refusée fait échouer
   `RoutesLecteurStructuralTest`.
5. **MCP par OAuth.** Le jeton d'un lecteur ouvre le transport, mais
   `EditionCibleeInterceptor` ne laisse passer que les outils qui déclarent
   `readOnlyHint = true`, sur ses éditions ; un autre outil répond par un refus
   métier (`BusinessError`, sans nom de personne). `McpLecteurStructurelleTest`
   exige que tout outil écrivant traverse cet intercepteur ou soit réservé à
   l'instance.
6. **Les filets.** `RoutesLecteurStructuralTest` tient l'inventaire des routes
   ouvertes (`routes-lecteur.txt`) : une nouvelle lecture non classée, une
   écriture ouverte ou un export brut ouvert font échouer le build.
   `LecteurCanariTest` balaie le contrat OpenAPI avec un vrai compte lecteur.

## Alternatives écartées

- **Une liste blanche de routes à la main** : la règle « tout ce qui n'est pas
  une lecture est refusé » ne se démode pas quand on ajoute une route d'écriture.
- **Refuser par le rôle HTTP seul** : le cumul avec un droit d'organisateur sur
  une autre édition ne s'exprime pas dans une politique de chemin.
- **Retirer au lecteur les dates de naissance** : décision contraire du
  propriétaire — il voit comme l'organisateur.

## Conséquences

- Un lecteur ne peut ni imprimer en lot ni exporter de tableur : les PDF et
  l'ICS suffisent à la relecture, les données brutes restent à qui écrit.
- Les `POST` de simple calcul (simulations, prévisualisations, aperçus) sont
  refusés : l'écran correspondant s'affiche, son action pas.
- Les actions masquées côté interface sont les boutons principaux ; un bouton
  secondaire qui échoue répond `403` avec sa phrase.
