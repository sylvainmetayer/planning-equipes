# Serveur MCP

`POST /mcp` (streamable HTTP) et `GET /mcp/sse` (rétrocompatibilité), fournis
par `quarkus-mcp-server-http`. **La liste des outils est celle que le serveur
annonce lui-même** — le code du paquet `mcp` en est la source. Ce document porte
les règles qui ne s'y lisent pas.

Périmètre retenu : tous les endpoints de l'organisation peuvent être exposés,
**la seule restriction est la confidentialité des données animateur**. Ce qui
reste dehors est énuméré en fin de document, avec sa raison.

## Authentification : clé API partagée

L'application n'a pas de modèle utilisateur, donc pas d'OAuth2 — il supposerait
un consentement qui n'existe pas ici. La clé se présente dans un en-tête
configurable (`X-MCP-Api-Key` par défaut) ou en `Authorization: Bearer`.

**Sécurisé par défaut** : tant que `PLANNING_MCP_API_KEY` n'est pas positionnée,
`/mcp` répond 401 systématiquement. Le serveur MCP peut vider la base, modifier
les référentiels et lancer une résolution : une exposition non authentifiée
serait pire qu'un refus de servir.

**La clé, et rien d'autre.** La politique de `/mcp` exige le rôle `mcp`, que
seule l'identité construite à partir de la clé porte. Une politique
`authenticated` laissait passer la session admin : l'authentification par
formulaire lit son cookie sur tous les chemins, et un navigateur connecté
atteignait `/mcp` sans clé. Il reçoit désormais `403`.

> **Un filtre Vert.x ne suffit pas.** Les routes de `quarkus-mcp-server-http`
> sont enregistrées **en amont** de la chaîne de filtres standard : un bean
> `Filter` ne voit jamais ces requêtes. D'où le passage par le moteur de
> politiques `quarkus.http.auth.permission.*`, qui s'applique à tout chemin
> quelle que soit l'extension qui l'a monté.

## Derrière un proxy d'accès

`PLANNING_MCP_REQUIRED_HEADERS` exige des paires `Nom=valeur` **en plus** de la
clé — comparaison exacte, à temps constant. C'est de la défense en profondeur :
elle protège l'origine si celle-ci reste joignable sans passer par le proxy.
Facultative, vide par défaut.

Deux pièges :

- ne lister que des en-têtes que le proxy **retransmet** ; un proxy qui les
  consomme et les retire ferait échouer toutes les requêtes ;
- une entrée sans `=`, ou à valeur vide, n'est **jamais** satisfaite — échec
  fermé, cohérent avec la clé. Ce n'est pas un moyen d'exiger la simple présence
  d'un en-tête.

Côté client, préférer l'en-tête dédié à `Authorization: Bearer` : **un proxy
d'accès consomme fréquemment `Authorization` pour son propre compte**, et la clé
n'arriverait jamais à l'application.

La page MCP de l'interface détecte le proxy via l'en-tête de réponse
`X-Pangolin`. Pangolin ne l'ajoute pas lui-même — son réglage « en-têtes
personnalisés » ne modifie que la requête vers le backend, pas la réponse au
navigateur. `PangolinHeaderFilter` renvoie donc sur la réponse tout `X-Pangolin`
reçu sur la requête : il suffit de le déclarer côté proxy.

## Confidentialité des données animateur

**Nom, prénom et date de naissance ne sortent jamais par MCP.** Sortent l'id, le
statut majeur/mineur et moins-de-16-ans — dérivés de la date, jamais la date —
et les attributs de planification non identifiants.

Quatre mécanismes :

1. **Des vues, jamais les objets de domaine.** Chaque outil renvoie un `record`
   dédié qui ne possède structurellement aucun accesseur vers un champ
   personnel.
2. **Anonymisation des messages d'écart.** `ViolationFormatter` désigne un
   animateur par « Prénom Nom (id) » et une contrainte ad hoc par
   « TYPE id (raison) » pour l'interface web ; `AnonymisationViolations` réécrit
   en « animateur id » et retire la raison avant toute sortie MCP. Elle part des
   fiches de l'édition : le libellé exact de chaque animateur connu est
   remplacé, quelle que soit la forme du nom (un seul mot, une initiale, un
   chiffre). Une reconnaissance par la forme ne vient qu'ensuite, pour ce que
   le référentiel ne connaît plus — une ligne enregistrée peut survivre à la
   fiche qu'elle nomme. Son pire cas est un libellé abîmé, jamais un nom qui
   sort ; un stand connu dont le nom a cette forme est épargné.
3. **Les textes libres sortent en « renseigné ou non ».** Le motif d'une
   demande d'échange, le commentaire d'une déclaration, la réponse de
   l'organisation, la raison d'un verrouillage ou d'une contrainte ad hoc :
   c'est là qu'un nom ou un rendez-vous médical revient (« je remplace
   Marie D. »), et aucun filtre ne le distingue d'une phrase anodine. Les vues
   portent `motifRenseigne`, `commentaireRenseigne`, `raisonRenseignee`…
   L'écriture reste ouverte : refuser une demande ou une déclaration prend
   toujours le commentaire que l'animateur lira. Le motif de fermeture d'un
   stand sort tel quel — il décrit un stand, pas une personne.
4. **Un test structurel.** `McpConfidentialiteStructurelleTest` parcourt par
   réflexion tous les `@Tool` et échoue si l'un expose `Animateur` ou un champ
   `prenom` / `dateNaissance` / `email` / `accessToken` / `commentaire` /
   `commentaireAdmin` / `raison`, **y compris à travers les génériques**. Un
   nouvel outil mal filtré casse le build sans qu'on ait à y penser.

**Corollaire côté écriture** : `modifier_animateur` fusionne au lieu de
remplacer, contrairement au `PUT` REST. Un assistant qui ne peut pas lire
nom/prénom/date de naissance ne peut pas les renvoyer : un remplacement complet
les effacerait à chaque modification.

**Une seule porte d'écriture, quel que soit le canal.** Les outils qui créent
ou modifient un animateur, un stand ou un créneau passent par le même
`write*` de `ReferenceDataService` que les resources REST — celui qui relit la
fiche avant d'écrire, note pour l'historique les champs qui ont bougé, et
rend les avertissements de cohérence. Une ligne du journal écrite par un
assistant porte donc sa colonne « champs » comme une ligne écrite depuis un
écran (audit #392, A6 : elle était vide). Les avertissements traversent en
**codes** (`INDISPONIBILITE_HORS_EVENEMENT`, `MINEUR_PENDANT_EVENEMENT`…) et
non en phrases : le message d'un avertissement sur un animateur date sa
majorité, c'est-à-dire sa date de naissance décalée de dix-huit ans.
`LayeringStructuralTest` interdit un `create*` / `update*` nu hors de la
façade.

## Divergences volontaires avec le REST

Chacune répond à la même question : l'appelant est un assistant, pas un écran
qui vient d'afficher la donnée.

| Outil | Ce qu'il fait autrement | Pourquoi |
| --- | --- | --- |
| `modifier_animateur` | fusionne au lieu de remplacer | voir le corollaire ci-dessus |
| `deverrouiller` | échoue sur un id inconnu, là où `DELETE /api/verrouillages/{id}` répond 204 | un écran vient de lister les verrouillages et sait que la ligne existait ; un assistant travaille sur des ids qu'il a pu inventer, et « supprimé » sur un verrouillage inexistant lui ferait croire le planning libre de bouger |
| `retirer_validation_journee` | échoue sur un id inconnu, là où `DELETE /api/validations/{id}` répond 404 de la même façon | même raison que `deverrouiller` : « retirée » sur une relecture qui n'existait pas laisserait croire la journée revenue à relire |
| `capturer_instantane`, `restaurer_instantane` | lèvent une erreur là où le REST renvoie un 409 avec un corps | une réponse d'outil que l'assistant lit comme un succès ne doit pas être celle qui dit que rien n'a été écrit |
| `simuler_deplacement`, `deplacer_affectation` | un seul outil pour les trois gestes du glisser-déposer (#308), la cible étant un poste ou une personne | l'écran a un pointeur et sait où il dépose ; l'assistant nomme ce qu'il vise, et le serveur choisit entre déplacer, échanger et attribuer — puis refuse ce qui casserait une règle dure, comme pour l'écran |
| `lancer_solveur` | repart du plan enregistré par défaut (`reamorcage=AUTO`), comme l'écran | un assistant qui relance « pour voir » détruirait sinon en silence la qualité déjà atteinte (#174) ; le départ à froid se demande, `reamorcage=AUCUN` |
| `modifier_stand`, `modifier_animateur`, `modifier_creneau`, `modifier_emplacement`, `modifier_typologie` | prennent un `modifieLe` **facultatif**, là où l'écran renvoie toujours celui qu'il a chargé | la fusion relit la fiche juste avant d'écrire, donc sans argument le contrôle de modification concurrente (#362) ne dit rien ; un assistant qui a lu la fiche plus tôt (`consulter_*`) et veut être refusé si elle a bougé depuis passe la valeur lue |
| `lister_affectations`, `consulter_instantane` | plafonnent la liste (200 par défaut) et annoncent le total | un planning réel porte plusieurs milliers de postes ; le total à côté de la liste est ce qui rend la troncature lisible, plutôt qu'un plafond caché |
| `lister_animateurs`, `lister_stands` | acceptent une `limite` mais ne plafonnent rien par défaut | leur taille est celle du référentiel, pas celle du planning : l'appelant qui les demande les veut en général en entier |
| `diagnostiquer_plan` | lève une erreur sans planning persisté, là où `POST /api/constraints/diagnostic` renvoie la vue vide | l'écran a un état vide permanent qui dit déjà « aucune analyse » ; une structure vide rendue à un assistant se lit comme « aucune contrainte en défaut » |
| `publier_planning`, `configurer_collecte_disponibilites` | comptent les personnes sans adresse et les échecs d'envoi, là où le REST les **nomme** | ces listes existent pour un écran qui affiche déjà les fiches ; `lister_destinataires_publication` redonne le détail par id, qui est ce que les autres outils prennent en entrée |

**La question « où ça coince ? » ne passe pas par la liste.**
`synthese_affectations` répond en quelques dizaines de lignes — postes pourvus
au total, par stand et par jour — là où `lister_affectations` dépenserait le
contexte entier de l'assistant avant qu'il ne voie qu'un stand est en
sous-effectif le samedi.

## Un refus métier revient comme refus, pas comme « Internal error »

Un `BusinessError` levé par le domaine repartait en erreur JSON-RPC générique
(`{"code": -32603, "message": "Internal error"}`) : la phrase qui dit ce qui a
été refusé — et souvent comment le corriger — était perdue sur les 132 outils.
Un assistant ne pouvait ni corriger son appel, ni distinguer « tu as mal
saisi » de « le serveur est cassé » : il réessayait à l'identique.

`RefusMetierInterceptor` est le pendant MCP de `api/BusinessErrorMapper`, et il
suit la même règle : **seul un refus délibéré passe par là**. Un `BusinessError`
revient comme résultat d'outil en erreur (`isError`) portant sa phrase ;
n'importe quoi d'autre — un `IllegalArgumentException` involontaire, une panne
de base — garde son « Internal error » et son alerte, exactement comme le REST
garde son 500. Le type qui distingue les deux est ce qui rend la distinction
possible des deux côtés.

Il est lié aux classes d'outils par `@RefusMetier`, une troisième annotation à
côté de `@EditionCiblee` et `@Journalise` plutôt qu'un ajout à l'une d'elles :
ce sont trois questions différentes, et `SauvegardeMcpTools` ne porte pas
`@EditionCiblee`. Il s'applique **en dehors** de `EditionCibleeInterceptor`,
si bien que le refus « Édition inconnue « X ». Éditions disponibles : … » — levé
avant même le corps de l'outil — arrive lui aussi.
`McpRefusMetierStructurelleTest` échoue sur toute classe d'outils qui oublie le
binding.

**La phrase traverse telle quelle, et c'est seulement sûr parce qu'aucun refus
ne nomme quelqu'un.** La faire passer par `AnonymisationViolations` était
l'autre piste : cette réécriture vise la forme « Prénom Nom (id) » des lignes
d'écart, et un refus comme « Deux horaires de même portée (MONDAY,TUESDAY)… »
la déclenche par accident. La règle est donc tenue à la source —
`McpRefusMetierStructurelleTest` échoue sur un `BusinessError` construit à
partir du nom, du prénom, de la date de naissance ou de l'adresse d'un
animateur. Le seul refus qui nommait une personne, « *Prénom Nom* n'a pas
d'adresse e-mail sur sa fiche », est désormais écrit par id dans
`PlanningDeliveryService` : l'écran ne perd rien — c'est la fiche de cet
animateur qui porte le bouton — et `envoyer_planning_animateur` n'a plus à
remplacer la phrase à la main, ce que ce traitement transverse rend inutile.

## L'édition se désigne argument par argument

Une requête MCP n'est pas une requête JAX-RS : `EditionHeaderFilter` ne la voit
jamais, et **`X-Edition-Id` n'a aucun effet sur `/mcp`**. Chaque outil porte un
argument `edition` facultatif, qui accepte l'id ou le nom. Omis, l'outil
travaille dans l'édition par défaut.

**Une édition inconnue échoue**, au lieu de retomber sur la courante — seule
divergence volontaire avec l'en-tête HTTP. Les deux appelants ne sont pas dans
la même situation : un onglet resté ouvert sur une édition supprimée doit
continuer d'afficher ses écrans, alors qu'un assistant qui nomme une édition
s'apprête à y écrire, et un repli silencieux enverrait l'écriture dans la
mauvaise édition sans que rien ne le signale. Le message énumère les éditions
existantes.

Trois pièces : `@EditionArg` marque l'argument porteur — c'est l'annotation, pas
le nom, qui fait le lien ; `@EditionCiblee` se pose sur la **classe** d'outils,
si bien qu'un outil ajouté plus tard en hérite au lieu d'écrire silencieusement
dans l'édition par défaut ; `EditionCibleeInterceptor` lie l'édition autour de
l'appel.

> **Piège d'auto-invocation.** Un outil qui en appelle un autre sur `this`
> court-circuite l'intercepteur. Il doit passer par un helper privé, jamais par
> l'outil voisin.

`McpEditionStructurelleTest` échoue sur tout `@Tool` qui travaille dans une
édition sans laisser la désigner. Sept exemptions : les deux outils de scénario
(fichiers livrés, validation pure), les quatre de pilotage des jobs — le
registre est global, chaque job portant l'édition pour laquelle il a été lancé —
et `lister_kpi_historique`, délibérément non cloisonné pour qu'une édition se
compare à la précédente.

Deux précisions sur l'écriture : les lancements de solveur capturent l'édition
**à la soumission**, donc le job y reste attaché même si le défaut change
ensuite ; et une section `edition:` dans un YAML importé **prime** sur
l'argument, puisque le fichier désigne explicitement sa cible.

## Les résolutions construisent leur problème au démarrage

`lancer_solveur` et `resoudre_incremental` passent par les soumissions
**rejouables** du `SolverJobService`, exactement comme les écrans. Le problème
n'est donc pas construit à l'appel mais au démarrage du job, ce qui apporte
trois propriétés d'un coup :

- **la mise en file** (`enFile`) : sans elle, un solveur occupé refuse la
  demande. Un job qui porte son problème tout construit ne peut pas attendre —
  il résoudrait l'édition telle qu'elle était avant l'attente ;
- **le rejeu au redémarrage** : un job en file survit à un arrêt du serveur ;
- **la fraîcheur** : un assistant qui continue de préparer l'édition pendant
  qu'une résolution tourne verra ses modifications prises en compte par la
  suivante.

`diagnostiquer_plan` ne passe pas par là du tout : il ne résout rien. Il
recalcule le score du **plan persisté**, celui dont parlent `etat_planning` et
`lister_affectations`. Il remplace un outil d'analyse qui lançait une résolution
complète et en jetait le résultat : le budget d'un solve pour décrire un
planning que rien ni personne n'aurait affiché ensuite.

Le périmètre de `resoudre_incremental` (animateurs, jours, stands) **ne touche
pas aux verrouillages** : il ne vaut que pour ce job. Ce qui est figé
durablement se pose avec `verrouiller`.

## Chaque outil annonce ce qu'il fait aux données

Les quatre *hints* de la spécification MCP sont déclarés sur **tous** les
outils. Ce n'est pas cosmétique : leurs valeurs par défaut sont
`destructiveHint = true` et `openWorldHint = true`, si bien qu'un outil qui ne
dit rien — `lister_stands` par exemple — s'annonce comme un appel destructif
vers l'extérieur. Un client qui demande confirmation avant les outils
destructifs la demandait alors pour chaque lecture, et le signal ne voulait
plus rien dire.

| Hint | Ce qu'il vaut ici |
| --- | --- |
| `readOnlyHint` | vrai pour les outils qui ne font que lire |
| `destructiveHint` | vrai pour les suppressions, les imports, une restauration d'instantané — et pour les résolutions, qui remplacent le planning persisté |
| `idempotentHint` | vrai quand rappeler l'outil avec les mêmes arguments ne change plus rien |
| `openWorldHint` | vrai pour les **quatre outils qui envoient du courriel** (`publier_planning`, `envoyer_planning_animateur`, `relancer_animateurs`, `configurer_collecte_disponibilites` quand elle prévient les animateurs) ; faux partout ailleurs, aucun autre outil ne sort de la base de l'application |

`McpAnnotationsStructurelleTest` tient les deux bouts. Le `openWorldHint = false`
sert de marqueur — un bloc oublié garde la valeur par défaut et échoue — et les
hints attendus sont dérivés du **nom** de l'outil, si bien qu'un
`supprimer_stand` qui se déclarerait en lecture seule échoue aussi. Un nom que
le test ne sait pas classer échoue également : un nouvel outil ne passe pas
sans que quelqu'un ait dit ce qu'il fait.

Les quatre outils qui envoient du courriel sont **énumérés** dans ce test, jamais
déduits d'un nom : un envoi sortant est une décision que quelqu'un prend, et
inscrire un outil dans cette liste *est* cette décision, relue. Ils ne perdent
pas pour autant le filet du bloc oublié, puisque leur `destructiveHint` reste
confronté à leur nom.

## Quatre outils font sortir un courriel

Ce sont les seuls, et ce sont les seuls à s'annoncer `openWorldHint = true` :
`publier_planning`, `envoyer_planning_animateur`, `relancer_animateurs`, et
`configurer_collecte_disponibilites` lorsqu'elle coche l'invitation. Un client
qui veut faire confirmer ce qui quitte l'application a de quoi le repérer.

**Une seule lecture pour savoir où en est l'édition.** `etat_edition` rend la
checklist du cycle que la page d'accueil affiche — référentiels, collecte,
ouvertures, besoin, dernière résolution, problèmes, publication, accusés de
réception, foire — chaque ligne avec son statut (`A_FAIRE`, `ATTENTION`,
`INFO`, `FAIT`) et les chiffres qui le décident, calculés côté serveur et donc
identiques pour l'écran et pour l'assistant. `INFO` porte des chiffres à lire
dont aucun ne bloque le cycle : des avertissements sans bloquant, par exemple,
qu'un planning réel n'évite jamais complètement. C'est l'outil à appeler en
premier ; les outils plus fins (`etat_planning`, `etat_publication`, `analyser_effectifs`…)
détaillent ensuite la ligne qui pose question. Une résolution en cours s'y lit
comme un statut, elle ne fait pas échouer l'appel. Aucune donnée nominative :
des comptes et des dates, jamais une liste de noms.

**Deux dates, deux questions.** `etat_planning` dit quand le solveur a tourné
pour la dernière fois ; `etat_publication` dit quand les animateurs ont été
prévenus pour la dernière fois. La seconde est la seule qui décrive ce que les
gens ont sous les yeux : un planning résolu ce matin et publié la semaine
dernière est, pour eux, celui de la semaine dernière. `etat_publication` répond
aussi qui serait concerné par la prochaine publication, et pourquoi — sans rien
envoyer, comme l'écran.

`publier_planning` capture d'abord l'instantané publié, puis écrit aux gens :
un envoi qui échoue à mi-chemin laisse un planning publié cohérent et une trace
qui dit qui a été manqué. C'est cette trace que relit
`lister_destinataires_publication`, par id.

**Qui n'a pas répondu, et les relancer.** `synthese_confirmations` compte, parmi
les animateurs qui ont un poste sur le planning publié, les confirmés, les
relancés et les silencieux, et date la publication à laquelle ils répondent —
`jamaisPublie` vrai veut dire que la question n'a encore été posée à personne.
`relancer_animateurs` envoie aux ids désignés le rappel « confirmez-vous votre
planning ? », le même que la relance automatique de nuit, sans l'attendre. La
règle est celle de la nuit : **une seule relance par personne et par
publication**, quelle que soit la main qui l'envoie — l'outil réserve la même
clé que le job nocturne, donc quelqu'un relancé par l'assistant ne l'est pas
une seconde fois la nuit suivante, et quelqu'un que la nuit a déjà écrit
revient dans `dejaRelancesPourCettePublication` au lieu de recevoir un second
message. Le compte rendu ne porte que des ids : `envoyes`, `dejaConfirmes`,
`sansEmail`, `dejaRelancesPourCettePublication`, `echecs` (un envoi qui a
échoué est compté, pas avalé) et `sansPoste`. Refusé si rien n'a jamais été
publié ou si un id est inconnu — rien ne part alors, pas même aux ids valides.

## Décider ce que les animateurs ont demandé

Les déclarations de disponibilité et les demandes d'échange arrivent de
l'espace animateur et attendent une décision. Les outils les listent, les
chiffrent et les tranchent côté organisation — `appliquer_declaration_disponibilite`
écrit la déclaration sur la fiche par le même `write*` que l'écran de saisie, donc
les données de référence sont marquées modifiées, `etat_planning` signale que
le planning résolu est périmé, la ligne du journal porte les champs remplacés
(`joursIndisponibles`, `souhaits`), et l'outil rend les avertissements de
cohérence en codes, comme `modifier_animateur` — une indisponibilité sur un
jour sans créneau est le cas type d'une déclaration appliquée.

`accepter_demande_echange` est, avec `affecter_poste`, l'un des deux seuls
outils qui écrivent dans le planning résolu sans passer par le solveur : il
applique l'échange puis le fige par des verrouillages `ANIMATEUR_CRENEAU`.
D'où `analyser_impact_echange` à côté : la prévalidation stockée avec la demande
décrit le planning du jour où elle a été envoyée, pas celui d'aujourd'hui.

## Retoucher un poste sans relancer le solveur

`suggerer_reparations` cherche qui pourrait tenir un poste et chiffre chaque
candidat ; `affecter_poste` applique le choix. Ce sont deux outils et non un
seul avec un drapeau : un assistant qui évalue un mouvement et l'applique dans
le même appel ne laisse plus aucune étape à laquelle un humain puisse dire non.

`affecter_poste` est le seul outil qui écrit dans le planning résolu lui-même,
et il **refuse un poste verrouillé** — le verrou est ce qu'on lui demande de
respecter, il ne doit pas pouvoir passer dessus en silence.

## Le serveur ne sert pas que des outils

Deux autres familles MCP sont servies, parce qu'elles portent ce qu'un outil ne
peut pas dire.

**Quatorze prompts**, un par moment du cycle, dans l'ordre d'un vrai
événement : saisir les horaires des stands, construire la grille de créneaux,
traiter les déclarations de disponibilité, savoir où recruter ou former,
vérifier une édition avant de résoudre, résoudre sans perdre le planning en
place, diagnostiquer les contraintes dures, verrouiller ce qui tient, préparer
une variante de repli, auditer avant diffusion, publier le planning, trancher
les demandes d'échange, reprendre après un changement tardif, tenir le jour J.
Chacun prend un argument `edition` facultatif et enchaîne les outils dans le
bon ordre.

Deux d'entre eux disent ce que les outils ne peuvent pas faire, et c'est
délibéré : l'import de la matrice des stands et la grille de saisie vivent dans
l'interface, et marquer une absence le jour J aussi. Un prompt qui tairait ces
limites enverrait l'assistant chercher un outil qui n'existe pas — la lecture
« Fragilité du planning » est dans le même cas.

Ceux qui arrivent après la résolution nomment parfois un outil qui envoie du
courriel : ils exigent alors un accord explicite avant l'appel.
`McpPromptsWordingTest` le vérifie sans énumérer quoi que ce soit — il relit les
outils dont `openWorldHint` est vrai, si bien qu'un futur outil sortant cité par
un prompt muet fait échouer le build.

La page MCP de l'interface **ne les recopie pas** : elle les lit sur
`GET /api/mcp/prompts`. Elle portait auparavant ses propres textes, et l'un
d'eux avait dérivé vers un outil que cette application n'a jamais exposé —
personne ne pouvait s'en apercevoir, un prompt n'étant que de la prose jusqu'à
ce que quelqu'un le colle. Un seul texte, une seule source, quel que soit le
chemin par lequel un client l'atteint.

Conséquence assumée : ces textes ne sont **pas traduits**. Le catalogue i18n
porte les libellés autour d'eux, pas les prompts eux-mêmes, qui restent en
français comme le vocabulaire métier. `McpToolNamesTest` les lit et échoue si
l'un d'eux nomme un outil inexistant.

**Deux ressources**, à attacher une fois pour informer tous les appels suivants :

| URI | Contenu |
| --- | --- |
| `planning://contraintes` | le catalogue des contraintes, **généré depuis `ConstraintCatalog`** : c'est la liste que le solveur applique, elle ne peut donc ni décrire une règle absente ni en oublier une ajoutée la veille |
| `planning://vocabulaire` | stand, créneau, poste, vacation, journée type, typologie, édition — et l'ordre dans lequel on prépare un événement |

Le vocabulaire est le seul texte écrit à la main ici : il résume
`docs/domaine.md` pour un lecteur qui ne verra jamais le modèle Java.

## Les fenêtres d'un stand portent leur effectif, les pauses sortent sans nom

Une fenêtre d'ouverture — dans `ajouter_horaire_stand`, `creer_stand_complet` et
`ajouter_ouverture_stand` — peut nommer l'effectif à pourvoir sur elle : le
suffixe `@N` de la syntaxe des fenêtres (`« 10:00-12:00@2,14:00-@4 »`), ou
l'argument `effectif` d'une ouverture datée. Sans lui, la fenêtre reprend
l'effectif minimum du stand, comme avant. Zéro est refusé : un stand sur lequel
personne ne doit être est une fermeture. Le suffixe n'a de sens que sur un
stand ; sur la récurrence d'un créneau (`creer_creneaux_recurrents`) il est
rejeté. Les vues renvoient l'effectif de chaque fenêtre et de chaque ouverture,
`null` quand il hérite.

`analyser_pauses` lit le planning persisté sous les paramètres légaux courants
et dit, par animateur et par jour, où tombe la pause due — de quelle heure à
quelle heure, dans la rotation du stand —, sur quel stand, et qui peut relayer. Là où l'écran et le planning individuel nomment les
collègues, l'outil ne rend que des **ids** — `relaisAnimateurIds` — comme tout
ce qui traverse MCP. Les trous déjà planifiés par la grille ne sont donnés
qu'en l'absence de filtre par stand ou par relais : ils appartiennent à la
journée, pas à un stand. La déclaration `pauseSurPoste` se lit et se règle par
`consulter_parametres_legaux` / `modifier_parametres_legaux`.

## L'équité se lit par id

`equite_planning` rend le tableau de l'écran Équité sur le planning persisté :
une ligne par animateur affecté — heures totales et par semaine ISO, heures de
soirée (après l'heure `heureDebutSoiree` des paramètres légaux), de week-end et
de jour férié, postes et postes pénibles, stands, typologies et emplacements
distincts, part des postes sur une typologie souhaitée ou appréciée, jours
travaillés, jours de repos, plus longue série — et, par colonne, la médiane, le
minimum, le maximum et l'écart-type. `colonnesSolveur` dit quelles colonnes une
règle du solveur mesure et si cette règle est active : une colonne que le
solveur ne pèse pas est une information pour l'assistant, pas un défaut de la
résolution. Là où l'écran et l'export CSV nomment les personnes, l'outil ne
rend que des ids. L'heure de début de soirée se lit et se règle par
`consulter_parametres_legaux` / `modifier_parametres_legaux`.

## Une grille est faite de vacations, et ça ne se demande plus

`valider_creneaux`, `previsualiser_creneaux_recurrents`,
`creer_creneaux_recurrents`, `previsualiser_derivation_creneaux` et
`generer_creneaux_depuis_stands` prenaient un argument `mode` — `AMPLITUDES` ou
`VACATIONS` — parce que le verdict en dépendait. Le découpage des amplitudes
est retiré (ADR
[0037](decisions/0037-une-grille-est-toujours-des-vacations.md)), l'argument
avec lui : un créneau est une vacation, et deux qui se chevauchent le même jour
sont deux relèves décalées, jamais une anomalie.

Ce que le contrôle signale toujours : une vacation au-delà de
`dureeVacationMaxMinutes` (6 h par défaut, `consulter_parametres_legaux`), une
vacation que le repos quotidien rend intenable, un relais repas hors fenêtre, un
doublon, un trou, une date isolée.

`diagnostiquer_grille_creneaux` reste le premier appel utile pour découvrir une
édition : combien de vacations, sur quelles dates, avec combien de relais
repas. Il proposait aussi un mode assorti d'un indice de confiance ; il n'y a
plus de mode à proposer.

## La grille peut découler des stands

Quand les stands ont déjà leurs horaires, `previsualiser_derivation_creneaux`
montre la grille qu'ils impliquent — une coupure à chaque heure où un stand
ouvre ou ferme — et `generer_creneaux_depuis_stands` l'écrit, en ajout par
défaut, ou à la place de la grille avec `remplacer=true` (le planning résolu
part avec elle). Le prompt
`construire_la_grille_de_creneaux` la propose avant la récurrence.

`creer_creneau` et `modifier_creneau` prennent un `couverturePause`
facultatif — le relais repas, qui garde le stand ouvert à la moitié de son
effectif arrondie au supérieur — et les vues de créneau le rendent.

## Les journées types se définissent en une ligne

`definir_journee_type` crée ou remplace une journée type par son nom, ses
vacations sur une ligne — « 09:00-12:00, 12:00-13:00 R, 13:00-14:00 R,
14:00-20:00 », `R` marquant un relais repas aux sièges divisés par deux —
et `affecter_journee_type` la pose sur une plage ou des dates ; les autres
dates du calendrier sont conservées, `retirer_dates_journee_type` en
retire. Rien de tout cela n'écrit un créneau :
`previsualiser_application_journees_types` dit ce qu'appliquer changerait
(conservés, mis à jour, créés, supprimés avec les sièges qu'ils portent) et
`materialiser_journees_types` l'écrit, puis déclare la grille en `VACATIONS`.
Une date sans journée type n'est pas touchée. `reconnaitre_journees_types`
fait le chemin inverse depuis les créneaux, et remplace journées types et
calendrier ; `previsualiser_reconnaissance_journees_types` le montre sans rien
écrire. `lister_journees_types`
rend aussi les dates **en écart**, celles dont les créneaux ne suivent plus
leur journée type. Voir
[ADR 0032](decisions/0032-journees-types-nommees-vacations-fixes.md).

## Hors périmètre, volontairement

| Ce qui n'a pas d'outil | Pourquoi |
| --- | --- |
| Export de scénario YAML | Contient prénom, nom et date de naissance — il doit être réimportable |
| Dump SQL (export et import) | Toutes les données personnelles, et un fichier volumineux destiné à une sauvegarde, pas à une conversation |
| Exports PDF / ICS / CSV | Binaires ou nominatifs, destinés au téléchargement depuis l'interface |
| `/api/config` | Clés publiques destinées au navigateur : aucun intérêt pour un assistant |
| `/api/planning/sample`, `/persisted` | Un `PlanningEvenement` entier, plusieurs dizaines de Mo avec les données personnelles. `lister_affectations` couvre le besoin en restant filtré |
| Import CSV des bénévoles | Le tableur porte nom, prénom, date de naissance et adresse : un outil qui l'accepterait ferait entrer par MCP exactement ce qui n'en sort pas |
| Espace animateur (`/api/espace-animateur/{jeton}`, abonnement ICS) | Le côté animateur du produit, derrière un jeton personnel. MCP est l'outil de l'organisation : il voit les demandes et les déclarations **côté admin**, jamais l'espace de quelqu'un |
| Rotation du jeton d'espace d'un animateur | Invalide le lien déjà imprimé sur un PDF distribué : une conséquence hors de l'application, que personne ne peut annuler depuis une conversation |
| `/api/auth`, `/api/branding`, `/api/mentions-legales`, `/api/debug` | La session admin, l'habillage de l'interface et les bacs à sable de développement : rien qu'un assistant puisse en faire |
