import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { ApiService } from '../../core/api.service';
import { NotificationService } from '../../core/notification.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { Creneau, ParametresDecoupage } from '../../core/models';
import { StatusMessage } from '../../shared/status-message';
import { summarizeVacationsByDay } from './decoupage';

/**
 * Découpage automatique : à partir des créneaux actuels de l'édition, lus
 * comme des amplitudes (une seule fenêtre d'ouverture par jour, ex. le
 * scénario "continu"), génère les vacations de travail réelles (plus courtes,
 * chevauchantes, jamais au-dessus du seuil légal de pause) et les substitue
 * EN PLACE aux amplitudes (issue #172 : l'édition ne porte qu'une grille).
 * Voir `docs/domaine.md#découpage-automatique-en-vacations`.
 */
@Component({
  selector: 'app-decoupage-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    StatusMessage
  ],
  templateUrl: './decoupage-page.html'
})
export class DecoupagePage {
  private readonly api = inject(ApiService);
  private readonly crud = inject(ReferenceCrudService);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);

  protected readonly parametres = signal<ParametresDecoupage | null>(null);
  protected readonly parametresLoading = signal(false);

  /**
   * What the current settings would produce, in one sentence, recomputed as
   * they are typed. The generation itself is a separate, explicit action: this
   * only answers "am I about to cut 4-hour or 8-hour vacations?" before the
   * user commits to it.
   */
  protected readonly apercuParametres = computed(() => {
    const p = this.parametres();
    if (!p) {
      return '';
    }
    const heures = (minutes: number) => (minutes / 60).toFixed(1).replace('.0', '').replace('.', ',');
    const familles =
      p.nombreFamillesDecalage > 1
        ? $localize`:@@decoupage.apercu.familles:, réparties sur ${p.nombreFamillesDecalage}:count: grilles décalées`
        : '';
    return $localize`:@@decoupage.apercu:Avec ces réglages : des vacations d'environ ${heures(p.dureeVacationCibleMinutes)}:cible: h (jamais plus de ${heures(p.dureeVacationMaxMinutes)}:max: h), un relais de ${p.dureeChevauchementMinutes}:chevauchement: min et une pause repas de ${p.dureePauseRepasMinutes}:repas: min${familles}:familles:.`;
  });

  protected readonly previewVacations = signal<Creneau[] | null>(null);
  protected readonly previewLoading = signal(false);
  protected readonly genererLoading = signal(false);

  protected readonly resume = computed(() => {
    const vacations = this.previewVacations();
    return vacations ? summarizeVacationsByDay(vacations) : [];
  });

  constructor() {
    void this.crud.reload();
    void this.chargerParametres();
  }

  private async chargerParametres(): Promise<void> {
    this.parametresLoading.set(true);
    try {
      this.parametres.set(await this.api.get<ParametresDecoupage>('/api/parametres-decoupage'));
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.parametresLoading.set(false);
    }
  }

  protected async sauvegarderParametres(): Promise<void> {
    const parametres = this.parametres();
    if (!parametres) {
      return;
    }
    this.parametresLoading.set(true);
    try {
      this.parametres.set(await this.api.put<ParametresDecoupage>('/api/parametres-decoupage', parametres));
      this.notifications.notify({
        title: $localize`:@@decoupage.parametresSaved:Paramètres de découpage enregistrés.`,
        variant: 'success',
        timeout: 4000
      });
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.parametresLoading.set(false);
    }
  }

  protected async previsualiser(): Promise<void> {
    this.previewLoading.set(true);
    this.previewVacations.set(null);
    try {
      this.previewVacations.set(await this.api.get<Creneau[]>('/api/decoupage/preview'));
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.previewLoading.set(false);
    }
  }

  /** Confirmed first: the generation replaces the edition's créneaux and erases the persisted plan with them. */
  protected async genererDecoupage(): Promise<void> {
    const confirme = await this.confirm.ask({
      title: $localize`:@@decoupage.generer.title:Générer le découpage`,
      message: $localize`:@@decoupage.generer.confirm:Les créneaux actuels de l'édition (les amplitudes) seront remplacés par les vacations générées, et le planning résolu sera effacé avec eux. Pour re-découper avec d'autres paramètres, il faudra ré-importer le scénario source.`,
      confirmLabel: $localize`:@@decoupage.generer.submitCourt:Générer les vacations`,
      danger: true
    });
    if (!confirme) {
      return;
    }
    this.genererLoading.set(true);
    try {
      await this.api.post('/api/decoupage/generer', {});
      await this.crud.reload();
      this.notifications.notify({
        title: $localize`:@@decoupage.generated:Découpage généré : les vacations ont remplacé les amplitudes.`,
        variant: 'success',
        timeout: 6000
      });
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.genererLoading.set(false);
    }
  }
}
