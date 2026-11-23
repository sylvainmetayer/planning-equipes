import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ResponsableApi } from '../../core/api/responsable-api';
import { errorMessage } from '../../core/error-message';
import { StandResponsable, ResponsableView } from '../../core/models';
import { StatusMessage } from '../../shared/status-message';
import { shiftsOn, staffingLabel, windowLabel } from './responsable';

/**
 * One stand's day on an A4 sheet (`/responsable/affichette`, issue #295 §6):
 * the planning to tape on the stand. Bounded by nature — one stand, one day —,
 * never a bulk export; read from the same route as the screen, with the stand
 * named, so the server refuses a stand out of scope exactly as it refuses one
 * that does not exist. Names only where the stand is shown by name.
 */
@Component({
  selector: 'app-responsable-affichette-page',
  imports: [
    DatePipe,
    RouterLink,
    StatusMessage,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
  ],
  template: `
    <nav class="affichette-actions">
      <a matButton routerLink="/responsable" [queryParams]="{ edition: editionId, jour: jour }">
        <mat-icon>arrow_back</mat-icon>
        <span i18n="@@responsable.affichette.retour">Retour</span>
      </a>
      @if (stand()) {
        <button matButton="filled" type="button" (click)="print()">
          <mat-icon>print</mat-icon>
          <span i18n="@@responsable.affichette.imprimer">Imprimer</span>
        </button>
      }
    </nav>
    <main class="affichette-feuille">
      @if (chargement()) {
        <mat-progress-bar mode="indeterminate" />
      } @else if (stand(); as s) {
        <header>
          <h1>{{ s.standNom }}</h1>
          <p>
            @if (s.emplacementNom) {
              {{ s.emplacementNom }} ·
            }
            {{ jour | date: 'EEEE d MMMM y' }} · {{ view()?.editionNom }}
          </p>
        </header>
        @if (vacations().length === 0) {
          <p i18n="@@responsable.aucunCreneau">Aucun créneau ce jour-là.</p>
        } @else {
          <table class="affichette-table">
            <caption class="visually-hidden" i18n="@@responsable.affichette.legende">
              Créneaux du stand ce jour-là
            </caption>
            <thead>
              <tr>
                <th scope="col" i18n="@@responsable.affichette.horaire">Horaire</th>
                <th scope="col" i18n="@@responsable.affichette.equipe">Équipe</th>
              </tr>
            </thead>
            <tbody>
              @for (vacation of vacations(); track vacation.debut + vacation.fin) {
                <tr>
                  <td class="affichette-heure">{{ windowLabel(vacation.debut, vacation.fin) }}</td>
                  <td>
                    @if (s.nominatif) {
                      @for (personne of vacation.personnes; track $index) {
                        <div>{{ personne.prenom }} {{ personne.nom }}</div>
                      }
                      @for (vide of emptySeats(vacation.vides); track $index) {
                        <div class="affichette-vide">&nbsp;</div>
                      }
                    } @else {
                      {{ staffingLabel(vacation) }}
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        }
        @if (view()?.publieLe; as publieLe) {
          <footer i18n="@@responsable.affichette.pied">
            Planning publié le {{ publieLe | date: 'd MMMM y, HH:mm' }} — il peut avoir changé depuis.
          </footer>
        }
      } @else {
        <app-status-message [text]="erreur()" tone="error" />
      }
    </main>
  `,
  styleUrl: './responsable-affichette-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ResponsableAffichettePage implements OnInit {
  private readonly api = inject(ResponsableApi);
  private readonly route = inject(ActivatedRoute);

  protected readonly editionId = this.route.snapshot.queryParamMap.get('edition') ?? '';
  protected readonly standId = this.route.snapshot.queryParamMap.get('stand') ?? '';
  protected readonly jour = this.route.snapshot.queryParamMap.get('jour') ?? '';

  protected readonly chargement = signal(true);
  protected readonly erreur = signal('');
  protected readonly view = signal<ResponsableView | null>(null);
  protected readonly stand = computed<StandResponsable | null>(
    () => this.view()?.stands[0] ?? null,
  );
  protected readonly vacations = computed(() => {
    const stand = this.stand();
    return stand ? shiftsOn(stand, this.jour) : [];
  });

  protected readonly windowLabel = windowLabel;
  protected readonly staffingLabel = staffingLabel;

  ngOnInit(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      this.view.set(await this.api.view(this.editionId, this.standId));
    } catch (error) {
      this.erreur.set(
        error instanceof HttpErrorResponse && error.status === 404
          ? $localize`:@@responsable.affichette.introuvable:Ce stand ne fait pas partie de votre périmètre.`
          : errorMessage(error),
      );
    } finally {
      this.chargement.set(false);
    }
  }

  /** One blank line per empty seat: room to write a name in by hand. */
  protected emptySeats(count: number): number[] {
    return Array.from({ length: count }, (_, i) => i);
  }

  protected print(): void {
    window.print();
  }
}
