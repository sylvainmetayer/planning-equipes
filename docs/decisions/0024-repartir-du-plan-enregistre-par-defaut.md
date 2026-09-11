# 0024 — Repartir du plan enregistré par défaut, sans l'épingler

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : solveur, API, MCP, IHM

## Contexte

Le bouton de résolution repartait toujours de zéro, et rien ne le disait.
Mesuré sur l'édition réelle 2026 (3 499 postes, 153 animateurs, 600 s par
solve, graine fixe, #174) :

| | hard | medium | soft |
| --- | --- | --- | --- |
| plan en base | 0 | -6 232 | -920 |
| relance à froid | 0 | **-7 434** | -564 |
| relance depuis le plan | 0 | **-6 047** | -852 |

Une relance à froid détruisait 1 202 points de medium acquis ; repartir du
plan les conservait et en gagnait 185. Le sujet n'était donc pas la vitesse
mais la **non-régression**. Aucun mécanisme existant ne rendait ce service :
le solve incrémental (#86) repart du plan mais **épingle** tout ce qui reste
valable — il stabilise, il ne ré-optimise pas — et les verrous (#87)
n'épinglent que ce qu'on verrouille.

Restait à décider où loger le choix, et ce qui serait le défaut.

## Options envisagées

**(A) Une case à cocher « repartir du plan actuel »**, cochée par défaut
quand un plan existe — la forme proposée par l'issue. Écarté : une option que
personne ne décoche est du bruit d'écran, et une page qui portait déjà deux
boutons parlant de mécanisme (« Résoudre avec Timefold », « Replanifier
(incrémental) ») n'avait pas besoin d'un troisième concept.

**(B) Un paramètre HTTP `false` par défaut**, pour ne pas changer le
contrat de l'endpoint. Écarté : un script ou un assistant MCP qui relance
« pour voir » détruirait alors en silence exactement ce que l'écran protège.

**(C) Le comportement sûr par défaut, partout, et le cas rare nommé.** Le
réamorçage devient ce que fait une résolution complète dès qu'un plan existe
(`AUTO`), pour l'écran, l'API et MCP ; le départ à froid devient une action à
part entière, « Recommencer de zéro », qui dit ce qu'elle abandonne et se
confirme.

## Décision

(C). La page Solveur parle par situation plutôt que par mécanisme :

| Situation | Action | Ce qui bouge |
| --- | --- | --- |
| Le meilleur planning possible | **Calculer le planning** | Tout, en repartant du plan enregistré ; seuls les verrouillages sont figés |
| Un changement tardif, bouger le moins possible | **Corriger après un changement** | Les postes invalidés ou désignés, le reste est garanti inchangé |
| Le plan ne vaut rien, tout reprendre | **Recommencer de zéro** | Tout, sans point de départ — confirmation |

Six règles :

1. **Réamorcer n'est pas épingler.** Chaque place reçoit son titulaire et
   reste mobile ; seuls les verrous explicites sont figés. La ligne
   `setVerrouille(true)` de la replanification est précisément celle que le
   réamorçage n'a pas, et un test le vérifie en premier.
2. **`AUTO` est le défaut de tous les points d'entrée**, y compris
   `POST /api/solve/async/reference-data` dont le comportement change : ne pas
   détruire l'acquis n'est pas une préférence d'écran. `PLAN_COURANT` exige un
   plan, `AUCUN` est le froid par son nom.
3. **Le choix est persisté avec le job** (`solver_job.reamorcage`), pour
   qu'une file rejouée après redémarrage démarre comme demandé.
4. **Un titulaire disparu ou devenu indisponible laisse sa place vide**,
   comme dans la replanification : semer une violation à défaire d'abord est
   un moins bon départ qu'un trou.
5. **Le résultat dit d'où il est parti** (`reamorcage: { mode, postes,
   postesLiberes }`, `mode` jamais `AUTO`), et la page le dit **avant** le
   clic — « repart du plan enregistré le … (N affectations) » — parce qu'un
   défaut invisible est un défaut qu'on ne comprend pas.
6. **Ce qui n'est pas promis.** Le gain propre du réamorçage est modeste
   (~3 %) ; un plan de départ infaisable n'achète aucun raccourci ; et le
   résultat peut descendre sous le plan de départ quand le référentiel a
   changé entre-temps — `previousPlan` (#274) continue de le montrer.

## Conséquences

- La brique est une opération de service (`buildFromReferenceData(Reamorcage)`),
  pas une option d'écran : la reprise d'un job `INTERROMPU` (#183) devient un
  appel de plus, avec un point de contrôle périodique à ajouter.
- Les libellés parlent d'intention ; « Timefold » et « incrémental » ne sont
  plus que des mots d'infobulle et d'aide. La section d'aide « Calculer,
  corriger, recommencer » porte le tableau ci-dessus.
- « Repartir d'un instantané » reste un geste composé : restaurer
  l'instantané, puis calculer.
