import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { ActivatedRoute } from '@angular/router';
import { CLOCK_CARD_ANCHOR, DateMockService, TODAY_ANCHOR } from '../../core/date-mock.service';
import { errorMessage } from '../../core/error-message';
import { StatusMessage } from '../../shared/status-message';

/**
 * « Date et heure simulées », on Paramètres › Instance: the date — and the
 * time if any — the screens reasoning on « now » read in place of the
 * machine's, so the mode jour J, the espace animateur and the frozen past can
 * be shown out of season.
 *
 * <p>A need of the demonstration server as much as of development, which is
 * why it left the Débogage page: it renders only where the server says the
 * simulated clock is allowed (under `quarkus:dev`, or with
 * `HORLOGE_SIMULEE_AUTORISEE=true`). That is a courtesy — the server refuses
 * the write anywhere else, whatever this card believes.</p>
 *
 * <p>Saved on change, with no Validate button: each field holds one value, and
 * a second click to confirm a date somebody just picked buys nothing. A
 * refusal is shown under the fields rather than as a toast — it is an answer
 * about this control and should stay next to it.</p>
 */
@Component({
  selector: 'app-horloge-simulee-card',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    StatusMessage,
  ],
  template: `
    @if (dates.modifiable()) {
      <mat-card appearance="outlined" class="page-card" [id]="cardAnchor">
        <mat-card-header>
          <h2 mat-card-title i18n="@@parametres.horloge.title">Date et heure simulées</h2>
          <mat-card-subtitle i18n="@@parametres.horloge.subtitle">
            La date, et si besoin l'heure, que lisent le mode jour J et l'espace animateur sur ce serveur.
          </mat-card-subtitle>
        </mat-card-header>
        <mat-card-content>
          <mat-form-field appearance="outline" class="horloge-simulee-champ">
            <mat-label i18n="@@parametres.horloge.date">Date simulée</mat-label>
            <input
              matInput
              #champDate
              [id]="todayAnchor"
              type="date"
              name="dateSimulee"
              [ngModel]="dates.dateDuJour()"
              (ngModelChange)="onDate($event)"
            />
          </mat-form-field>
          <mat-form-field appearance="outline" class="horloge-simulee-champ">
            <mat-label i18n="@@parametres.horloge.heure">Heure (facultative)</mat-label>
            <input
              matInput
              id="heure-du-jour"
              type="time"
              name="heureSimulee"
              [disabled]="!dates.actif()"
              [ngModel]="dates.heureMock()"
              (ngModelChange)="onHeure($event)"
            />
            <mat-hint i18n="@@parametres.horloge.heureHint">Sans heure, l'horloge tourne sur la date simulée.</mat-hint>
          </mat-form-field>
          <app-status-message [text]="erreur()" tone="error" />
          @if (dates.actif()) {
            <p class="horloge-simulee-actif" i18n="@@parametres.horloge.actif">
              Date simulée : les écrans qui raisonnent sur « maintenant » ne montrent pas la réalité.
            </p>
          }
        </mat-card-content>
        <mat-card-actions class="card-actions">
          <button matButton="outlined" type="button" [disabled]="!dates.actif()" (click)="revenirHorlogeMachine()">
            <mat-icon>restore</mat-icon>
            <ng-container i18n="@@parametres.horloge.reel">Revenir à l'horloge de la machine</ng-container>
          </button>
        </mat-card-actions>
      </mat-card>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HorlogeSimuleeCard {
  protected readonly dates = inject(DateMockService);
  protected readonly todayAnchor = TODAY_ANCHOR;
  protected readonly cardAnchor = CLOCK_CARD_ANCHOR;
  protected readonly erreur = signal('');

  /**
   * Resolves only once the field is rendered, which is itself conditional on
   * the server saying the setting may be used — so this is what the deep link
   * of the toolbar indicator has to wait for.
   */
  private readonly champDate = viewChild<ElementRef<HTMLInputElement>>('champDate');

  /**
   * `?focus=date-du-jour`, set by the toolbar indicator. Read once and
   * honoured once: the point is to land on the control, not to steal the focus
   * back every time the page re-renders. An unknown value does nothing
   * (decision 0012: reading view state is tolerant).
   */
  private focusPending =
    inject(ActivatedRoute, { optional: true })?.snapshot.queryParamMap.get('focus') ===
    TODAY_ANCHOR;

  constructor() {
    effect(() => {
      const champ = this.champDate();
      if (!champ || !this.focusPending) {
        return;
      }
      this.focusPending = false;
      // Scrolling is the nicety, the focus is the point: jsdom has no
      // scrollIntoView, and neither does an old browser.
      champ.nativeElement.scrollIntoView?.({ block: 'center' });
      champ.nativeElement.focus();
    });
  }

  /** Clearing the date clears the time: a time alone is refused server-side. */
  protected async onDate(valeur: string): Promise<void> {
    await this.save(valeur, valeur ? this.dates.heureMock() : '');
  }

  /** An empty time gives the wall clock back its hours, on the simulated date. */
  protected async onHeure(valeur: string): Promise<void> {
    await this.save(this.dates.dateDuJour(), valeur ?? '');
  }

  protected async revenirHorlogeMachine(): Promise<void> {
    await this.save('', '');
  }

  private async save(date: string, heure: string): Promise<void> {
    this.erreur.set('');
    try {
      await this.dates.set(date, heure);
    } catch (error) {
      this.erreur.set(errorMessage(error));
    }
  }
}
