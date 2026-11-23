import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  input,
  output,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { RouterLink } from '@angular/router';
import { intlLocale } from '../../core/locale';
import { PosteAffectation, RapportPauses } from '../../core/models';
import { ValidationsStore } from '../../core/validations.store';
import { syntheseJournee } from './journee';
import { RelectureDialog, RelectureDialogData } from './relecture-dialog';

/**
 * The relecture of the day on screen, in one line (issue #712): what is left
 * to look at before accepting the day — seats nobody holds and breaks nobody
 * can relieve — as one count leading to the Diagnostic's problems, and a menu
 * holding the reading itself: its state, « Relu et accepté… » (the comment
 * and the lock in a dialog), and the withdrawal.
 *
 * <p>It replaced four chips that each narrowed the rendering: one count is
 * read at a glance, and the problems are detailed in one place. The count is
 * read from the plan the page already holds and the breaks report it already
 * read: nothing is asked of the server.</p>
 */
@Component({
  selector: 'app-relecture-barre',
  imports: [MatButtonModule, MatIconModule, MatMenuModule, RouterLink],
  template: `
    <div class="relecture-barre" role="group" [attr.aria-label]="groupeLabel">
      <a
        matButton
        routerLink="/diagnostic"
        [queryParams]="{ onglet: 'problemes' }"
        [class.relecture-compteur-alerte]="aRelire() > 0"
        >{{ aRelireLabel() }}</a
      >
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
  private readonly validations = inject(ValidationsStore);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

  /** The date on screen, `AAAA-MM-JJ`. */
  readonly jour = input.required<string>();
  /** The day's number in the plan, which the seats and the breaks are keyed by. */
  readonly numero = input.required<number>();
  readonly postes = input<readonly PosteAffectation[]>([]);
  /** Null when the breaks could not be read: they then count for nothing. */
  readonly pauses = input<RapportPauses | null>(null);
  /** Whether a filter of the page narrows the rendering: the reading still covers the whole day. */
  readonly filtre = input(false);
  /** A sentence for the page: the reading was recorded, or withdrawn. */
  readonly reported = output<string>();

  protected readonly groupeLabel = $localize`:@@journee.relecture.groupe:Relecture de la journée`;

  private readonly synthese = computed(() =>
    syntheseJournee(this.postes(), this.numero(), this.pauses()),
  );
  /** Empty seats and breaks without relay: what the day still asks to look at. */
  protected readonly aRelire = computed(() => {
    const synthese = this.synthese();
    return (synthese.vides ?? 0) + (synthese.unrelievedBreaks ?? 0);
  });
  protected readonly aRelireLabel = computed(() => {
    const count = this.aRelire();
    return count === 0
      ? $localize`:@@journee.relecture.rien:Rien à relire`
      : $localize`:@@journee.relecture.aRelireN:${count}:count: point(s) à relire`;
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
}
