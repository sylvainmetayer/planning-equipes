# Passer de la 3.x à la 4.0

Vous exploitez une instance en 3.x et vous voulez savoir ce que la 4.0 vous demandera. Cette page est un **squelette** : la 4.0 est en préparation, chaque changement qui casse quelque chose y ajoute son paragraphe au moment où il est fusionné, et aucune date de sortie n'est promise. Ne montez pas de version à partir de ce guide tant que la 4.0.0 n'est pas publiée.

## Où en est-on

- La **3.5** est la dernière version publiée depuis la ligne de développement. La ligne `3.x` ne reçoit plus que des correctifs, sur la branche de maintenance `release/3.5` ([comment elle se tient](publier-une-version.md#3-patcher-une-version-antérieure)).
- Les ruptures de la 4.0 sont suivies dans les tickets [#271](https://github.com/sylvainmetayer/planning-equipes/issues/271) (modèle), [#272](https://github.com/sylvainmetayer/planning-equipes/issues/272) (gel, versions, paramètres), [#273](https://github.com/sylvainmetayer/planning-equipes/issues/273) (règles), [#274](https://github.com/sylvainmetayer/planning-equipes/issues/274) (MCP et écrans) et [#281](https://github.com/sylvainmetayer/planning-equipes/issues/281) (lots de contraintes).
- Combien de temps la 3.x reste soutenue : voir [la politique de maintenance](versioning.md#combien-de-temps-une-ligne-est-corrigée).

## Avant de monter

1. Restez sur la dernière 3.x et lisez ses notes de version : une 3.x peut déjà avertir de ce que la 4.0 retire.
2. Faites une [sauvegarde](sauvegarde-et-restauration.md#2-sauvegarder-à-la-main). Le schéma ne va que vers l'avant : une base migrée en 4.0 ne redescend pas sous une image 3.x.
3. Lisez l'entrée « ⚠️ Attention » des notes de la 4.0, puis les sections ci-dessous.

## Ce qui change

Chaque paragraphe dit *avant*, *après* et *ce que vous faites*. Aucun n'est écrit pour l'instant.

### Variables d'environnement

À venir : tableau ancien nom, nouveau nom.

### Format du scénario YAML

À venir : ce que le lecteur convertit seul, et ce qu'il ignore en le disant dans le rapport d'import.

### Noms des règles du planning

À venir : correspondance des anciens noms, règles fusionnées ou retirées.

### Outils MCP

**Avant** : 157 outils aux noms libres (`lister_stands`, `equite_planning`, `volumes`…). **Après** : les outils se nomment `<objet>_<verbe>` ([ADR 0100](repo:docs/decisions/0100-convention-de-nommage-des-outils-mcp.md)), certains sont regroupés ou retirés, et il n'existe **aucun alias** : un outil n'est publié que sous son nom courant. La correspondance complète, ancien nom puis nouveau nom ou `retiré`, est le fichier `src/main/resources/mcp-renommages.tsv`, remplie au fil des changements de la 4.0 (pour l'instant aucun outil n'est renommé).

**Ce que vous faites** : si vous avez écrit des instructions, des invites ou des scripts qui nomment des outils, relisez-les avec cette table ; un assistant qui se reconnecte relit la liste des outils et n'a rien d'autre à changer.

### Données et migrations

À venir : les migrations qui convertissent des données, et ce qu'elles font de vos éditions existantes.
