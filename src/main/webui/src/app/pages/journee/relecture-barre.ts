import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { JourneesApi } from '../../core/api/journees-api';
import { intlLocale } from '../../core/locale';
import { PosteAffectation, RapportPauses } from '../../core/models';
import { ValidationsStore } from '../../core/validations.store';
import { VerrouillageStore } from '../../core/verrouillage.store';
import { syntheseJournee } from './journee';
import { RelectureDialog, RelectureDialogData } from './relecture-dialog';

/** Which of the four chips narrows the rendering; `aucune` when none does. */
export type PastilleRelecture = 'aucune' | 'vides' | 'pauses' | 'verrous' | 'changements';

/**
 * The relecture of the day on screen, in one line (issue #712): four chips
 * that count what to look at before accepting the day — seats nobody holds,
 * breaks nobody can relieve, seats a lock freezes, changes since the last
 * publication — each of them **filtering** the rendering on what it counts,
 * and a menu holding the reading itself: its state, « Relu et accepté… » (the
 * comment and the lock in a dialog), and the withdrawal.
 *
 * <p>It replaced a card of four prerequisites a thousand pixels above the
 * list they described. The counts are read from the plan the page already
 * holds, and from the breaks report it already read; only the changes are
 * asked of the server, one call per day.</p>
 */
@Component({
  selector: 'app-relecture-barre',
  imports: [MatButtonModule, MatIconModule, MatMenuModule],
  template: `
    <div class="relecture-barre" role="group" [attr.aria-label]="groupeLabel">
      <span class="relecture-barre-titre" i18n="@@journee.relecture.titre">Relecture :</span>
      <button type="button" class="relecture-pastille" [class.relecture-pastille-alerte]="vides() > 0"
              [class.relecture-pastille-active]="active() === 'vides'" [attr.aria-pressed]="active() === 'vides'"
              (click)="basculer('vides')" i18n="@@journee.relecture.vides">{{ vides() }} siège(s) vide(s)</button>
      <button type="button" class="relecture-pastille" [class.relecture-pastille-attention]="(unrelievedBreaks() ?? 0) > 0"
              [class.relecture-pastille-active]="active() === 'pauses'" [attr.aria-pressed]="active() === 'pauses'"
              [disabled]="unrelievedBreaks() === null" (click)="basculer('pauses')">{{ pausesLabel() }}</button>
      <button type="button" class="relecture-pastille"
              [class.relecture-pastille-active]="active() === 'verrous'" [attr.aria-pressed]="active() === 'verrous'"
              (click)="basculer('verrous')" i18n="@@journee.relecture.verrous">{{ verrouilles() }} siège(s) verrouillé(s)</button>
      <button type="button" class="relecture-pastille"
              [class.relecture-pastille-active]="active() === 'changements'" [attr.aria-pressed]="active() === 'changements'"
              [disabled]="changements() === null" (click)="basculer('changements')">{{ changementsLabel() }}</button>
      <button matButton type="button" class="relecture-menu" [matMenuTriggerFor]="menu">
        <mat-icon>{{ relue() ? 'task_alt' : 'fact_check' }}</mat-icon>
        {{ etatLabel() }}
        <mat-icon iconPositionEnd>arrow_drop_down</mat-icon>
      </button>
      <mat-menu #menu="matMenu">
        <button mat-menu-item type="button" (click)="open()">
          <mat-icon>fact_check</mat-icon>
          @if (relue()) {
            <span i18n="@@journee.relecture.menu.voir">Relecture et commentaire…</span>
          } @else {
            <span i18n="@@journee.relecture.menu.accepter">Relu et accepté…</span>
          }
        </button>
      </mat-menu>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RelectureBarre {
  private readonly journeesApi = inject(JourneesApi);
  private readonly validations = inject(ValidationsStore);
  private readonly verrous = inject(VerrouillageStore);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

  /** The date on screen, `AAAA-MM-JJ`. */
  readonly jour = input.required<string>();
  /** The day's number in the plan, which the seats and the breaks are keyed by. */
  readonly numero = input.required<number>();
  readonly postes = input<readonly PosteAffectation[]>([]);
  /** Null when the breaks could not be read: the chip then says nothing. */
  readonly pauses = input<RapportPauses | null>(null);
  /** Whether a filter of the page narrows the rendering: the reading still covers the whole day. */
  readonly filtre = input(false);
  /** The chip narrowing the rendering now. */
  readonly active = input<PastilleRelecture>('aucune');
  /** A chip was pressed: the page narrows the rendering — or widens it back, on the active one. */
  readonly pastille = output<PastilleRelecture>();
  /** A sentence for the page: the reading was recorded, or withdrawn. */
  readonly reported = output<string>();

  protected readonly groupeLabel = $localize`:@@journee.relecture.groupe:Relecture de la journée`;

  private readonly synthese = computed(() =>
    syntheseJournee(this.postes(), this.numero(), this.pauses()),
  );
  protected readonly vides = computed(() => this.synthese().vides ?? 0);
  protected readonly unrelievedBreaks = computed(() => this.synthese().unrelievedBreaks);
  protected readonly verrouilles = computed(() => {
    const jourVerrouille = this.verrous.estJourVerrouille(this.jour());
    return this.postes().filter(
      (poste) =>
        poste.creneau?.jour === this.numero() &&
        poste.stand &&
        (jourVerrouille ||
          this.verrous.estStandVerrouille(poste.stand.id) ||
          this.verrous.estCreneauVerrouille(poste.creneau.id)),
    ).length;
  });
  /** Seats changed since the reference; null while unknown or when there is nothing to compare to. */
  protected readonly changements = signal<number | null>(null);

  protected readonly pausesLabel = computed(() => {
    const count = this.unrelievedBreaks();
    return count === null
      ? $localize`:@@journee.relecture.pauses.inconnu:Pauses : non lues`
      : $localize`:@@journee.relecture.pauses:${count}:count: pause(s) sans relais`;
  });
  protected readonly changementsLabel = computed(() => {
    const count = this.changements();
    return count === null
      ? $localize`:@@journee.relecture.changements.inconnu:Changements : rien à comparer`
      : $localize`:@@journee.relecture.changements:${count}:count: changement(s)`;
  });

  protected readonly relue = computed(() => this.validations.acceptedDays().has(this.jour()));
  protected readonly etatLabel = computed(() => {
    if (!this.relue()) {
      return $localize`:@@journee.relecture.aRelire:À relire`;
    }
    const validation = this.validations.validations().find((item) => item.jour === this.jour());
    if (!validation?.valideLe) {
      return $localize`:@@journee.relecture.relue:Relue et acceptée`;
    }
    const quand = new Date(validation.valideLe).toLocaleDateString(intlLocale());
    return $localize`:@@journee.relecture.relueLe:Relue le ${quand}:date:`;
  });

  constructor() {
    effect(() => {
      const jour = this.jour();
      // Re-read with the plan: a solve or a gesture moves the count.
      this.postes();
      untracked(() => void this.readChanges(jour));
    });
  }

  protected basculer(pastille: PastilleRelecture): void {
    this.pastille.emit(this.active() === pastille ? 'aucune' : pastille);
  }

  protected open(): void {
    const data: RelectureDialogData = { jour: this.jour(), filtre: this.filtre() };
    this.dialog
      .open<RelectureDialog, RelectureDialogData, string>(RelectureDialog, {
        data,
        width: '36rem',
        autoFocus: 'first-tabbable',
      })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((message) => {
        if (message) {
          this.reported.emit(message);
        }
      });
  }

  private async readChanges(jour: string): Promise<void> {
    try {
      const lu = await this.journeesApi.changements(jour);
      if (jour === this.jour()) {
        this.changements.set(lu.referenceDisponible ? lu.parVacation.length : null);
      }
    } catch {
      // The chip goes quiet rather than failing the page: the Changements
      // rendering, one click away, says what went wrong.
      this.changements.set(null);
    }
  }
}
