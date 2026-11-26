import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ValidationsApi } from '../../core/api/validations-api';
import { errorPrefix } from '../../core/error-message';
import { intlLocale } from '../../core/locale';
import { DayState, PrerequisJournee, PrerequisValidation } from '../../core/models';
import { ValidationsStore } from '../../core/validations.store';
import { libellePrerequis } from './validation-prerequis';
import { ConfirmService } from '../../shared/confirm-dialog';
import { StatusMessage } from '../../shared/status-message';

/**
 * The state of the day on screen — à relire, relue, figée (ADR 0121): what to
 * check before reading it, the three-valued control, the comment of the
 * reading. Opened from the relecture bar's menu, in `RelectureDialog`.
 *
 * <p>The four prerequisites are the Problèmes, Pauses and Fragilité screens
 * narrowed to this date — read from the server so this panel can never disagree
 * with them. <b>None of them blocks</b>: an organiser who knows why a seat
 * stays empty reads the day and says so in the comment.</p>
 *
 * <p>« Figée » is the day lock itself and asks first: no solve moves anything
 * there any more. It is the only state that lays a lock down.</p>
 */
@Component({
  selector: 'app-validation-panel',
  imports: [
    StatusMessage,
    MatButtonModule,
    MatCardModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
  ],
  templateUrl: './validation-panel.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ValidationPanel {
  private readonly api = inject(ValidationsApi);
  private readonly store = inject(ValidationsStore);
  private readonly confirm = inject(ConfirmService);

  /** The day on screen, `AAAA-MM-JJ`; null while the plan is still loading. */
  readonly jour = input<string | null>(null);
  /**
   * Whether the page narrows what it shows (a stand, an animateur). The reading
   * never follows the filter — it is always the whole day — so the panel says
   * so rather than let a filtered screen pass for what is being accepted.
   */
  readonly filtre = input(false);
  /** False inside the relecture dialog, whose own title already names the day. */
  readonly entete = input(true);

  /** A sentence for the page's feedback: the reading was recorded, or withdrawn. */
  readonly reported = output<string>();

  protected readonly prerequis = signal<PrerequisJournee | null>(null);
  protected readonly chargement = signal(false);
  protected readonly busy = signal(false);
  protected readonly erreur = signal('');
  protected readonly commentaire = signal('');
  /** The state the control points at; saved only by the button. */
  protected readonly choix = signal<DayState>('A_RELIRE');

  constructor() {
    // The day changed: the whole panel describes another day, so its read-out
    // and its unsent comment both start again.
    effect(() => {
      const jour = this.jour();
      void this.reload(jour);
    });
  }

  /** Nothing to save while the control and the comment say what the server holds. */
  protected readonly modifie = computed(() => {
    const lu = this.prerequis();
    return (
      !!lu &&
      (this.choix() !== lu.etat ||
        (this.choix() !== 'A_RELIRE' && this.commentaire() !== (lu.commentaire ?? '')))
    );
  });

  protected readonly acceptedLabel = computed(() => {
    const lu = this.prerequis();
    if (!lu?.valideeLe) {
      return '';
    }
    const quand = new Date(lu.valideeLe).toLocaleString(intlLocale());
    const auteur = lu.valideParNom ?? lu.validePar;
    return auteur
      ? $localize`:@@journee.validation.acceptee.par:Relue le ${quand}:date: par ${auteur}:auteur:.`
      : $localize`:@@journee.validation.acceptee:Relue le ${quand}:date:.`;
  });

  protected libelle(prerequis: PrerequisValidation): string {
    return libellePrerequis(prerequis);
  }

  protected icone(prerequis: PrerequisValidation): string {
    if (!prerequis.connu) {
      return 'help';
    }
    return prerequis.satisfait ? 'check_circle' : 'error';
  }

  protected onCommentaire(event: Event): void {
    this.commentaire.set((event.target as HTMLInputElement).value);
  }

  protected async save(): Promise<void> {
    const jour = this.jour();
    const etat = this.choix();
    if (!jour || this.busy()) {
      return;
    }
    if (etat === 'FIGEE' && this.prerequis()?.etat !== 'FIGEE') {
      const confirmed = await this.confirm.ask({
        title: $localize`:@@journee.etat.figer.titre:Figer cette journée ?`,
        message: $localize`:@@journee.etat.figer.message:Le calcul ne touchera plus à cette journée : ses places restent telles quelles, même si vous relancez un calcul. Vous pouvez la rendre à « Relue » à tout moment.`,
        confirmLabel: $localize`:@@journee.etat.figer.confirmer:Figer la journée`,
      });
      if (!confirmed) {
        return;
      }
    }
    this.busy.set(true);
    try {
      await this.store.setEtat(jour, etat, etat === 'A_RELIRE' ? null : this.commentaire() || null);
      this.reported.emit(
        etat === 'FIGEE'
          ? $localize`:@@journee.etat.enregistree.figee:Journée figée : le calcul n'y touchera plus.`
          : etat === 'RELUE'
            ? $localize`:@@journee.etat.enregistree.relue:Journée relue.`
            : $localize`:@@journee.etat.enregistree.aRelire:Journée à relire de nouveau.`,
      );
    } catch (error) {
      this.erreur.set(errorPrefix(error));
    } finally {
      this.busy.set(false);
    }
  }

  /**
   * Re-reads the day's state. A failure leaves the panel silent rather than
   * breaking the page: the four renderings of the day are what the screen is
   * for, and none of them needs this to draw.
   */
  private async reload(jour: string | null): Promise<void> {
    if (!jour) {
      this.prerequis.set(null);
      return;
    }
    this.chargement.set(true);
    try {
      const lu = await this.api.prerequis(jour);
      this.prerequis.set(lu);
      this.choix.set(lu.etat);
      this.commentaire.set(lu.commentaire ?? '');
      this.erreur.set('');
      if (!this.store.progression()) {
        await this.store.reload();
      }
    } catch (error) {
      this.prerequis.set(null);
      this.erreur.set(errorPrefix(error));
    } finally {
      this.chargement.set(false);
    }
  }
}
