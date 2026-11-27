# Publier une version et patcher la précédente

Vous fabriquez une release (un formulaire GitHub, aucune commande) et, le jour où un exploitant réel ne peut pas monter de version, vous corrigez une ligne antérieure sans rien perdre. `main` reste la seule branche de développement, une PR par sujet, pas de `develop`. Ce guide s'adresse à qui publie l'image ; pour déployer une version, voir [Mettre à jour vers une nouvelle version](mettre-a-jour.md), et pour ce que promet un numéro, [les numéros de version](versioning.md).

## 1. La source de vérité : un tag git

La version d'un déploiement est le **tag git** `vX.Y.Z` posé sur `main`. Il naît du formulaire de release de GitHub (§ 2), qui le crée et le pousse ; aucune commande n'est à taper.

Annoté ou léger, c'est sans conséquence, et le dépôt porte les deux : `git describe --tags --exact-match`, la seule lecture qui compte, celle de `generate-version.js` et du `-Drevision` du `Dockerfile`, les traite identiquement. Ce qu'un tag annoté apportait, un résumé daté attaché à un commit précis, vit désormais dans la **release** elle-même, à un endroit qui se lit sans cloner.

Tout le reste **dérive** du tag, sans intervention :

| Où | Comment |
| --- | --- |
| Frontend (`APP_VERSION`, pied de page des deux coquilles, admin et espace animateur, et page *Débogage*) | `generate-version.js` : le tag exact (`v1.2.0`), sinon le SHA court. Le lien mène à la page de la release GitHub pour un tag (`/releases/tag/v1.2.0`, que GitHub rend aussi pour un tag sans release), au commit pour un SHA (`core/version-link.ts`) |
| Écran *Nouveautés* (`/nouveautes`) | `generate-news.js` : l'historique git lu au build, découpé par tag `vX.Y.Z` ; ce qui suit le dernier tag s'affiche sous « À venir » |
| Backend (`quarkus.application.version`, ligne de démarrage Quarkus) | Le `Dockerfile` passe `-Drevision=1.2.0` dérivé du même `git describe`, le `v` retiré ; hors release, le SHA court. En build local, `999-SNAPSHOT` |
| Sentry, **côté frontend seulement** | `release: APP_VERSION` : les erreurs du navigateur se regroupent par version, pas par commit. Les événements du backend ne portent pas encore de `release` : `SentryInitializer` ne pose que le DSN et l'environnement |
| Image Docker | `docker-ghcr.yml` publie `ghcr.io/…:1.2.0` et `ghcr.io/…:1.2` sur le push du tag |
| Release GitHub | Une par tag, corps écrit par `release.yml` à partir des messages de commit (§ 2) |

Le `pom.xml` ne porte donc **plus de numéro en dur** : sa version est `${revision}`, et un workflow qui bâtit une release échoue avant de construire si le tag n'est pas visible du build (étape de garde de `docker-ghcr.yml`).

## 2. Fabriquer une release

Une release, c'est **un formulaire** : *Releases* → *Draft a new release* sur GitHub. Choisissez le numéro `vX.Y.Z` ([ce que chaque numéro promet](versioning.md)), visez `main`, publiez. Le corps peut rester vide.

Pour une **préversion**, nommez le tag `vX.Y.Z-rc.N` (`N` à partir de 1, sans zéro de tête) et cochez *Set as a pre-release*. Elle suit le même chemin : l'image est publiée sous `X.Y.Z-rc.N` seulement, sans `X.Y` ni `:latest`, et les notes couvrent la plage depuis le dernier tag stable. Un autre suffixe (`-beta`, `-alpha`) est refusé par les deux workflows.

![Fabrication d'une release et patch d'une version antérieure](repo:docs/diagrammes/release.svg)

<sub>Source : [`diagrammes/release.puml`](repo:docs/diagrammes/release.puml).</sub>

Tout le reste dérive de ce geste, sans commande ni clone :

| Ce qui part | Sur quoi | Ce qu'il fait |
| --- | --- | --- |
| `docker-ghcr.yml` | le push du tag | build `linux/amd64` poussé sous `sha-…` seulement, smoke test de l'image publiée, SBOM, signature cosign, puis tags `1.2.0` et `1.2`, et `:latest` si le tag est le plus récent ; pour une préversion, `1.2.0-rc.1` seulement |
| `release.yml` | la publication de la release | écrit le corps de la release : la section rendue par `git cliff --current` |

Les deux sont indépendants et tournent de front ; aucun ne pose ni ne déplace de tag, le numéro reste une décision humaine.

Le smoke test démarre l'image publiée, par son digest, contre un PostgreSQL 19 et un Mailpit, avec l'environnement minimal de production. Il vérifie la disponibilité, la version exposée (celle du tag), l'uid 1000, qu'un `pg_dump` de la même majeure que le `postgres:` de `docker-compose.prod.yml` est présent, et que `/backup` est inscriptible ; en échec, les journaux du conteneur sont affichés. Ce n'est qu'ensuite que l'image est signée, puis que les tags lisibles sont posés, par digest, sans reconstruction. Une image qui ne démarre pas reste donc accessible sous `sha-…` pour diagnostic, mais n'est jamais `1.2.0`, `:main` ni `:latest`.

Si ce contrôle échoue sur un tag de release, le tag git existe mais pas l'image `1.2.0` : la réparation est celle des contrôles ci-dessous. Supprimez la release et son tag, corrigez, recommencez au même numéro.

Si vous écrivez quelque chose dans le corps au moment de créer la release, le résumé que portait le message d'un tag annoté, il est conservé : `release.yml` le laisse en tête et n'écrit la section générée qu'en dessous, après un trait de séparation et le marqueur `<!-- git-cliff -->`. Tout ce qui suit ce marqueur lui appartient et est réécrit à chaque exécution ; le relancer à la main (*Actions* → *Notes de release* → *Run workflow*, avec le tag) est donc sans risque, et c'est aussi ce qui permet de rattraper une release plus ancienne.

Deux contrôles tournent ensuite, et ils échouent **après** avoir écrit les notes : la release est déjà publiée quand le workflow démarre, rien ne peut plus l'empêcher, et un job rouge est le seul canal qui prévienne. Ils disent qu'un numéro ment (un `!` fusionné sous une montée PATCH, un `feat` sous un PATCH) ou que le tag n'est ni sur `main` ni sur une branche `release/X.Y` (§ 3). Dans les deux cas la réparation est la même : supprimez la release et son tag, recommencez au bon numéro. Une préversion se compare à la dernière version finale ; le contrôle refuse une préversion d'une version déjà publiée, ou dont le `N` ne dépasse pas celui d'une préversion de même numéro, et chaque refus dit sa raison dans le journal du job. Relancer le workflow à la main sur un tag rejoue ces contrôles avec les scripts de `main`, pas ceux du tag.

### `:latest` ne bouge jamais implicitement

`docker/metadata-action` en défaut (`latest=auto`) déplacerait `:latest` à **chaque** push de tag : publier un correctif `1.2.4` après la sortie de la `1.3.0` ramènerait `:latest` sur l'ancienne ligne, silencieusement. Le workflow force donc `latest=false`, et une étape séparée ne pose `:latest` que si le tag poussé est le plus grand `vX.Y.Z` de tout le dépôt. `:latest` n'avance que vers l'avant, et ne désigne jamais une préversion : l'étape est sautée pour un tag `-rc.N`.

### Les notes sont générées, pas écrites

Il n'y a pas de `CHANGELOG.md`. `cliff.toml` à la racine transforme les messages conventionnels (`feat:`, `fix:`…, la convention est dans [`AGENTS.md`](repo:AGENTS.md)) en sections datées. C'est le **corps des releases** qu'il alimente, et rien d'autre.

> Un fichier aurait dit la même chose que la page *Releases*, avec un défaut qu'elle n'a pas : régénéré au moment du tag, son commit arrive forcément *après* lui, si bien que l'arbre taggué ne contient jamais sa propre entrée. La page de release, elle, est datée du tag qu'elle documente.

On ne retouche donc pas la section générée à la main : une entrée mal libellée se corrige en reformulant le commit *avant* fusion, pas après.

Les mêmes sujets alimentent l'écran **Nouveautés** de l'application (`/nouveautes`), construit à partir de l'historique git au moment du build et non d'un fichier tenu à la main : mêmes filtres et mêmes intertitres que `cliff.toml`, jusqu'au « ⚠️ Attention ». Un sujet mal libellé se lit donc deux fois, dans les notes de version et dans l'application, ce qui est une raison de plus de le corriger avant fusion.

## 3. Patcher une version antérieure

Les branches de maintenance se créent à la demande, jamais à l'avance. Tant que tous les déploiements sont sur la dernière version, il n'existe aucune branche de maintenance : on n'en crée une que le jour où un exploitant réel ne peut pas monter de version.

Voici la procédure, ce jour-là (exemple : faille à corriger, une prod en `1.2.0`, `main` déjà en `1.3.x`) :

```bash
# 1. Le correctif va d'abord sur main. Toujours. Sans exception.
#    Un correctif qui n'existe que sur une branche de maintenance est une
#    régression programmée pour la version suivante.
git switch main && … && git commit     # PR normale, CI verte, fusion

# 2. La branche de maintenance, créée depuis le TAG, pas depuis main
git switch -c release/1.2 v1.2.0

# 3. Rapatrier le correctif déjà fusionné sur main
git cherry-pick -x <sha>               # -x note l'origine dans le message

# 4. Publier la branche ; le tag, lui, naît de la release (§ 2) : viser
#    `release/1.2` dans le formulaire, pas `main`
git push origin release/1.2
```

`release.yml` remonte au tag précédent de **cette ligne-là** pour composer les notes et vérifier la montée : sur `release/1.2`, un `v1.2.1` se lit après `v1.2.0`, jamais après le `v1.3.0` de `main`.

Les règles qui rendent ça sûr :

- `main` d'abord, toujours : c'est ce qui garantit qu'aucun correctif ne se perd ;
- `cherry-pick -x`, pour que le commit de maintenance dise de quel commit de `main` il vient. Si le correctif ne s'applique pas tel quel, écrivez une adaptation, et relisez-la comme du code neuf, parce que c'en est ;
- rien sur une branche de maintenance qui ne soit pas déjà sur `main` ;
- aucune migration Flyway dans un patch de maintenance, sauf strictement additive : le schéma étant *forward-only*, une base déjà passée en `1.3.0` ne redescendra pas. Si le patch touche au schéma, ce n'est plus un patch, c'est une montée de version ;
- `release/1.2` ne fusionne jamais dans `main` : elle vit et meurt sur sa ligne ;
- `:latest` ne bouge pas : le `v1.2.1` n'est pas le tag le plus récent du dépôt, l'étape du workflow le laisse en place.

Le nom de la branche est `release/X.Y`, exactement : `release.yml` n'accepte pas `release/3.x`. La ligne 3.x vit donc sur `release/3.5`, créée depuis le tag `v3.5.0`, et ses correctifs sortent en `v3.5.1`, `v3.5.2`… La durée de vie d'une ligne après une majeure est dans [les numéros de version](versioning.md#combien-de-temps-une-ligne-est-corrigée).

Combien de lignes maintient-on ? Une seule : la dernière `MAJOR.MINOR` publiée en dehors de la ligne courante, et seulement tant qu'un exploitant réel y est. Au-delà, la réponse est « montez de version » : un développeur seul ne maintient pas trois lignes.
