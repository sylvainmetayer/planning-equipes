# Le frontend Angular

L'interface est une application Angular servie par le même Quarkus que l'API. Vous saurez comment elle est construite et amorcée, quelles règles de structure les écrans suivent, et quelques pièges qui coûtent cher quand on les redécouvre : le poll qui double le flux d'événements, le clavier, le thème, Leaflet.

## Un seul déploiement

L'extension Quinoa lance `npm ci && npm run build` pendant `mvn package` et copie le bundle dans les ressources statiques ([`application.properties`](repo:src/main/resources/application.properties), `quarkus.quinoa.*`). En production il n'y a pas de serveur Node. En `quarkus:dev`, Quinoa démarre `ng serve` et proxifie le port 4200 pour le rechargement à chaud.

Règle de structure : une question, un écran, ses variantes en onglets (`?onglet=`) ou en vues (`?vue=`) dans l'URL, chargés à la demande. On n'ajoute pas une section à une page existante. Le menu ([`shell/nav-groups.ts`](repo:src/main/webui/src/app/shell/nav-groups.ts)) est le même pour tout le monde et suit le cycle d'une édition ; une route peut être servie sans entrée de menu (Débogage), la palette Ctrl+K la trouve. Les services de `core/` portent l'état partagé et les appels HTTP ; les composants ne font pas de `fetch`.

L'état circule dans un seul sens : la page Solveur pousse le planning résolu, les calendriers le relisent en lecture seule et ne lancent jamais de résolution.

## Trois lectures avant `bootstrapApplication()`

[`main.ts`](repo:src/main/webui/src/main.ts) charge en parallèle le catalogue de traductions anglaises (seulement quand l'anglais est le choix retenu), `/api/config` et `/api/branding`, avant d'amorcer l'application. Le titre de l'onglet et le logo décident de la première image : aucun écran ne doit attendre un aller-retour pour savoir comment il s'appelle. Si le catalogue anglais ne se charge pas, l'application retombe sur le français plutôt que de rester blanche.

**Traduction à l'exécution.** Le français s'écrit en dur dans les modèles, `loadTranslations()` charge `messages.en.json`. Il n'y a qu'un build, sans bundle par langue, donc aucune configuration Quinoa supplémentaire. Les règles d'écriture (les deux moitiés d'une chaîne, `$localize` jamais au niveau d'un module) sont dans [les conventions du dépôt](conventions-du-depot.md).

**Marque.** Un `logoUrl` vide veut dire « n'affiche rien », jamais « affiche le logo par défaut » : une instance sert un client, et aucun n'hérite de la marque d'un autre. Une limite à connaître : `mat.theme()` compile toute la palette tonale dans le bundle, et la couleur d'accent configurable (`BRANDING_ACCENT_COLOR`) n'alimente que `--app-accent`. Recolorer les composants Material demande une recompilation, pas une variable d'environnement.

## Thème clair ou sombre

`mat.theme()` compile les deux palettes d'un coup : chaque couleur `--mat-sys-*` est une paire `light-dark(clair, sombre)`, et la propriété CSS `color-scheme` de l'élément racine décide laquelle est peinte. Il n'y a ni second thème ni feuille parallèle, rien à recompiler. L'application écrit `light dark` (« suivre la machine », le défaut), `light` ou `dark` sur `<html>`, et retient la préférence dans `localStorage` avec les autres préférences d'interface. `main.ts` la pose avant tout appel réseau, pour ne pas peindre une image dans le mauvais thème.

Deux couleurs échappent à `mat.theme()` et portent leurs propres paires `light-dark()` :

- la palette catégorielle des typologies, `styles/typologie-colors.css` : huit teintes qu'aucun `--mat-sys-*` ne fournit ;
- `--app-accent`, quand `BRANDING_ACCENT_COLOR` la renseigne (sinon elle vaut `--mat-sys-primary`, déjà une paire). Elle sert de couleur de texte sur `--mat-sys-surface` et d'anneau de focus ; [`core/branding.ts`](repo:src/main/webui/src/app/core/branding.ts) la pose donc en paire, chaque moitié bornée en OKLCH à teinte constante : clarté plafonnée en clair, bornée par le bas en sombre (avec un plafond de chroma). Les deux bornes, parce qu'un exploitant choisit sa couleur en regardant une surface et que l'autre est alors celle que personne n'a vérifiée : un accent pastel tombait sous 4,5:1 en clair. Un navigateur qui ne sait pas parser la paire garde la couleur brute.

Trois surfaces restent volontairement hors du thème :

- la barre d'outils de marque (administration, espace animateur) garde sa couleur d'enseigne : une identité qui change selon l'heure n'en est plus une. Elle porte du texte, donc sa paire `--app-toolbar-foreground` / `--app-toolbar-background` se mesure comme toute couleur écrite par le dépôt ;
- les cartes affichent des tuiles OpenStreetMap, du contenu et non du chrome ; les assombrir demanderait un autre fournisseur de tuiles. Pastilles, légende et liste autour suivent le thème ;
- les PDF sont composés côté serveur pour être imprimés : ils suivent la marque, jamais le thème du navigateur.

Le bouton de bascule ne vit que dans la barre d'administration. La page de connexion et l'espace animateur suivent la préférence enregistrée par ce navigateur, à défaut celle du système.

## Le poll n'est pas un vestige

L'état « un solveur tourne » n'est jamais stocké dans le navigateur : il est lu sur le serveur, si bien qu'une résolution lancée depuis une autre fenêtre verrouille aussi les boutons ici.

Il est lu de deux façons, à dessein. Le flux SSE `/api/jobs/stream` donne la latence : une résolution lancée ailleurs verrouille en une seconde au lieu de trente. La boucle de polling en dessous est le filet, car SSE échoue en silence et l'interface resterait figée sur un état périmé sans rien signaler, ce qui est pire qu'un poll, lequel se répare au tick suivant. Le poll garde la main tant que le flux n'a pas prouvé qu'il vit, descend à 30 s tant qu'il vit, et reprend la main après 45 s de silence.

Les deux moitiés sont deux fichiers. [`core/solver-stream.ts`](repo:src/main/webui/src/app/core/solver-stream.ts) tient la connexion `EventSource`, son chien de garde et son backoff, sans aucun état métier. [`core/solver-job.service.ts`](repo:src/main/webui/src/app/core/solver-job.service.ts) tient le poll et l'état des jobs, et fait passer ce que le flux pousse par le même corps qu'un tick de poll : c'est ce qui rend le repli honnête. Le raccord des deltas de la courbe de score est une fonction pure de `core/score-trace.ts`. Le côté serveur est dans [lancer, suivre et arrêter un calcul](api-calcul.md).

## Un seul écouteur clavier global

[`core/keyboard-shortcuts.service.ts`](repo:src/main/webui/src/app/core/keyboard-shortcuts.service.ts) porte l'unique `keydown` posé sur le document pour les raccourcis (Ctrl+K, `g` puis une lettre, `/`, `?`, Ctrl+Entrée). Il est armé et désarmé avec le shell d'administration : `/login` et l'espace animateur n'ont ni palette ni ces destinations. N'en ajoutez pas un second : deux écouteurs globaux se disputent la même frappe sans que rien le signale. La seule exception volontaire est le code Konami du shell, une séquence avec son propre état.

Les flèches des calendriers, de la grille du Planning et des tables de données ne sont pas des écouteurs globaux : elles sont posées sur l'élément du composant, ne consomment que les touches qu'elles utilisent et laissent le reste remonter jusqu'à l'écouteur global, qui s'arrête sur un événement déjà consommé. C'est ainsi qu'on ajoute un comportement clavier. Échap n'est écrit nulle part : aucun dialogue n'utilise `disableClose`, `MatDialog` ferme déjà le dialogue du dessus et rend le focus.

La table des `g`+lettre et les libellés de la palette vivent dans `core/keyboard-shortcuts.ts`, et les destinations sont dérivées de `app.routes.ts` : une page ajoutée demain est atteignable au clavier sans qu'on ait pensé à l'y inscrire.

### Les tables de données

Le `tabindex` mobile des cinq tables de référence, et des tables des ajustements manuels et des verrouillages (une seule ligne atteignable par Tab, flèches, Début/Fin, Entrée pour ouvrir, Espace pour cocher), est mutualisé dans [`core/table-navigation.ts`](repo:src/main/webui/src/app/core/table-navigation.ts), à côté de `core/table-selection.ts` qu'il pilote.

- On entre dans le tableau par le filtre. La ligne qui porte `tabindex="0"` se trouve derrière la case « tout sélectionner » et derrière un arrêt par en-tête triable : le nombre de Tab à taper changerait chaque fois qu'une colonne devient triable, et un geste qu'il faut deviner n'existe pas. Le geste documenté est donc : « / » saisit le filtre de la page, Flèche bas y entre. Ce `keydown` est posé sur le champ de `shared/table-filter.ts`, qui émet `enterTable` ; la page appelle `TableNavigation.focusCurrent()`. Rien de global n'est ajouté. La page des créneaux, sans filtre rapide, garde la seule entrée par Tab.
- Le focus s'ancre sur la ligne, pas sur son rang. Un tri ne le fait pas sauter ailleurs, et une ligne effacée par le filtre le repose sur la place qu'elle occupait en dernier, sans quoi plus aucune ligne ne porterait `tabindex="0"` et le tableau sortirait de l'ordre de tabulation. Ce dernier rang est la valeur précédente du signal lui-même (`linkedSignal`), pas un rang figé à la prise de focus : un tri passé entre-temps renverrait le focus à une position que la ligne a quittée.
- Entrée et Espace ne sont consommés que si quelque chose s'ouvre ou se coche. Une table sans sélection rend l'Espace à la page ; les créneaux refusent d'ouvrir pendant qu'une résolution verrouille l'édition, et la touche poursuit sa route, le bandeau de verrouillage disant déjà pourquoi.
- Pas d'`aria-selected` sur la ligne cochée. L'attribut n'est supporté sur `role=row` que dans une `grid`, alors qu'un `mat-table` expose `role="table"`. L'état accessible est celui de la case à cocher, le changement est annoncé par le `LiveAnnouncer` du CDK, et le surlignage passe par une classe.
- Un en-tête triable ne se nomme pas tout seul. Material rend la cellule d'en-tête dans un `role="button"` qu'il ne nomme jamais, et le calcul du nom accessible descend dans les `aria-label` des contrôles imbriqués : rendre une colonne triable a suffi pour que les six lignes d'explication de son bouton d'aide deviennent le nom du bouton de tri. `shared/sort-header-name.ts` coupe ce couplage par un `aria-labelledby` vers le titre de la colonne, et n'est nécessaire que sur un en-tête contenant autre chose que son titre. `sortActionDescription` alimente `aria-describedby`, qui décrit l'action sans toucher au nom ; masquer le bouton d'aide le rendrait invisible au lecteur d'écran alors qu'il est focalisable.

La grille à deux axes du Planning (par stand, par personne) garde sa navigation propre : sa géométrie n'est pas celle d'une liste.

## Leaflet reste hors du bundle initial

Deux endroits affichent une carte : l'onglet « Lieux » de `/stands` (tous les lieux, un marqueur déplacé à la souris qui enregistre sa position, la saisie d'un point dans le formulaire d'un lieu) et la vue « Carte » de `/journee`. Le premier est dans un bloc `@defer`, le formulaire d'un lieu est chargé par un `import()` quand un stand propose « Nouveau lieu… », la seconde dans un `@defer` de la page. Leaflet pèse à lui seul un morceau de 150 ko : rien de chargé au démarrage ne doit l'importer, sinon ce poids passe dans le bundle de tout le monde, page de connexion comprise. Ce que les cartes partagent (tuiles OpenStreetMap, attribution, politique de `Referrer` qu'elles exigent, chemins d'icônes qui survivent au bundle) vit dans `shared/leaflet-base.ts`, jamais recopié.

## Le seul endroit où le frontend réimplémente du domaine

`app/core/horaire-stand.ts` résout les règles d'horaire d'un stand en fenêtres jour par jour, en miroir de `HoraireStandResolver` côté serveur, pour prévisualiser une règle sans aller-retour. Il se fait évoluer dans le même commit que son pendant Java ([horaires et fenêtres des stands](horaires-et-fenetres-des-stands.md)).

## CSS

[`src/styles.css`](repo:src/main/webui/src/styles.css) n'est qu'un agrégateur d'`@import`, avec un fichier partiel par écran sous `src/styles/`. `branding.css` est importé en premier : tous les autres lisent `--app-accent`.
