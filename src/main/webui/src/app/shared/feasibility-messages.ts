// Shared wording for the two post-solve feasibility warnings, so the banner
// (shared/feasibility-banner.ts) and the notification it also raises
// (core/notification.service.ts) never drift apart.

/**
 * Rich, actionable explanation shown when the solver could not reach hard
 * score zero. Kept as a standalone function (not inlined where it's used) so
 * the exact same French text backs both the persisted on-page banner and the
 * notification logged to the Notifications page.
 */
export function hardScoreNegativeMessage(hardScore: number): string {
  return $localize`:@@feasibility.hardScoreNegative:Ce planning n'est pas totalement réalisable : le solveur n'a pas réussi à ramener le score dur à zéro (score dur ${hardScore}:hardScore:). Essayez d'augmenter l'effectif disponible ou compétent, d'assouplir le découpage des vacations (durée min/max, stratégie de couverture pendant la pause) ou de revoir les disponibilités déclarées, puis relancez une résolution. Le détail des règles encore en défaut (ci-dessous si listées, ou page Contraintes) précise la valeur légale ou métier à respecter.`;
}
