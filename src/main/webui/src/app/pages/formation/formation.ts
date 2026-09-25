// The pure side of the « À former » tab: how a level, a wish and a shortage
// read on screen. The ranking itself is the server's (`FormationAnalyzer`),
// shown as it comes.

import { NiveauCompetence, TypologieAFormer } from '../../core/models';

export function libelleNiveau(niveau: NiveauCompetence): string {
  switch (niveau) {
    case 'DEBUTANT':
      return $localize`:@@formation.niveau.debutant:Débutant`;
    case 'AUTONOME':
      return $localize`:@@formation.niveau.autonome:Autonome`;
    case 'REFERENT':
      return $localize`:@@formation.niveau.referent:Référent`;
  }
}

/** `2026-07-10` → `10/07`, the way the fragility tab writes a day. */
export function libelleJour(date: string): string {
  const [, mois, jour] = date.split('-');
  return `${jour}/${mois}`;
}

/**
 * The shortage in one line, from the two reports it is read from: the Besoin
 * tab's shortfall, then the Fragilité tab's scarce groups and irreplaceable
 * seats. A figure at zero is left out, not written « 0 ».
 */
export function resumeDeficit(ligne: TypologieAFormer): string {
  const morceaux: string[] = [];
  if (ligne.manque > 0) {
    morceaux.push(
      $localize`:@@formation.deficit.manque:manque ${ligne.manque}:count: animateur(s) au besoin`,
    );
  }
  if (ligne.competencesRares > 0) {
    morceaux.push(
      ligne.groupesSansSpecialiste > 0
        ? $localize`:@@formation.deficit.raresSans:${ligne.competencesRares}:count: couple(s) stand × créneau à un spécialiste ou aucun, dont ${ligne.groupesSansSpecialiste}:sans: sans spécialiste`
        : $localize`:@@formation.deficit.rares:${ligne.competencesRares}:count: couple(s) stand × créneau à un seul spécialiste`,
    );
  }
  if (ligne.postesIrremplacables > 0) {
    morceaux.push(
      $localize`:@@formation.deficit.irremplacables:${ligne.postesIrremplacables}:count: poste(s) irremplaçable(s)`,
    );
  }
  return morceaux.join(' · ');
}
