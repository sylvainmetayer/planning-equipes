// The wording of the scenario-import confirmation, kept apart from both the
// service that shows it and the page that used to own it, so it stays a pure
// function testable without any of them.

import { ImpactImport } from './models';

/**
 * The confirmation message of a scenario import, built from the server-side
 * impact counts: what gets replaced, what disappears with it, what stays.
 * With `impact` null (the counting call failed), a generic warning remains —
 * counting is comfort, never the safety net itself.
 */
export function messageImpactImport(
  impact: ImpactImport | null,
  intitule: string,
  avecInstantane = true,
): string {
  const lignes: string[] = [
    $localize`:@@dataSetup.impact.base:Cette action va ${intitule}:action: : les stands et animateurs absents du fichier sont supprimés, avec leurs demandes d'échange.`,
  ];
  if (impact) {
    lignes.push(
      $localize`:@@dataSetup.impact.referentiel:Actuellement : ${impact.animateurs}:animateurs: animateur(s) et ${impact.stands}:stands: stand(s).`,
    );
    if (impact.planningResolu) {
      lignes.push(
        avecInstantane
          ? $localize`:@@dataSetup.impact.planning:Le planning résolu (${impact.postes}:postes: affectation(s)) sera effacé, ainsi que ${impact.verrous}:verrous: verrouillage(s) ; un instantané sera enregistré automatiquement avant l'import.`
          : $localize`:@@dataSetup.impact.planningSansInstantane:Le planning résolu de cette édition (${impact.postes}:postes: affectation(s)) sera effacé, ainsi que ${impact.verrous}:verrous: verrouillage(s).`,
      );
    }
    if (impact.demandesEchange > 0) {
      lignes.push(
        $localize`:@@dataSetup.impact.demandes:${impact.demandesEchange}:demandes: demande(s) d'échange (dont ${impact.demandesEnAttente}:enAttente: en attente) seront perdues si leurs créneaux sont remplacés ou leurs animateurs supprimés.`,
      );
    }
  }
  return lignes.join(' ');
}
