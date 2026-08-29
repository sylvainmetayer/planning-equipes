# 0018 — Écrire l'URL de vue sans naviguer

- **Statut** : accepté, implémenté · précise 0012
- **Date** : août 2026
- **Portée** : frontend

## Contexte

[0012](0012-etat-de-vue-dans-l-url.md) place l'état de vue d'un écran — un tri,
un filtre rapide, une vue choisie — dans l'URL, et demande que l'écriture
**remplace** l'entrée d'historique plutôt que d'en empiler une par geste. Il ne
disait pas *comment* écrire, et la première implémentation a pris le chemin
évident :

```ts
effect(() => {
  void router.navigate([], { relativeTo: route, queryParams: queryParams(), replaceUrl: true });
});
```

Ça remplit le contrat écrit, et c'est faux à l'usage. L'`effect` dépend du
signal qu'il reflète, donc **chaque frappe dans un champ de filtre déclenchait
une navigation du routeur** : cycle complet, re-rendu, et le champ perdait le
focus. L'utilisateur devait recliquer entre deux lettres. Le défaut a vécu sur
plusieurs écrans (Animateurs, Heures, Heatmap, Calendrier) sans qu'aucun test
ne le voie : les tests unitaires vérifiaient que les bons paramètres partaient,
les tests de bout en bout remplissaient les champs avec `fill()`, qui pose la
valeur d'un bloc et ne reproduit donc pas la saisie humaine.

## Options envisagées

**(A) Temporiser l'écriture (debounce).** Une navigation après 300 ms
d'inactivité. Réduit la casse sans la supprimer — le focus saute encore quand
l'utilisateur marque une pause au milieu d'un mot — et ajoute un délai avant
que l'URL ne devienne partageable.

**(B) Sortir le champ texte de la synchronisation.** Ne plus refléter que le
tri et la vue. Simple, mais ampute exactement le cas d'usage le plus utile :
« regarde cet écran filtré sur ce nom » est le lien qu'on envoie à un collègue.

**(C) Écrire la barre d'adresse sans navigation**, par
`Location.replaceState`.

## Décision

**(C).** L'URL est ici le **reflet** de l'écran, pas une demande d'aller
ailleurs : rien n'a à être résolu, activé ni réaffiché quand elle change. Faire
tourner un cycle de routeur pour ça, c'était payer le prix d'une navigation
pour un effet d'affichage — et le focus était ce prix.

Lire n'est pas touché, et c'est ce qui rend la décision tenable : tous les
écrans concernés lisent `route.snapshot.queryParamMap` **une seule fois**, à leur
construction. Le rechargement (F5), le lien partagé et un Retour qui revient sur
l'écran passent, eux, par une vraie navigation, laquelle relit la barre
d'adresse — les deux usages qui justifient 0012 continuent donc de fonctionner.

`keepViewInQueryParams` n'injecte plus du tout le `Router` : il compose la chaîne
de requête lui-même, en `encodeURIComponent` et non en `URLSearchParams`, qui
écrirait l'espace en `+` là où Angular relit la valeur avec
`decodeURIComponent` — un filtre sur deux mots ne survivrait pas au
rechargement.

## Conséquences

- Un écran qui aurait besoin d'**observer** ses paramètres d'URL (et non de les
  lire à la construction) devra en refaire une navigation, et en repayer le
  re-rendu. Aucun n'en est là.
- L'état du routeur est momentanément en retard sur la barre d'adresse. Sans
  effet observé : les navigations de l'application sont absolues, et la première
  navigation réelle resynchronise tout.
- Le défaut était un défaut d'**interaction** : aucun test unitaire ne pouvait
  le voir. Il est désormais tenu des deux côtés — un test unitaire sur le
  helper, qui vérifie qu'aucune navigation n'est déclenchée, et un test de bout
  en bout qui tape caractère par caractère et vérifie que le champ garde le
  focus. Le second a été vu rouge avant correction, dans un vrai navigateur.
- Règle générale qui en découle : **remplir un champ avec `fill()` ne teste pas
  la saisie**. Un test qui doit prouver qu'on peut taper doit taper.
