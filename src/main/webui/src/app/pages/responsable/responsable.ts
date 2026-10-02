// The rules of the responsable de stand's screen (issue #295), kept out of
// the components so they are tested without rendering: which days the
// published plan covers, what one day shows of a stand and of a person, and
// how a shift is summed up.
//
// Every wording is built on call, never at module scope: `$localize` only
// resolves once `main.ts` has loaded the translation catalogue.

import {
  MembreEquipe,
  PlageMembre,
  StandResponsable,
  VacationResponsable,
  ResponsableView,
} from '../../core/models';

/** The two views of the page, as `?onglet=` names them. */
export type OngletResponsable = 'planning' | 'equipe';

/** `yyyy-MM-dd` of a local date-time as the server writes it. */
export function dayOf(dateTime: string): string {
  return dateTime.slice(0, 10);
}

/** `HH:mm` of a local date-time as the server writes it. */
export function timeOf(dateTime: string): string {
  return dateTime.slice(11, 16);
}

/** Every day a shift of the scope starts on, in order. */
export function daysOf(view: ResponsableView | null): string[] {
  if (!view) {
    return [];
  }
  const days = new Set<string>();
  for (const stand of view.stands) {
    for (const vacation of stand.vacations) {
      days.add(dayOf(vacation.debut));
    }
  }
  return [...days].sort((a, b) => a.localeCompare(b));
}

/**
 * The day to open on: the one asked for when the plan has it, else today when
 * it has it, else the next day with a shift, else the first — an edition over
 * is read from its start.
 */
export function initialDay(
  days: readonly string[],
  asked: string | null,
  today: string,
): string | null {
  if (asked && days.includes(asked)) {
    return asked;
  }
  return days.find((day) => day >= today) ?? days[0] ?? null;
}

/** The shifts of one stand starting on `day`, in start order. */
export function shiftsOn(stand: StandResponsable, day: string): VacationResponsable[] {
  return stand.vacations.filter((vacation) => dayOf(vacation.debut) === day);
}

/** One person's windows of `day`: their seats on the scope, and « occupé » elsewhere. */
export function windowsOn(membre: MembreEquipe, day: string): PlageMembre[] {
  return membre.plages.filter((plage) => dayOf(plage.debut) === day);
}

/** The people of the team working on `day`. */
export function teamOn(view: ResponsableView | null, day: string): MembreEquipe[] {
  return (view?.equipe ?? []).filter((membre) => windowsOn(membre, day).length > 0);
}

/** « 10:00 – 12:00 », the end dated when it falls on the next day. */
export function windowLabel(debut: string, fin: string): string {
  const suffix =
    dayOf(fin) !== dayOf(debut) ? $localize`:@@responsable.lendemain: (lendemain)` : '';
  return `${timeOf(debut)} – ${timeOf(fin)}${suffix}`;
}

/** « 1 animateur sur 2 places », what a head count says of a shift. */
export function staffingLabel(vacation: VacationResponsable): string {
  const places = vacation.pourvus + vacation.vides;
  return vacation.pourvus === 1
    ? $localize`:@@responsable.effectif.un:1 animateur sur ${places}:places: place(s)`
    : $localize`:@@responsable.effectif.plusieurs:${vacation.pourvus}:pourvus: animateurs sur ${places}:places: place(s)`;
}

/** Whether at least one stand of the view names who holds its shifts. */
export function anyNamed(view: ResponsableView | null): boolean {
  return (view?.stands ?? []).some((stand) => stand.nominatif);
}
