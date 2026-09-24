# Accessibilité

Ce que l'application tient en matière d'accessibilité, ce qui le garantit, et ce
qui reste ouvert. Les **conventions** d'écriture (un `<h1>` par écran, les icônes
porteuses d'information, les messages de statut…) sont dans
[`developpement.md` § Accessibilité](developpement.md#accessibilité) : ce fichier
dit *où on en est*, celui-là dit *comment écrire*.

## Le référentiel et le périmètre

Le référentiel est le **RGAA 4.1**, qui transpose les critères A et AA des
WCAG 2.1. Trois périmètres, qui n'ont pas le même public :

| Périmètre | Public | Lu sur |
| --- | --- | --- |
| Espace animateur (4 écrans) | ~150 personnes extérieures à l'organisation, dont des **mineurs**, sans équipement connu | un téléphone, depuis un lien reçu par e-mail |
| Pages publiques (`/login`, mentions légales, confidentialité, CGU, déclaration d'accessibilité) | quiconque arrive sans être passé par le reste | tout support |
| Administration (une cinquantaine d'écrans) | l'équipe d'organisation | un poste de bureau |

L'espace animateur est le seul périmètre réellement grand public : il est traité
en priorité et joué par les tests sur un viewport de téléphone.

## Ce qui a été audité

Un audit statique critère par critère a été mené sur l'ensemble des gabarits,
complété de quatre mesures outillées : les règles d'accessibilité
d'`angular-eslint`, un calcul de ratio de contraste sur les couleurs écrites
par ce dépôt, un balayage structurel des gabarits (titres, `caption`, `scope`,
`role`, `tabindex`, gestionnaires d'événements), et une sonde sur le
comportement réel de `<mat-icon>`. Ses écarts ont tous été corrigés :

| Thématique RGAA | Écart relevé | Correction |
| --- | --- | --- |
| 1 — Images | les `aria-label` d'icônes jamais restitués (`MatIcon` les masque) | `aria-hidden="false"` en dur, contrôlé par `icon-labels-check` |
| 3 — Couleurs | barre de marque à 3,24:1 ; accent de déploiement sans plafond en clair | `#007eb2` (4,54:1) ; clarté OKLCH bornée dans les deux schémas, mesurée par `branding.spec.ts` |
| 5 — Tableaux | `role="grid"` sans clavier, tables sans `caption`, `scope` absent des `mat-table` | grille réelle ou tableau natif ; `caption` ; `scope` contrôlé par `table-headers-check` |
| 7 — Scripts | glisser-déposer sans équivalent clavier ; cellule du pivot inatteignable ; raccourcis mono-touche | dialogue « Déplacer … vers » ; tabindex itinérant ; interrupteur des raccourcis |
| 7.5 — Messages de statut | une trentaine d'erreurs et de verdicts inertes | `app-status-message`, contrôlé par `status-messages-check` |
| 9 — Structuration | écrans sans `<h1>`, `mat-card-title` non-titres | contrôlé par `headings-check` |
| 12 — Navigation | espace animateur sans lien d'évitement ni reprise de focus | lien d'évitement et `<main>` refocalisé dans les deux coques |
| 13 — Consultation | nouvelles fenêtres non annoncées ; PDF non balisés ; pas de déclaration | directive `NewWindowLink` ; titre et langue des PDF ; page `/declaration-accessibilite` |

Conforme sans correction, et consigné pour qu'un prochain audit ne le
ré-instruise pas : les **formulaires** (thématique 11 — un `mat-label` par
champ, les erreurs en `mat-error`, `autocomplete` là où la donnée est celle de
l'utilisateur lui-même), l'absence de **cadres** et de **multimédia**, les
**éléments obligatoires** (`<html lang>` réécrit avec le catalogue, un
`<title>` par route), la **présentation** (aucun `user-scalable=no`, un seul
`outline: none`, compensé par un `:focus-visible`) et les **limites de temps**
(aucune imposée).

## Ce qui empêche les écarts de revenir

Chaque famille d'écart corrigée a son contrôle en CI, sur le modèle des tests
structurels du dépôt : il dit quelle ligne écrire, et ses exceptions sont
argumentées une par une.

| Contrôle | Où | Ce qu'il refuse |
| --- | --- | --- |
| 12 règles a11y d'`angular-eslint` | `npm run lint` | les huit familles de régressions que le linter de gabarit voit (clic sans clavier, `tabindex` positif, `autofocus`…) |
| `icon-labels-check` | job `frontend` | une icône porteuse d'information masquée, ou sans nom |
| `headings-check` | job `frontend` | un écran routé sans exactement un `<h1>`, un `<mat-card-title>` élément |
| `status-messages-check` | job `frontend` | une erreur ou un verdict dans un élément sans `role` |
| `table-headers-check` | job `frontend` | un `<th>` sans `scope` |
| `new-window-check` | job `frontend` | un lien `target="_blank"` qui ne l'annonce pas |
| `e2e/accessibilite.spec.ts` | workflow `e2e.yml` | toute violation *serious* ou *critical* d'axe-core hors ligne de base, sur les écrans représentatifs — les quatre de l'espace animateur rejoués sur téléphone |

La spec axe mesure ce qu'aucun linter ne voit : le contraste une fois les thèmes
appliqués, les régions, les noms accessibles calculés à l'exécution. Elle part
d'une **ligne de base gelée**, écran par écran (`LIGNE_DE_BASE`), qui ne peut
que décroître : une règle qui n'est plus violée fait échouer la spec tant
qu'elle n'est pas retirée de la liste.

## Ce qui reste ouvert

- **Aucun parcours n'a été joué sous lecteur d'écran** (NVDA, JAWS, VoiceOver).
  Les défauts d'ordre de lecture et de verbosité ne sont couverts ni par l'audit
  statique ni par axe. Une déclaration de conformité demande un audit humain sur
  la grille complète.
- **Les PDF** portent un titre et une langue, mais **ne sont pas balisés** :
  OpenPDF ne construit aucune structure sur ce que `Document.add` écrit, et un
  PDF déclaré balisé sur un arbre vide se lit comme une page blanche — pire
  qu'un PDF non balisé, lu comme un flux de texte. L'espace animateur est la
  version accessible du même planning (critère 13.3, « si nécessaire »).
- **La redirection vers `/login` sur une session expirée est silencieuse** et
  perd un formulaire en cours de saisie. Aucune limite de temps n'est imposée,
  mais l'expiration de session en tient lieu de fait.
- **Les jetons `--mat-sys-*`** de `mat.theme()` ne sont pas recalculés : ils
  sont réputés conformes par construction, et seul axe les mesure une fois
  appliqués.

## La déclaration d'accessibilité

L'article 47 de la loi n° 2005-102 impose une déclaration aux organismes publics
et aux entreprises au-dessus d'un seuil de chiffre d'affaires. **L'obligation
pèse sur l'organisation qui déploie l'application**, pas sur ce dépôt : elle
seule sait jusqu'où son instance a été auditée, quand, et où recevoir un
signalement. La page `/declaration-accessibilite`, liée depuis le pied de page,
le tiroir et le menu légal de l'espace animateur, affiche ce que le
déploiement configure, et dit « non renseigné » pour le reste :

| Variable | Contenu |
| --- | --- |
| `LEGAL_ACCESSIBILITE_ETAT` | `totale`, `partielle` ou `non` — toute autre valeur se lit comme non renseignée |
| `LEGAL_ACCESSIBILITE_DATE_AUDIT` | la date de l'audit sur lequel repose l'état |
| `LEGAL_ACCESSIBILITE_CONTENUS` | les contenus non accessibles, et pourquoi ; un saut de ligne fait un paragraphe |
| `LEGAL_ACCESSIBILITE_SIGNALEMENT` | où signaler un défaut ; vide, l'adresse de contact des mentions légales (`LEGAL_CONTACT`) |
