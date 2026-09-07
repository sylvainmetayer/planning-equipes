import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { errorPrefix } from '../../core/error-message';
import { intlLocale } from '../../core/locale';
import { EntreeHistorique } from '../../core/models';
import { keepViewInQueryParams, optionalParam } from '../../core/view-query-params';
import { StatusMessage } from '../../shared/status-message';
import {
  FiltreActeur,
  FiltreResultat,
  entitesPresentes,
  filtrer,
  lireFiltreActeur,
  lireFiltreResultat,
  parJournee,
  qui,
  surQuoi
} from './historique';

/**
 * Ce qui s'est passé dans cette édition, et par qui (issue #406).
 *
 * <p>Read-only by construction: the history has no button that changes it, and
 * what bounds it is a retention applied server-side, not a delete action. What
 * the table stores is an identifier and a list of field names — the identity is
 * joined by the server when it reads, so a fiche deleted since leaves a line
 * that names nobody.</p>
 */
@Component({
  selector: 'app-historique-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTooltipModule,
    StatusMessage
  ],
  templateUrl: './historique-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class HistoriquePage {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);

  protected readonly entrees = signal<EntreeHistorique[]>([]);
  protected readonly chargement = signal(false);
  protected readonly erreur = signal('');

  protected readonly acteur = signal<FiltreActeur>('TOUS');
  protected readonly resultat = signal<FiltreResultat>('TOUS');
  protected readonly entite = signal('');
  protected readonly recherche = signal('');

  protected readonly entites = computed(() => entitesPresentes(this.entrees()));
  protected readonly filtrees = computed(() =>
    filtrer(this.entrees(), this.acteur(), this.resultat(), this.entite(), this.recherche())
  );
  protected readonly journees = computed(() => parJournee(this.filtrees()));

  /** True as soon as the screen shows something other than its default view. */
  protected readonly vueModifiee = computed(
    () =>
      this.acteur() !== 'TOUS' ||
      this.resultat() !== 'TOUS' ||
      this.entite() !== '' ||
      this.recherche().trim() !== ''
  );

  constructor() {
    const params = this.route.snapshot.queryParamMap;
    this.acteur.set(lireFiltreActeur(params.get('acteur')));
    this.resultat.set(lireFiltreResultat(params.get('resultat')));
    this.entite.set(params.get('entite') ?? '');
    this.recherche.set(params.get('q') ?? '');
    void this.recharger();
    keepViewInQueryParams(() => ({
      acteur: this.acteur() === 'TOUS' ? null : this.acteur(),
      resultat: this.resultat() === 'TOUS' ? null : this.resultat(),
      entite: optionalParam(this.entite()),
      q: optionalParam(this.recherche())
    }));
  }

  protected async recharger(): Promise<void> {
    this.chargement.set(true);
    this.erreur.set('');
    try {
      this.entrees.set(await this.api.get<EntreeHistorique[]>('/api/historique'));
    } catch (error) {
      this.erreur.set(errorPrefix(error));
    } finally {
      this.chargement.set(false);
    }
  }

  protected reinitialiser(): void {
    this.acteur.set('TOUS');
    this.resultat.set('TOUS');
    this.entite.set('');
    this.recherche.set('');
  }

  protected qui(entree: EntreeHistorique): string {
    return qui(entree);
  }

  protected surQuoi(entree: EntreeHistorique): string {
    return surQuoi(entree);
  }

  /** « 14:32 » — the day is already the group's heading. */
  protected heure(iso: string): string {
    return new Date(iso).toLocaleTimeString(intlLocale(), { hour: '2-digit', minute: '2-digit' });
  }

  protected jourLisible(jour: string): string {
    return new Date(jour).toLocaleDateString(intlLocale(), {
      weekday: 'long',
      day: 'numeric',
      month: 'long',
      year: 'numeric'
    });
  }
}
