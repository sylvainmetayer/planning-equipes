# Versions et releases : ce qu'un numéro promet, et comment patcher hier

Un numéro de version n'a d'intérêt que s'il permet à un exploitant de répondre
à deux questions : « je suis sur quelle version ? » et « le correctif y
est-il ? ». Ce document fixe ce que promettent les numéros, comment une release
se fabrique, et comment corriger une version antérieure sans rien perdre.

## 1. La source de vérité : un tag git

La version d'un déploiement est le **tag git** `vX.Y.Z` posé sur `main`. Il
naît du formulaire de release de GitHub (§ 3), qui le crée et le pousse ;
aucune commande n'est à taper.

Annoté ou léger, c'est sans conséquence, et le dépôt porte les deux :
`git describe --tags --exact-match` — la seule lecture qui compte, celle de
`generate-version.js` et du `-Drevision` du `Dockerfile` — les traite
identiquement. Ce qu'un tag annoté apportait, un résumé daté attaché à un
commit précis, vit désormais dans la **release** elle-même, à un endroit qui
se lit sans cloner.

Tout le reste **dérive** du tag, sans intervention :

| Où | Comment |
| --- | --- |
| Frontend (`APP_VERSION`, pied de page des deux coquilles — admin et espace animateur — et page *Débogage*) | `generate-version.js` : le tag exact (`v1.2.0`), sinon le SHA court. Le lien mène à la page de la release GitHub pour un tag (`/releases/tag/v1.2.0`, que GitHub rend aussi pour un tag sans release), au commit pour un SHA (`core/version-link.ts`) |
| Écran *Nouveautés* (`/nouveautes`) | `generate-news.js` : l'historique git lu au build, découpé par tag `vX.Y.Z` — ce qui suit le dernier tag s'affiche sous « À venir » |
| Backend (`quarkus.application.version`, ligne de démarrage Quarkus) | Le `Dockerfile` passe `-Drevision=1.2.0` dérivé du même `git describe`, le `v` retiré ; hors release, le SHA court. En build local, `999-SNAPSHOT` — une valeur qui ne ressemble volontairement à aucune version publiée |
| Sentry, **côté frontend seulement** | `release: APP_VERSION` : les erreurs du navigateur se regroupent par version, pas par commit. Les événements du backend ne portent pas encore de `release` — `SentryInitializer` ne pose que le DSN et l'environnement |
| Image Docker | `docker-ghcr.yml` publie `ghcr.io/…:1.2.0` et `ghcr.io/…:1.2` sur le push du tag |
| Release GitHub | Une par tag, corps écrit par `release.yml` à partir des messages de commit (§ 3) |

Le `pom.xml` ne porte donc **plus de numéro en dur** : sa version est
`${revision}`, et un workflow qui bâtit une release échoue avant de construire
si le tag n'est pas visible du build (étape de garde de `docker-ghcr.yml`).

**Point de départ : `1.0.0` à l'ouverture publique.** Pas de `0.x` : un `0.x`
dit « je ne m'engage sur rien », ce qui est faux — le produit tourne en
production sur un vrai événement.

## 2. Le contrat : SemVer sur la surface qu'un exploitant touche

« Rupture d'API » ne veut rien dire tant qu'on n'a pas dit ce qui est l'API.
Ici, la surface publique n'est **pas** le code Java : c'est ce qu'un exploitant
ou un client manipule.

**Sont la surface publique — les changer incompatiblement est MAJOR :**

- le format du **scénario YAML** (clés `festival:` et `festival.dateDebut`,
  forme des stands et des créneaux) : c'est un fichier que le client écrit ;
- les **variables d'environnement** (`BRANDING_*`, `LEGAL_*`,
  `PLANNING_MCP_*`, `FLYWAY_REPAIR_AT_START`…) : en retirer une, ou changer un
  défaut d'une façon qui change le comportement ;
- les **URLs stables** — au premier chef les liens d'espace animateur,
  **imprimés sur des PDF distribués à des humains** : les casser, c'est
  invalider du papier déjà en circulation ;
- l'**API REST** consommée par autre chose que notre propre frontend, serveur
  MCP compris ;
- une **migration Flyway irréversible** au sens exploitation : suppression ou
  renommage de colonne, contrainte qui rejette des données existantes. Le
  schéma est *forward-only* : revenir à l'image précédente ne suffit pas à
  revenir en arrière, il faut restaurer un dump
  ([`exploitation.md` § Sauvegarde](exploitation.md)). Une version qui rend le
  retour arrière impossible doit se voir de loin.

**MINOR** : fonctionnalité ajoutée, nouvelle variable d'environnement avec un
défaut qui préserve le comportement, migration additive (nouvelle table,
colonne nullable), nouvelle contrainte solveur *désactivable*.

**PATCH** : correction de bug, sécurité, dépendances, documentation. Aucune
migration destructive, aucune variable retirée.

**Le cas de la contrainte légale nouvelle ou durcie** : elle peut rendre
insatisfiable un planning qui passait avant. Techniquement additive,
pratiquement une rupture d'exploitation. Verdict : **MINOR, avec une entrée
« ⚠️ Attention » obligatoire aux notes de release.** Pas MAJOR : sinon on change de
majeure chaque fois que le Code du travail bouge.

Le marqueur est le **scope réservé `contraintes-legales`**
(`feat(contraintes-legales): …`), pas le `!` — lequel reste ce que la
convention en dit, le signe d'une rupture MAJOR, et ce que
`git cliff --bumped-version` lit pour calculer la montée. Les deux mènent à la
même section « ⚠️ Attention » (`cliff.toml`), sans que le même caractère ait
à signifier MAJOR ici et MINOR là.

## 3. Fabriquer une release

Rien ne change au quotidien : `main` reste la seule branche de développement,
une PR par sujet, pas de `develop`. Une release, c'est **un formulaire** —
*Releases* → *Draft a new release* sur GitHub : choisir le numéro `vX.Y.Z`
(§ 2 dit lequel), viser `main`, publier. Le corps peut rester vide.

Tout le reste dérive de ce geste, sans commande ni clone :

| Ce qui part | Sur quoi | Ce qu'il fait |
| --- | --- | --- |
| `docker-ghcr.yml` | le push du tag | build `linux/amd64` poussé sous `sha-…` seulement, **smoke test** de l'image publiée, SBOM, signature cosign, puis tags `1.2.0` et `1.2`, et `:latest` si le tag est le plus récent |
| `release.yml` | la publication de la release | écrit le corps de la release : la section rendue par `git cliff --current` |

Les deux sont indépendants et tournent de front ; aucun ne pose ni ne déplace
de tag, le numéro reste une décision humaine.

**Le smoke test démarre l'image publiée**, par son digest, contre un
PostgreSQL 18 et un Mailpit, avec l'environnement minimal de production. Il
vérifie la disponibilité, la version exposée (celle du tag), l'uid 1000,
qu'un `pg_dump` de la même majeure que le `postgres:` de
`docker-compose.prod.yml` est présent, et que `/backup` est inscriptible ;
en échec, les journaux du conteneur sont affichés. Ce n'est qu'ensuite que
l'image est signée, puis que les tags lisibles sont posés — par digest, sans
reconstruction. Une image qui ne démarre pas reste donc accessible sous
`sha-…` pour diagnostic, mais n'est jamais `1.2.0`, `:main` ni `:latest`.

Si ce contrôle échoue sur un tag de release, le tag git existe mais pas
l'image `1.2.0` : la réparation est celle des contrôles ci-dessous — supprimer
la release et son tag, corriger, recommencer au même numéro.

**Si tu écris quelque chose dans le corps** au moment de créer la release — le
résumé que portait le message d'un tag annoté —, il est conservé : `release.yml`
le laisse en tête et n'écrit la section générée qu'en dessous, après un trait de
séparation et le marqueur `<!-- git-cliff -->`. Tout ce qui suit ce marqueur lui
appartient et est réécrit à chaque exécution ; le relancer à la main
(*Actions* → *Notes de release* → *Run workflow*, avec le tag) est donc sans
risque, et c'est aussi ce qui permet de rattraper une release plus ancienne.

Deux contrôles tournent ensuite, et ils échouent **après** avoir écrit les
notes : la release est déjà publiée quand le workflow démarre, rien ne peut
plus l'empêcher, et un job rouge est le seul canal qui prévienne. Ils disent
qu'un numéro ment — un `!` fusionné sous une montée PATCH, un `feat` sous un
PATCH (§ 2) — ou que le tag n'est ni sur `main` ni sur une branche
`release/X.Y` (§ 5). Dans les deux cas la réparation est la même : supprimer
la release et son tag, recommencer au bon numéro.

### `:latest` ne bouge jamais implicitement

`docker/metadata-action` en défaut (`latest=auto`) déplacerait `:latest` à
**chaque** push de tag — publier un correctif `1.2.4` après la sortie de la
`1.3.0` ramènerait `:latest` sur l'ancienne ligne, silencieusement. Le
workflow force donc `latest=false`, et une étape séparée ne pose `:latest`
que si le tag poussé est le plus grand `vX.Y.Z` de tout le dépôt. `:latest`
n'avance que vers l'avant, ou pas du tout.

### Les notes sont générées, pas écrites — et il n'y a pas de `CHANGELOG.md`

`cliff.toml` à la racine transforme les messages conventionnels (`feat:`,
`fix:`, … — la convention est dans [`AGENTS.md`](../AGENTS.md)) en sections
datées. C'est le **corps des releases** qu'il alimente, et rien d'autre : aucun
`CHANGELOG.md` n'est tenu dans le dépôt.

> Un fichier aurait dit la même chose que la page *Releases*, avec un défaut
> qu'elle n'a pas : régénéré au moment du tag, son commit arrive forcément
> *après* lui, si bien que l'arbre taggué ne contient jamais sa propre entrée.
> La page de release, elle, est datée du tag qu'elle documente.

On ne retouche donc pas la section générée à la main : une entrée mal libellée
se corrige en reformulant le commit *avant* fusion, pas après.

Les mêmes sujets alimentent l'écran **Nouveautés** de l'application
(`/nouveautes`), construit à partir de l'historique git au moment du build et
non d'un fichier tenu à la main : mêmes filtres et mêmes intertitres que
`cliff.toml`, jusqu'au « ⚠️ Attention ». Un sujet mal libellé se lit donc deux
fois — dans les notes de version et dans l'application — ce qui est une raison
de plus de le corriger avant fusion.

### Savoir qu'une version plus récente existe

Un exploitant n'a pas à surveiller le dépôt : sur un build **posé sur un tag**,
la barre d'outils de l'administration affiche, à côté de la cloche des
notifications, une icône dont l'infobulle nomme la dernière release publiée
quand elle est plus récente que celle qui tourne ; le clic ouvre ses notes de
version. C'est le navigateur de l'administrateur qui interroge l'API publique
de GitHub (`/releases/latest`, une fois par chargement de page,
`core/update-check.service.ts`) — jamais le serveur, qui peut très bien être
déployé sans sortie réseau. **Et seulement celui d'un administrateur
connecté** : la coquille d'administration n'a pas de garde de route, elle
s'affiche puis redirige sur le premier 401, si bien qu'un visiteur quelconque
aurait joint GitHub avant cette redirection — l'adresse IP d'un animateur,
souvent mineur, livrée pour rien, et le quota anonyme par IP de GitHub épuisé
au détriment des vrais administrateurs. La session est donc confirmée sur
`/api/auth/me` avant le moindre appel sortant. Un build sur un SHA n'interroge
rien : « une version plus récente existe » y est vrai tous les jours et
n'apprend rien. GitHub
injoignable, quota d'API épuisé, réponse inattendue : l'icône reste absente,
sans message — c'est une courtoisie, pas une alerte. Un déploiement qui nomme
ses endpoints dans `CSP` (`securite.md`) doit y laisser `https://api.github.com`
pour la conserver, ou l'omettre pour la désactiver.

## 4. Déployer : un `vX.Y.Z`, jamais `:main` ni `:latest`

Le workflow publie aussi une image `:main` à chaque fusion. C'est l'**image de
recette** : elle sert à valider ce qui sortira, pas à tourner chez un client.

Un déploiement de production référence une **version nommée** — `1.2.0` —
parce qu'un déploiement doit être nommable dans un rapport d'incident ou de
faille. `:main` désigne un commit différent chaque jour ; `:latest` désigne ce
que le registre veut bien ; ni l'un ni l'autre n'est une réponse à « vous
tourniez sur quoi ? ». `docker-compose.prod.yml` prend la version par la
variable `APP_VERSION` du `.env.prod`.

La version tourne aussi **en réponse** : `GET /api/config` porte un champ
`version` (`X.Y.Z` sur une image de release, le SHA court sur une image de
recette, `999-SNAPSHOT` en local), le même que la ligne de démarrage. C'est ce
que compare `scripts/verifier-deploiement.sh` après un `up -d`, avec la
disponibilité et les mentions légales — voir
[`exploitation.md`](exploitation.md) § 2.

## 5. Patcher une version antérieure

C'est la question qui justifie tout le reste. Réponse : **des branches de
maintenance créées à la demande, jamais à l'avance.** Tant que tous les
déploiements sont sur la dernière version, il n'existe aucune branche de
maintenance — on n'en crée une que le jour où un exploitant réel ne peut pas
monter de version.

Procédure, ce jour-là (exemple : faille à corriger, une prod en `1.2.0`,
`main` déjà en `1.3.x`) :

```bash
# 1. Le correctif va d'abord sur main. Toujours. Sans exception.
#    Un correctif qui n'existe que sur une branche de maintenance est une
#    régression programmée pour la version suivante.
git switch main && … && git commit     # PR normale, CI verte, fusion

# 2. La branche de maintenance, créée depuis le TAG, pas depuis main
git switch -c release/1.2 v1.2.0

# 3. Rapatrier le correctif déjà fusionné sur main
git cherry-pick -x <sha>               # -x note l'origine dans le message

# 4. Publier la branche ; le tag, lui, naît de la release (§ 3) — viser
#    `release/1.2` dans le formulaire, pas `main`
git push origin release/1.2
```

`release.yml` remonte au tag précédent de **cette ligne-là** pour composer les
notes et vérifier la montée : sur `release/1.2`, un `v1.2.1` se lit après
`v1.2.0`, jamais après le `v1.3.0` de `main`.

Les règles qui rendent ça sûr :

- **`main` d'abord, toujours** — c'est ce qui garantit qu'aucun correctif ne
  se perd ;
- **`cherry-pick -x`**, pour que le commit de maintenance dise de quel commit
  de `main` il vient. Si le correctif ne s'applique pas tel quel, on écrit une
  adaptation — et on la relit comme du code neuf, parce que c'en est ;
- **rien sur une branche de maintenance qui ne soit pas déjà sur `main`** ;
- **aucune migration Flyway dans un patch de maintenance**, sauf strictement
  additive : le schéma étant *forward-only*, une base déjà passée en `1.3.0`
  ne redescendra pas. Si le patch touche au schéma, ce n'est plus un patch,
  c'est une montée de version ;
- **`release/1.2` ne fusionne jamais dans `main`** : elle vit et meurt sur sa
  ligne ;
- **`:latest` ne bouge pas** : le `v1.2.1` n'est pas le tag le plus récent du
  dépôt, l'étape du workflow le laisse en place (§3).

**Combien de lignes maintient-on ? Une seule** — la dernière `MAJOR.MINOR`
publiée en dehors de la ligne courante, et seulement tant qu'un exploitant
réel y est. Au-delà, la réponse est « montez de version » : un développeur
seul ne maintient pas trois lignes.
