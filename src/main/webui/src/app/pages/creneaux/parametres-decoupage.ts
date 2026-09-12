import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { CreneauxApi } from '../../core/api/creneaux-api';
import { ParametresDecoupage } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { StatusMessage } from '../../shared/status-message';

/**
 * The découpage settings, as one card of the Créneaux page, right above the
 * generation they drive. They used to live on the Paramètres page, a screen
 * away from the button that reads them.
 *
 * The card owns its own copy of the settings: `PUT /api/parametres-decoupage`
 * never writes the grid mode, so a save here cannot undo the mode switch the
 * page makes next to it.
 */
@Component({
  selector: 'app-parametres-decoupage',
  imports: [
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    StatusMessage,
  ],
  templateUrl: './parametres-decoupage.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ParametresDecoupageCard {
  /**
   * The grid is declared as final vacations: nothing is left to slice, so the
   * settings are shown but cannot be edited — they only ever apply to
   * amplitudes.
   */
  readonly inactif = input(false);
  /** A solve runs on this edition: the save is held back, like every other edition-scoped write. */
  readonly verrouille = input(false);

  protected readonly parametresDecoupage = signal<ParametresDecoupage | null>(null);
  protected readonly parametresDecoupageLoading = signal(false);

  private readonly creneauxApi = inject(CreneauxApi);
  private readonly crud = inject(ReferenceCrudService);
  private readonly notifications = inject(NotificationService);

  /**
   * What the current settings would produce, in one sentence, recomputed as
   * they are typed: "am I about to cut 4-hour or 8-hour vacations?" before
   * the user commits to it.
   */
  protected readonly apercuParametres = computed(() => {
    const p = this.parametresDecoupage();
    if (!p) {
      return '';
    }
    const heures = (minutes: number) =>
      (minutes / 60).toFixed(1).replace('.0', '').replace('.', ',');
    return $localize`:@@decoupage.apercu:Avec ces réglages : des vacations d'environ ${heures(p.dureeVacationCibleMinutes)}:cible: h (jamais plus de ${heures(p.dureeVacationMaxMinutes)}:max: h) et un relais de ${p.dureeChevauchementMinutes}:chevauchement: min.`;
  });

  constructor() {
    void this.load();
  }

  /**
   * Immutable field update: the « Avec ces réglages » preview is a computed
   * over the `parametresDecoupage` signal, and a zoneless app never notices
   * an in-place mutation. Replacing the object is what makes it live while
   * typing.
   */
  protected patchParametre(patch: Partial<ParametresDecoupage>): void {
    this.parametresDecoupage.update((p) => (p ? { ...p, ...patch } : p));
  }

  private async load(): Promise<void> {
    this.parametresDecoupageLoading.set(true);
    try {
      this.parametresDecoupage.set(await this.creneauxApi.slicingParameters());
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.parametresDecoupageLoading.set(false);
    }
  }

  protected async sauvegarder(): Promise<void> {
    const parametres = this.parametresDecoupage();
    if (!parametres || this.inactif() || this.verrouille()) {
      return;
    }
    this.parametresDecoupageLoading.set(true);
    try {
      this.parametresDecoupage.set(await this.creneauxApi.saveSlicingParameters(parametres));
      this.notifications.notify({
        title: $localize`:@@decoupage.parametresSaved:Paramètres de découpage enregistrés.`,
        variant: 'success',
        timeout: 4000,
      });
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.parametresDecoupageLoading.set(false);
    }
  }
}
