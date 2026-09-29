import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatToolbarModule } from '@angular/material/toolbar';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AdminApi, accountUrl } from '../../core/api/admin-api';
import { ResponsableApi } from '../../core/api/responsable-api';
import { toDateKey } from '../../core/date-utils';
import { errorMessage } from '../../core/error-message';
import { EditionResponsable, ResponsableView } from '../../core/models';
import { signOut } from '../../core/session';
import { BrandLogo } from '../../shared/brand-logo';
import { StatusMessage } from '../../shared/status-message';
import {
  OngletResponsable,
  anyNamed,
  daysOf,
  initialDay,
  shiftsOn,
  staffingLabel,
  teamOn,
  windowLabel,
  windowsOn,
} from './responsable';

/** Where the page stands before it can show a plan. */
type Etat = 'chargement' | 'connexion' | 'sans-droit' | 'erreur' | 'pret';

/**
 * The responsable de stand's screen (`/responsable`, issue #295): outside
 * both shells, opened by a Keycloak session alone — no link token, since the
 * right is the account's, not a fiche's.
 *
 * <p>One edition at a time, the default one first. Two views of the
 * <b>published</b> plan of the stands in scope: « Mon stand, jour par jour »
 * (each shift, its head count, the names when the stand is shown by name) and
 * « Mon équipe » (who, and when they are taken elsewhere — never where). Each
 * stand prints its day as an A4 sheet to tape on the stand.</p>
 *
 * <p>What this screen hides is comfort, never a protection: the server
 * serves nothing it would have to hide.</p>
 */
@Component({
  selector: 'app-responsable-page',
  imports: [
    DatePipe,
    RouterLink,
    BrandLogo,
    StatusMessage,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatProgressBarModule,
    MatSelectModule,
    MatToolbarModule,
  ],
  templateUrl: './responsable-page.html',
  styleUrl: './responsable-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ResponsablePage {
  private readonly api = inject(ResponsableApi);
  private readonly adminApi = inject(AdminApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly etat = signal<Etat>('chargement');
  protected readonly erreur = signal('');
  protected readonly editions = signal<EditionResponsable[]>([]);
  protected readonly editionId = signal<string | null>(null);
  protected readonly vue = signal<ResponsableView | null>(null);
  protected readonly jour = signal<string | null>(null);
  protected readonly onglet = signal<OngletResponsable>('planning');

  protected readonly jours = computed(() => daysOf(this.vue()));
  protected readonly nomme = computed(() => anyNamed(this.vue()));
  protected readonly equipeDuJour = computed(() => {
    const jour = this.jour();
    return jour ? teamOn(this.vue(), jour) : [];
  });
  protected readonly accountHref = accountUrl('/responsable');

  protected readonly shiftsOn = shiftsOn;
  protected readonly windowsOn = windowsOn;
  protected readonly windowLabel = windowLabel;
  protected readonly staffingLabel = staffingLabel;

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      const editions = await this.api.editions();
      this.editions.set(editions);
      if (editions.length === 0) {
        this.etat.set('sans-droit');
        return;
      }
      const demandee = this.route.snapshot.queryParamMap.get('edition');
      const edition = editions.find((e) => e.editionId === demandee) ?? editions[0];
      const onglet = this.route.snapshot.queryParamMap.get('onglet');
      this.onglet.set(onglet === 'equipe' ? 'equipe' : 'planning');
      await this.open(edition.editionId, this.route.snapshot.queryParamMap.get('jour'));
    } catch (error) {
      this.fail(error);
    }
  }

  protected async open(editionId: string, jourDemande: string | null = null): Promise<void> {
    this.etat.set('chargement');
    this.editionId.set(editionId);
    try {
      const vue = await this.api.view(editionId);
      this.vue.set(vue);
      this.jour.set(initialDay(daysOf(vue), jourDemande, toDateKey(new Date())));
      this.etat.set('pret');
      this.syncUrl();
    } catch (error) {
      this.fail(error);
    }
  }

  protected pickDay(jour: string): void {
    this.jour.set(jour);
    this.syncUrl();
  }

  protected pickOnglet(onglet: OngletResponsable): void {
    this.onglet.set(onglet);
    this.syncUrl();
  }

  private syncUrl(): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      replaceUrl: true,
      queryParams: {
        edition: this.editionId(),
        jour: this.jour(),
        onglet: this.onglet() === 'equipe' ? 'equipe' : null,
      },
    });
  }

  private fail(error: unknown): void {
    if (error instanceof HttpErrorResponse && error.status === 401) {
      this.etat.set('connexion');
    } else if (
      error instanceof HttpErrorResponse &&
      (error.status === 403 || error.status === 404)
    ) {
      this.etat.set('sans-droit');
    } else {
      this.erreur.set(errorMessage(error));
      this.etat.set('erreur');
    }
  }

  protected signIn(): void {
    window.location.assign(this.adminApi.oidcLoginUrl('/responsable'));
  }

  protected signOut(): Promise<void> {
    return signOut(this.adminApi, '/responsable');
  }
}
