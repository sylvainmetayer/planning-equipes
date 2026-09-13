// Shared wording for the feasibility warnings, so the banner
// (shared/feasibility-banner.ts), the Problèmes page and the notification they
// also raise (core/notification.service.ts) never drift apart.

/**
 * Rich, actionable explanation shown when the solver could not reach hard
 * score zero. Kept as a standalone function (not inlined where it's used) so
 * the exact same French text backs both the persisted on-page banner and the
 * notification logged to the Notifications page.
 */
export function hardScoreNegativeMessage(hardScore: number): string {
  return $localize`:@@feasibility.hardScoreNegative:Ce planning n'est pas totalement réalisable (score dur ${hardScore}:hardScore:) : augmentez l'effectif, raccourcissez les vacations ou revoyez les disponibilités, puis relancez une résolution.`;
}

/**
 * Note appended under a truncated list of causes. The backend caps
 * `FeasibilityReport.causes` while `totalCauses` counts them all, so both the
 * banner (which shows fewer still) and the Problèmes page can say how many
 * problems are not listed. Returns an empty string when nothing is hidden.
 */
export function causesRestantesMessage(restantes: number): string {
  if (restantes <= 0) {
    return '';
  }
  return $localize`:@@feasibility.causesRestantes:+ ${restantes}:count: autre(s) problème(s) non détaillé(s) ici.`;
}
