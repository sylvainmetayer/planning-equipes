---
ordre: 150
---

### La copie avant vidage devient un export YAML

- **Avant (3.x)** : avant « Vider cette édition », un import de scénario ou une restauration, l'application rangeait le scénario de l'édition (texte YAML) **et**, dans une colonne JSON à part (`sauvegarde_avant_vidage.plan`), son planning ; la restauration enchaînait l'import, puis un code propre remettait les sièges et les verrous.
- **Après (4.0)** : la copie est **un seul export YAML**, dont la dernière section, `plan:`, porte les sièges et les verrous ; la restauration est l'import normal de ce fichier, qui remet le planning ([ADR 0112](repo:docs/decisions/0112-la-copie-avant-vidage-est-un-export-yaml-avec-son-plan.md)). La migration `V147` rend seulement la colonne `plan` facultative : **aucune ligne n'est copiée, réécrite ni supprimée**. Les copies déjà rangées par la 3.x restent restaurables et téléchargeables : leur colonne est relue comme la section `plan:` du même fichier. Le fichier téléchargé d'une copie récente porte cette section ; il reste un scénario v3 valide ; importé à la main, il ne remet que le scénario (la section `plan:` est ignorée avec un avertissement), seule la restauration remet le planning. Les droits (administrateur seul) et la durée (celle de l'édition, aucune purge) ne changent pas.
- **Ce que vous faites** : rien pour vos copies. Si votre registre des traitements décrit « scénario et planning par identifiants », il reste exact ; ajoutez que le fichier est un export YAML complet, à garder comme la base ([les copies avant vidage](rgpd-limites-a-consigner.md#les-copies-avant-vidage)). Le chemin de lecture de l'ancienne colonne est retiré en 5.0, comme le lecteur v1 : avant la 5.0, restaurez ou supprimez les copies de la 3.x dont vous avez encore besoin.
