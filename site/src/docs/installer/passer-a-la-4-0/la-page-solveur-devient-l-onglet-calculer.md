---
ordre: 233
---

### La page Solveur devient l'onglet « Calculer » du Planning

- **Avant (3.x)** : le calcul se lançait depuis la page *Solveur* (`/solveur`), qui avait son entrée de menu (raccourci « g » puis « s »), trois boutons (« Calculer le planning », « Corriger après un changement », « Recommencer de zéro ») et un second onglet, *Versions du plan* (`/solveur?onglet=versions`). L'avancement de la relecture ne s'y lisait qu'après un calcul lancé depuis ce navigateur.
- **Après (4.0)** : la page *Planning* (`/journee`) porte deux onglets à côté de ses quatre vues : **Calculer** (`?onglet=calculer`), l'ancienne page *Solveur*, et **Versions du plan** (`?onglet=versions`). L'entrée *Solveur* quitte le menu, et son raccourci avec elle ; la palette trouve les deux onglets. Il reste **deux boutons** : « Calculer le planning », le calcul complet depuis le plan en place (verrous et passé figé respectés), qui demande confirmation dès qu'un plan existe, et « Corriger après un changement », qui ne répare que les sièges en écart. **« Recommencer de zéro » devient une case** de la confirmation de « Calculer le planning », décochée par défaut ; cochée, une seconde confirmation dit ce qui sera perdu. L'avancement de la relecture (journées relues ou figées) s'affiche dès qu'un plan existe, calculé ou non. L'API et les outils MCP ne changent pas.
- **Ce que vous faites** : rien pour vos liens. `/solveur` et `/solver` mènent à `/journee?onglet=calculer`, `/solveur?onglet=versions` et `/versions` à `/journee?onglet=versions`, leurs autres paramètres gardés. Un signet ou une page d'aide interne qui nommait `/solveur` est à reprendre vers la nouvelle adresse avant la 5.0. Qui lançait un calcul de zéro par le bouton coche désormais la case de la confirmation.
