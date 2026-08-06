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
import { ReferenceDataStore } from '../../core/reference-data.store';
import { slugify } from '../../core/slug';
import { Creneau, DecoupageRequest, GroupeCreneau, ParametresDecoupage } from '../../core/models';
import { summarizeVacationsByDay } from './decoupage';

/**
 * Découpage automatique : à partir d'un groupe d'amplitudes (une seule
 * fenêtre d'ouverture par jour, ex. le scénario "continu"), génère les
 * vacations de travail réelles (plus courtes, chevauchantes, jamais au-dessus
 * du seuil légal de pause) dans un groupe de créneaux cible, sans saisie
 * manuelle détaillée. Voir `docs/domaine.md#découpage-automatique-en-vacations`.
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
    MatSelectModule
  ],
  templateUrl: './decoupage-page.html'
})
export class DecoupagePage {
  protected readonly store = inject(ReferenceDataStore);

  private readonly api = inject(ApiService);
  private readonly crud = inject(ReferenceCrudService);
  private readonly notifications = inject(NotificationService);

  protected readonly groupeSourceId = signal<string | null>(null);

  protected readonly parametres = signal<ParametresDecoupage | null>(null);
  protected readonly parametresLoading = signal(false);

  protected readonly previewVacations = signal<Creneau[] | null>(null);
  protected readonly previewLoading = signal(false);

  /** `null` = créer un nouveau groupe cible (voir `nomGroupeCible`). */
  protected readonly groupeCibleExistantId = signal<string | null>(null);
  protected readonly nomGroupeCible = signal('');
  protected readonly activerGroupeCible = signal(false);
  protected readonly genererLoading = signal(false);

  /** Un groupe ne peut pas se découper lui-même : jamais listé comme cible possible. */
  protected readonly groupesCiblePossibles = computed(() =>
    this.store.groupesCreneaux().filter((groupe) => groupe.id !== this.groupeSourceId())
  );

  protected readonly resume = computed(() => {
    const vacations = this.previewVacations();
    return vacations ? summarizeVacationsByDay(vacations) : [];
  });

  constructor() {
    void this.crud.reload();
    void this.chargerParametres();
  }

  protected setGroupeSource(id: string | null): void {
    this.groupeSourceId.set(id);
    this.previewVacations.set(null);
    if (id !== null && this.groupeCibleExistantId() === id) {
      this.groupeCibleExistantId.set(null);
    }
  }

  protected setGroupeCibleExistant(id: string | null): void {
    this.groupeCibleExistantId.set(id);
    const groupe = this.store.groupesCreneaux().find((g) => g.id === id);
    if (groupe) {
      this.nomGroupeCible.set(groupe.nom);
    }
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
    const groupeId = this.groupeSourceId();
    if (!groupeId) {
      return;
    }
    this.previewLoading.set(true);
    this.previewVacations.set(null);
    try {
      this.previewVacations.set(
        await this.api.get<Creneau[]>(`/api/decoupage/preview?groupeSourceId=${encodeURIComponent(groupeId)}`)
      );
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.previewLoading.set(false);
    }
  }

  protected async genererDecoupage(): Promise<void> {
    const groupeSourceId = this.groupeSourceId();
    const nom = this.nomGroupeCible().trim();
    if (!groupeSourceId || !nom) {
      return;
    }
    const groupeCibleId =
      this.groupeCibleExistantId() ??
      slugify(
        nom,
        this.store.groupesCreneaux().map((g) => g.id)
      );
    this.genererLoading.set(true);
    try {
      const requete: DecoupageRequest = {
        groupeSourceId,
        groupeCibleId,
        nomGroupeCible: nom,
        activerGroupeCible: this.activerGroupeCible()
      };
      const groupe = await this.api.post<GroupeCreneau>('/api/decoupage/generer', requete);
      await this.crud.reload();
      this.notifications.notify({
        title: $localize`:@@decoupage.generated:Découpage généré dans le groupe ${groupe.nom}:nom:.`,
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
