# Explorbot — tests exploratoires pilotés par IA

[Explorbot](https://github.com/testomatio/explorbot) conduit un navigateur avec
un LLM : il lit une page, invente des scénarios, les joue, et écrit les specs
Playwright/CodeceptJS correspondantes. On l'essaie ici pour couvrir ce que nos
specs Playwright de `src/main/webui/e2e` ne vont pas chercher.

Ce dossier est **hors du build Maven** : rien dans `pom.xml` ni dans Quinoa ne
le regarde, et son `package.json` n'a aucun rapport avec celui du frontend
(`src/main/webui`). Licence Elastic 2.0 — libre pour tester nos applications,
interdit à la revente en service hébergé.

## Ce qu'il y a dans le dossier

```
explorbot/
├── explorbot.config.js   # cible, navigateur, modèles
├── .env                  # clés d'API — ignoré par git
├── knowledge/            # ce que le LLM doit savoir de l'app
│   ├── app.md            #   routes, vocabulaire FR, comportements Material
│   ├── login.md          #   identifiants admin
│   └── referentiels.md   #   écrans CRUD et leurs cas limites
├── rules/                # instructions par agent
│   ├── tester/planning-equipes.md
│   └── planner/planning-equipes.md
├── experience/           # ce qu'il a appris des runs — ignoré par git
└── output/               # résultats — ignoré par git
```

## Prérequis

- Node 24+ (déjà épinglé dans `mise.toml`)
- `npm install` dans ce dossier, puis `npx playwright install chromium`
- **une clé d'API LLM** : un abonnement ChatGPT ou Claude ne marche pas, il faut
  une clé facturée à l'usage

Le fournisseur se choisit par `EXPLORBOT_AI_PROVIDER` dans `.env`
(`openai`, `openrouter`, `anthropic`, `google`, `groq`) ; `explorbot.config.js`
contient déjà les modèles recommandés pour chacun. Explorbot utilise trois
rôles : `model` (Tester, Navigator, Researcher — ils relisent le HTML à chaque
pas, c'est là que partent les tokens), `visionModel` (analyse des captures) et
`agenticModel` (Captain et Pilot, qui ne lisent que des logs courts et décident).

Vérifier ce qui est réellement chargé :

```bash
npx explorbot config
```

## Authentification

Il n'y a pas de mécanisme d'auth dédié dans explorbot : pas de section `auth`,
pas de `login()`. Les identifiants vivent dans `knowledge/login.md`, dont le
frontmatter `url: /login` déclenche l'injection dans le prompt du Navigator dès
que la page de login est atteinte. L'agent remplit alors le formulaire comme
n'importe quel autre.

Le parcours réel sur cette app :

1. `/stands` est servi par le SPA — la route Angular n'est pas protégée côté
   serveur, seul `/api/*` l'est.
2. Le premier appel API répond 401 et `core/auth.interceptor.ts:24` route vers
   `/login`.
3. `waitForNavigation: 'networkidle'` laisse ce ping-pong se stabiliser avant le
   snapshot d'état, donc le knowledge matche.

Point à surveiller au premier run : le login est un **XHR**, pas un POST de
formulaire natif (`login-page.ts:53` poste sur `/j_security_check`, relit
`/api/auth/me`, puis `router.navigateByUrl('/')`). La règle intégrée du
Navigator suppose qu'une soumission de formulaire déclenche une navigation
serveur — ce n'est pas le cas ici. Si le login patine, la réponse est un bloc
`code:` dans `knowledge/login.md`, qui court-circuite le LLM sur cette étape.

### Garder la session entre les runs

`--session` mappe sur le `storageState` de Playwright : écrit en fin de run
(`explorer.ts:133`), relu à la création du contexte (`explorer.ts:368`).

```bash
npx explorbot freesail / --session auth.json     # 1er run : se logue, écrit le fichier
npx explorbot freesail / --session auth.json     # suivants : restaure, saute le login
```

Le fichier ressemble à ça — mais la valeur du cookie est un jeton signé par
Quarkus, elle ne s'invente pas : soit on laisse explorbot l'écrire, soit on la
copie depuis DevTools (Application → Cookies → `planning-session`).

```json
{
  "cookies": [
    {
      "name": "planning-session",
      "value": "eyJhbGciOi…",
      "domain": "localhost",
      "path": "/",
      "expires": 1787059200,
      "httpOnly": true,
      "secure": false,
      "sameSite": "Strict"
    }
  ],
  "origins": []
}
```

`--session` sans nom de fichier écrit dans `output/session.json`
(`explorbot.ts:131`) ; un nom relatif est résolu depuis le dossier de travail.
Comme `quarkus.http.auth.form.timeout=PT8H`, le fichier périme au bout de huit
heures : si explorbot se retrouve sur `/login` alors qu'il avait une session,
supprimer `auth.json` et relancer.

### Sur le mot de passe en clair

`knowledge/login.md` contient `admin` / `admin`, le défaut de dev. Explorbot sait
faire mieux : `${env.NOM}` dans un fichier knowledge est interpolé depuis
l'environnement (`knowledge-tracker.ts:156`), et si le nom de la variable
ressemble à un secret (`isSecretName` matche `password`, entre autres) la valeur
est masquée dans les logs et les prompts. Passer le fichier sur
`${env.ADMIN_PASSWORD}` le jour où on vise autre chose que le local.

## Lancer

| Commande | Ce qu'elle fait |
| --- | --- |
| `explore <path>` | reste sur une page et ses sous-pages, invente et joue des scénarios |
| `freesail [url]` | navigue en continu vers les pages qu'il découvre — le mode « toute l'app » |
| `start [path]` | ouvre la TUI ; on pilote à la main avec `/research`, `/plan`, `/test`, `/explore` |
| `plan` / `test` / `rerun` | générer un plan, le rejouer, le rejouer avec auto-réparation |

```bash
# une page, quelques tests
npx explorbot explore /stands --max-tests 5 --headless

# toute l'admin, en largeur
npx explorbot freesail / --shallow --max-tests 20 --headless --session auth.json
```

`--shallow` prend la page globalement la moins visitée, `--deep` va en
profondeur d'abord.

### Rester sur l'admin, ne pas toucher à l'espace animateur

`--scope` est un préfixe d'URL strict
(`freesail-command.ts:67` : `suggestion.target.startsWith(scope)`). Il ne sert à
rien ici : l'admin est à la racine (`/`, `/stands`, `/animateurs`…) et l'espace
animateur à `/animateur/<jeton>` — aucun préfixe ne dit « tout sauf `/animateur` ».

Ce qui protège en pratique, c'est la structure de l'app : les seuls liens vers
`/animateur/…` sont **dans le shell de l'espace animateur lui-même**
(`espace-animateur-shell.html:91,97,106`). Aucune page admin n'y renvoie, et le
jeton est opaque. Sur `/animateurs` il n'y a qu'un bouton « Copier le lien de son
espace », qui écrit dans le presse-papier sans jamais mettre l'URL dans le DOM.

Pour une garantie dure plutôt qu'une constatation, ajouter ce hook dans
`explorbot.config.js` :

```javascript
ai: {
  agents: {
    navigator: {
      beforeHook: {
        type: 'playwright',
        hook: async ({ page }) => {
          await page.route('**/animateur/**', (route) => route.abort());
        },
      },
    },
  },
}
```

### Garde-fous

`rules/tester/planning-equipes.md` interdit déjà : vider la base, lancer un
solve (plusieurs minutes, verrouille les référentiels), supprimer l'édition
courante. Il demande aussi de préfixer les créations par `EXPLORBOT-` et de lire
le snackbar plutôt que la fermeture du dialogue.

**Manque encore** : le bouton « Régénérer le lien de son espace » sur
`/animateurs`, qui invalide le lien d'un animateur réel.

L'instance de dev sur `http://localhost:8080` porte l'édition
`festival-hivernal` — 153 animateurs, 65 stands, 62 créneaux, des données
anonymisées mais réalistes. Explorbot écrit dedans. Un instantané depuis
`/instantanes` avant un run coûte dix secondes et rend le retour arrière trivial.

## Voir ce qu'il fait, en direct

```bash
npx explorbot freesail / --show --session auth.json
```

`highlightElement: true` est déjà forcé par explorbot (`explorer.ts:297`) : chaque
élément sur lequel il agit est entouré d'un cadre avant l'action. Il pose aussi
`waitForAction: 500`, une demi-seconde de pause après chaque action, ce qui rend
la séquence suivable à l'œil. Pour ralentir davantage, dans la section
`playwright` de la config :

```javascript
waitForAction: 1500,   // pause après chaque action (défaut 500)
slowMo: 300,           // ralentit chaque opération Playwright
```

Autres façons de regarder :

- **En headless, hors CI, le port 9222 est ouvert automatiquement**
  (`explorer.ts:276-281`) : `chrome://inspect` → *Configure* → `localhost:9222`
  donne le screencast de la page en direct. Attention, explorbot passe
  `--remote-debugging-address=0.0.0.0`, donc le port n'est pas limité au
  loopback — sans importance en local, à éviter sur un réseau ouvert.
- `npx explorbot browser start --show` lance un navigateur persistant auquel les
  runs s'attachent : la fenêtre ne se ferme plus entre deux runs.
- `--ws ws://127.0.0.1:8787` diffuse le run vers une UI maison. Explorbot compose
  en sortie (notre côté est le serveur) et envoie des frames JSON typées :
  `state`, `test`, `plan`, `screenshot`, `research`, `report`, `activity`, `log`,
  `ask`, `result`. On peut lui renvoyer `answer` et `interrupt`.

Pas de replay façon `playwright show-trace` : le `PlaywrightRecorder` démarre un
tracing, mais il s'en sert en interne pour reconstituer les locators du test
généré (`playwright-recorder.ts:114-126`), pas pour produire une trace
consultable.

## Coût

Explorbot **n'a aucune notion de coût**. Il compte les tokens
(`Stats.recordTokens`, `stats.ts:23`) et affiche en fin de run un tableau
`| Role | Model | Tokens |` — des volumes, jamais un prix. Pas de `maxCost`, pas
de budget, pas d'arrêt automatique.

Le plafond se pose donc chez le fournisseur :

- **OpenAI** — un *project* dédié avec une limite de budget, et la clé générée
  dans ce projet. Quand le plafond tombe, l'API renvoie
  `You have no credits remaining` et explorbot s'arrête net.
- **OpenRouter** — une clé peut être créée avec sa propre limite de crédit,
  c'est le plafond par clé le plus simple.

Les leviers côté explorbot pour faire durer un budget :

- `--max-tests N`, le plus direct
- `ai: { vision: false }` — coupe l'analyse de captures, appelée à chaque étape.
  Sur une app Material dont l'ARIA est propre, la perte est faible.
- le choix du modèle `model` : c'est lui qui relit le HTML à chaque pas.
  `agenticModel` ne lit que des logs courts, le garder intelligent coûte peu.
- `--incognito` — n'enregistre pas les *experiences*, qui sont réinjectées dans
  les prompts des runs suivants et grossissent avec le temps.

Pour calibrer : un run `--max-tests 3` donne le tableau des tokens, à multiplier
par le tarif du modèle.

## Sorties

Tout atterrit dans `output/` (ignoré par git) : `research/` (la carte de chaque
page vue par le Researcher), les plans de test, les specs générées, les captures,
et les rapports HTML/Markdown. Pour naviguer dedans sans fouiller le disque :

```bash
npx explorbot runs        # les fichiers de test générés
npx explorbot plans       # les plans sauvegardés
npx explorbot experience  # ce qu'il a appris, par URL
npx explorbot clean       # remet à zéro output/ + experiences
```

## Où on en est

Installation, configuration et fichiers knowledge/rules faits et validés.
Un premier run a tourné : le login passe du premier coup — le Navigator a trouvé
les champs par leur rôle ARIA, sans qu'on ait eu à écrire de bloc `code:` —

```js
I.fillField({ "role": "textbox", "text": "Utilisateur" }, 'admin');
I.fillField({ "role": "textbox", "text": "Mot de passe" }, 'admin');
I.click({ "role": "button", "text": "Se connecter" });
```

et le Researcher a cartographié `/stands`. Les deux traces sont dans
`experience/`. Rien n'a encore été exploré au-delà.

Attention si un run s'arrête sur `AI_APICallError: You have no credits
remaining` : ce n'est pas une erreur de configuration, c'est le plafond du
fournisseur. Recharger, ou basculer `EXPLORBOT_AI_PROVIDER` et poser la clé
correspondante dans `.env`.

À reprendre :

1. ajouter la règle sur « Régénérer le lien de son espace »
2. décider pour le hook `/animateur/**` et pour `vision: false`
3. un `explore /stands --max-tests 3` pour mesurer le coût réel d'un test sur
   cette app, puis passer en `freesail`

Ce dossier vit sur la branche `test/explorbot` et n'est pas commité : les
écritures git sont refusées par le classifieur de permissions de la session qui
l'a mis en place.
