import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { ResponsableApi } from '../../core/api/responsable-api';
import { errorPrefix } from '../../core/error-message';
import { NotificationService } from '../../core/notification.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { StatusMessage } from '../../shared/status-message';
import { DefautEdition, LigneStand, defautDe, lignesDe, reglagesDe } from './reglage-responsables';

/**
 * « Responsables de stand » of Paramètres › Édition: what a responsable reads on
 * their stand — the shifts and the empty seats always, first and last names only
 * when this says so. One default for the edition, an exception per stand either
 * way, each with an optional opening date before which only the shifts show. The
 * sheet they print follows the very same setting.
 */
@Component({
  selector: 'app-reglage-responsables-card',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    StatusMessage,
  ],
  template: `
    <mat-card appearance="outlined" class="page-card" id="responsables">
      <mat-card-header>
        <h2 mat-card-title i18n="@@parametres.responsables.title">Responsables de stand</h2>
        <mat-card-subtitle i18n="@@parametres.responsables.subtitle"
          >Les horaires et les places à pourvoir de leur stand, toujours ; les prénoms et noms
          seulement si vous le décidez.</mat-card-subtitle
        >
      </mat-card-header>
      <mat-card-content>
        <form class="form-grid" (ngSubmit)="save()">
          <mat-slide-toggle
            name="noms"
            [disabled]="busy()"
            [ngModel]="defaut().noms"
            (ngModelChange)="patchDefaut({ noms: $event })"
            i18n="@@parametres.responsables.noms"
            >Afficher les prénoms et noms par défaut</mat-slide-toggle
          >
          @if (defaut().noms) {
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label i18n="@@parametres.responsables.ouverture">À partir du (facultatif)</mat-label>
              <input
                matInput
                type="datetime-local"
                name="ouverture"
                [disabled]="busy()"
                [ngModel]="defaut().ouverture"
                (ngModelChange)="patchDefaut({ ouverture: $event })"
              />
              <mat-hint i18n="@@parametres.responsables.ouvertureAide"
                >Avant cette date : horaires seuls.</mat-hint
              >
            </mat-form-field>
          }
          @for (ligne of lignes(); track ligne.standId) {
            <div class="responsables-ligne">
              <span>{{ standNom(ligne.standId) }}</span>
              <mat-form-field appearance="outline" subscriptSizing="dynamic">
                <mat-label i18n="@@parametres.responsables.stand">Ce stand</mat-label>
                <mat-select
                  [value]="ligne.mode"
                  [disabled]="busy()"
                  (selectionChange)="patchLigne(ligne.standId, { mode: $event.value })"
                >
                  <mat-option value="herite" i18n="@@parametres.responsables.herite"
                    >Comme l'édition</mat-option
                  >
                  <mat-option value="horaires" i18n="@@parametres.responsables.horaires"
                    >Horaires seuls</mat-option
                  >
                  <mat-option value="noms" i18n="@@parametres.responsables.avecNoms"
                    >Avec les noms</mat-option
                  >
                </mat-select>
              </mat-form-field>
              @if (ligne.mode === 'noms') {
                <mat-form-field appearance="outline" subscriptSizing="dynamic">
                  <mat-label i18n="@@parametres.responsables.ouverture">À partir du (facultatif)</mat-label>
                  <input
                    matInput
                    type="datetime-local"
                    [name]="'ouverture-' + ligne.standId"
                    [disabled]="busy()"
                    [ngModel]="ligne.ouverture"
                    (ngModelChange)="patchLigne(ligne.standId, { ouverture: $event })"
                  />
                </mat-form-field>
              }
            </div>
          }
          <app-status-message [text]="error()" tone="error" />
          <div class="form-actions">
            <button matButton="filled" type="submit" [disabled]="busy()" i18n="@@common.save">
              Enregistrer
            </button>
          </div>
        </form>
      </mat-card-content>
    </mat-card>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReglageResponsablesCard implements OnInit {
  private readonly api = inject(ResponsableApi);
  private readonly notifications = inject(NotificationService);
  private readonly referenceData = inject(ReferenceDataStore);

  protected readonly defaut = signal<DefautEdition>({ noms: false, ouverture: '' });
  protected readonly lignes = signal<LigneStand[]>([]);
  protected readonly busy = signal(false);
  protected readonly error = signal('');

  private readonly noms = computed(
    () => new Map(this.referenceData.stands().map((stand) => [stand.id, stand.nom])),
  );

  ngOnInit(): void {
    void this.load();
  }

  protected standNom(standId: string): string {
    return this.noms().get(standId) ?? standId;
  }

  protected patchDefaut(champs: Partial<DefautEdition>): void {
    this.defaut.update((courant) => ({ ...courant, ...champs }));
  }

  protected patchLigne(
    standId: string,
    champs: Partial<Pick<LigneStand, 'mode' | 'ouverture'>>,
  ): void {
    this.lignes.update((lignes) =>
      lignes.map((ligne) => (ligne.standId === standId ? { ...ligne, ...champs } : ligne)),
    );
  }

  private async load(): Promise<void> {
    try {
      const reglages = await this.api.reglages();
      this.defaut.set(defautDe(reglages));
      this.lignes.set(
        lignesDe(
          reglages,
          this.referenceData.stands().map((stand) => stand.id),
        ),
      );
    } catch (error) {
      this.error.set(errorPrefix(error));
    }
  }

  protected async save(): Promise<void> {
    this.busy.set(true);
    this.error.set('');
    try {
      const reglages = await this.api.saveReglages(reglagesDe(this.defaut(), this.lignes()));
      this.defaut.set(defautDe(reglages));
      this.lignes.update((lignes) =>
        lignesDe(
          reglages,
          lignes.map((ligne) => ligne.standId),
        ),
      );
      this.notifications.notify({
        title: $localize`:@@parametres.responsables.enregistre:Réglage des responsables de stand enregistré.`,
        variant: 'success',
        timeout: 4000,
      });
    } catch (error) {
      this.error.set(errorPrefix(error));
    } finally {
      this.busy.set(false);
    }
  }
}
