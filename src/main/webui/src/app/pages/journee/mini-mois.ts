import {
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ConsignesStore } from '../../core/consignes.store';
import { parseDateKey } from '../../core/date-utils';
import { intlLocale } from '../../core/locale';
import { PosteAffectation } from '../../core/models';
import { ValidationsStore } from '../../core/validations.store';
import { VerrouillageStore } from '../../core/verrouillage.store';
import { JourEvenement } from './journee';
import { CaseMois, emptySeatsByDate, initialesSemaine, libelleCase, eventMonths } from './mois';

/**
 * The Planning page's day selector: the day on screen, the arrows either
 * side, and — unfolded — the event's month(s), each day marked with its empty
 * seats, its reading, its lock and its consigne. « Aujourd'hui » is the
 * server's date (the simulated one included), never the browser's.
 *
 * It replaces both the sixteen-entry `mat-select` of the Journée and the
 * `/calendar` screen, whose month grid answered the same question with none of
 * the page's gestures.
 */
@Component({
  selector: 'app-mini-mois',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule],
  template: `
    <div class="mini-mois">
      <button matIconButton type="button" [disabled]="premier()" (click)="decaler(-1)"
              [attr.aria-label]="precedentLabel" [title]="precedentLabel">
        <mat-icon>chevron_left</mat-icon>
      </button>
      <button matButton type="button" class="mini-mois-bascule" (click)="basculer()"
              [attr.aria-expanded]="ouvert()" aria-controls="mini-mois-panneau">
        <mat-icon>calendar_month</mat-icon>
        <span class="mini-mois-titre">{{ titre() }}</span>
        <mat-icon iconPositionEnd>{{ ouvert() ? 'expand_less' : 'expand_more' }}</mat-icon>
      </button>
      <button matIconButton type="button" [disabled]="dernier()" (click)="decaler(1)"
              [attr.aria-label]="suivantLabel" [title]="suivantLabel">
        <mat-icon>chevron_right</mat-icon>
      </button>
    </div>
    @if (ouvert()) {
      <div id="mini-mois-panneau" class="mini-mois-panneau" tabindex="-1" (keydown.escape)="fermer()">
        @for (mois of mois(); track mois.cle) {
          <table class="mini-mois-table">
            <caption>{{ mois.titre }}</caption>
            <thead>
              <tr>
                @for (initiale of initiales; track $index) {
                  <th scope="col" aria-hidden="true">{{ initiale }}</th>
                }
              </tr>
            </thead>
            <tbody>
              @for (semaine of mois.semaines; track semaine[0].date) {
                <tr>
                  @for (cellule of semaine; track cellule.date) {
                    <td>
                      @if (cellule.horsMois) {
                        <span class="mini-mois-vide"></span>
                      } @else if (cellule.jour; as jour) {
                        <button type="button" class="mini-mois-jour"
                                [class.mini-mois-courant]="jour.key === courant()?.key"
                                [class.mini-mois-aujourdhui]="cellule.aujourdhui"
                                [class.mini-mois-trous]="cellule.vides > 0"
                                [attr.aria-current]="jour.key === courant()?.key ? 'date' : null"
                                [attr.aria-label]="libelle(cellule)" [matTooltip]="libelle(cellule)"
                                (click)="choisir(jour.key)">
                          <span class="mini-mois-numero">{{ cellule.numero }}</span>
                          <span class="mini-mois-marques" aria-hidden="true">
                            @if (cellule.vides > 0) {
                              <span class="mini-mois-point"></span>
                            }
                            @if (cellule.relu) {
                              <mat-icon inline>task_alt</mat-icon>
                            }
                            @if (cellule.verrou) {
                              <mat-icon inline>lock</mat-icon>
                            }
                            @if (cellule.consigne) {
                              <mat-icon inline>gavel</mat-icon>
                            }
                          </span>
                        </button>
                      } @else {
                        <span class="mini-mois-hors" [class.mini-mois-aujourdhui]="cellule.aujourdhui">{{ cellule.numero }}</span>
                      }
                    </td>
                  }
                </tr>
              }
            </tbody>
          </table>
        } @empty {
          <!-- Timeslots without a date: no month to draw, the days as they are numbered. -->
          <ul class="mini-mois-sans-date">
            @for (jour of jours(); track jour.key) {
              <li>
                <button matButton type="button" [attr.aria-current]="jour.key === courant()?.key ? 'date' : null"
                        (click)="choisir(jour.key)">{{ jour.title }}</button>
              </li>
            }
          </ul>
        }
        <ul class="mini-mois-legende">
          <li><span class="mini-mois-point" aria-hidden="true"></span><ng-container i18n="@@journee.mois.legende.vides">sièges vides</ng-container></li>
          <li><mat-icon inline aria-hidden="true">task_alt</mat-icon><ng-container i18n="@@journee.mois.legende.relu">relue</ng-container></li>
          <li><mat-icon inline aria-hidden="true">lock</mat-icon><ng-container i18n="@@journee.mois.legende.verrou">verrou</ng-container></li>
          <li><mat-icon inline aria-hidden="true">gavel</mat-icon><ng-container i18n="@@journee.mois.legende.consigne">consigne</ng-container></li>
          <li><span class="mini-mois-aujourdhui mini-mois-legende-jour" aria-hidden="true"></span><ng-container i18n="@@journee.mois.legende.aujourdhui">aujourd'hui (serveur)</ng-container></li>
        </ul>
      </div>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MiniMois {
  private readonly validations = inject(ValidationsStore);
  private readonly verrous = inject(VerrouillageStore);
  private readonly consignes = inject(ConsignesStore);
  private readonly hote = inject<ElementRef<HTMLElement>>(ElementRef);

  /** The days of the plan, in order. */
  readonly jours = input<readonly JourEvenement[]>([]);
  /** The day on screen. */
  readonly courant = input<JourEvenement | null>(null);
  /** The plan's seats, for the empty ones of each day. */
  readonly postes = input<readonly PosteAffectation[]>([]);
  /** A day was chosen, by its key. */
  readonly choisi = output<string>();

  protected readonly ouvert = signal(false);
  protected readonly initiales = initialesSemaine();
  protected readonly precedentLabel = $localize`:@@journee.previousDay:Jour précédent`;
  protected readonly suivantLabel = $localize`:@@journee.nextDay:Jour suivant`;

  private readonly index = computed(() =>
    this.jours().findIndex((jour) => jour.key === this.courant()?.key),
  );
  protected readonly premier = computed(() => this.index() <= 0);
  protected readonly dernier = computed(() => this.index() >= this.jours().length - 1);

  protected readonly mois = computed(() =>
    eventMonths(
      this.jours(),
      emptySeatsByDate(this.postes()),
      {
        relu: (date) => this.validations.acceptedDays().has(date),
        verrou: (date) => this.verrous.estJourVerrouille(date),
        consigne: (date) => this.consignes.consigneOf(date) !== null,
      },
      this.consignes.aujourdhui(),
    ),
  );

  /** « samedi 5 septembre · J5 », or the numbered title of an undated day. */
  protected readonly titre = computed(() => {
    const jour = this.courant();
    if (!jour) {
      return $localize`:@@journee.mois.aucunJour:Aucune journée`;
    }
    if (!jour.date) {
      return jour.title;
    }
    const date = parseDateKey(jour.date).toLocaleDateString(intlLocale(), {
      weekday: 'long',
      day: 'numeric',
      month: 'long',
    });
    return `${date} · J${jour.jour}`;
  });

  constructor() {
    // The markers read three stores the page may not have filled yet; a
    // failure leaves a day unmarked, never the selector broken.
    if (!this.validations.progression()) {
      void this.validations.reload();
    }
    void this.verrous.reload().catch(() => undefined);
    if (!this.consignes.etat()) {
      void this.consignes.reload();
    }
  }

  protected libelle(cellule: CaseMois): string {
    return libelleCase(cellule);
  }

  protected basculer(): void {
    this.ouvert.update((ouvert) => !ouvert);
  }

  protected fermer(): void {
    this.ouvert.set(false);
    this.hote.nativeElement.querySelector<HTMLElement>('.mini-mois-bascule')?.focus();
  }

  protected choisir(key: string): void {
    this.choisi.emit(key);
    this.fermer();
  }

  protected decaler(delta: number): void {
    const target = this.jours()[this.index() + delta];
    if (target) {
      this.choisi.emit(target.key);
    }
  }
}
