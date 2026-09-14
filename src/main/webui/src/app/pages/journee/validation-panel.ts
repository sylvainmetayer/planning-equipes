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
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ValidationsApi } from '../../core/api/validations-api';
import { errorPrefix } from '../../core/error-message';
import { intlLocale } from '../../core/locale';
import { PrerequisJournee, PrerequisValidation } from '../../core/models';
import { ValidationsStore } from '../../core/validations.store';
import { libellePrerequis } from './validation-prerequis';

/**
 * « Relu et accepté » on the day on screen: what to check before accepting it,
 * and the one action that records the reading.
 *
 * <p>The four prerequisites are the Problèmes, Pauses and Fragilité screens
 * narrowed to this date — read from the server so this panel can never disagree
 * with them. <b>None of them blocks</b>: an organiser who knows why a seat
 * stays empty accepts the day and says so in the comment. What the panel owes
 * them is that they cannot accept it without knowing.</p>
 *
 * <p>The lock is offered beside the acceptance and never implied by it: they
 * answer two different questions, and a reviewer who means to keep re-solving
 * must be able to say « relu » without saying « figé ».</p>
 */
@Component({
  selector: 'app-validation-panel',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatCheckboxModule,
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

  /** The day on screen, `AAAA-MM-JJ`; null while the plan is still loading. */
  readonly jour = input<string | null>(null);
  /**
   * Whether the page narrows what it shows (a stand, an animateur). The reading
   * never follows the filter — it is always the whole day — so the panel says
   * so rather than let a filtered screen pass for what is being accepted.
   */
  readonly filtre = input(false);

  /** A sentence for the page's feedback: the reading was recorded, or withdrawn. */
  readonly reported = output<string>();

  protected readonly prerequis = signal<PrerequisJournee | null>(null);
  protected readonly chargement = signal(false);
  protected readonly busy = signal(false);
  protected readonly erreur = signal('');
  protected readonly commentaire = signal('');
  protected readonly poserVerrou = signal(false);

  constructor() {
    // The day changed: the whole panel describes another day, so its read-out
    // and its unsent comment both start again.
    effect(() => {
      const jour = this.jour();
      this.commentaire.set('');
      this.poserVerrou.set(false);
      void this.reload(jour);
    });
  }

  protected readonly acceptedLabel = computed(() => {
    const lu = this.prerequis();
    if (!lu?.valideeLe) {
      return '';
    }
    const quand = new Date(lu.valideeLe).toLocaleString(intlLocale());
    return lu.validePar
      ? $localize`:@@journee.validation.acceptee.par:Relue et acceptée le ${quand}:date: par ${lu.validePar}:auteur:.`
      : $localize`:@@journee.validation.acceptee:Relue et acceptée le ${quand}:date:.`;
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

  protected async accept(): Promise<void> {
    const jour = this.jour();
    if (!jour || this.busy()) {
      return;
    }
    this.busy.set(true);
    try {
      const resultat = await this.store.accept({
        jour,
        commentaire: this.commentaire() || null,
        poserVerrou: this.poserVerrou(),
      });
      this.commentaire.set('');
      this.poserVerrou.set(false);
      await this.reload(jour);
      this.reported.emit(
        resultat.verrouPose
          ? $localize`:@@journee.validation.enregistreeAvecVerrou:Journée relue et acceptée, et verrouillée pour les prochaines résolutions.`
          : $localize`:@@journee.validation.enregistree:Journée relue et acceptée.`,
      );
    } catch (error) {
      this.erreur.set(errorPrefix(error));
    } finally {
      this.busy.set(false);
    }
  }

  protected async withdraw(): Promise<void> {
    const jour = this.jour();
    // The id the read-out itself carries: the panel withdraws exactly the
    // reading it is showing, rather than the first row that looks like it.
    const validationId = this.prerequis()?.validationId;
    if (!validationId || this.busy()) {
      return;
    }
    this.busy.set(true);
    try {
      await this.store.withdraw(validationId);
      await this.reload(jour);
      this.reported.emit(
        $localize`:@@journee.validation.retiree:Validation retirée : la journée est de nouveau à relire.`,
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
      this.prerequis.set(await this.api.prerequis(jour));
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
