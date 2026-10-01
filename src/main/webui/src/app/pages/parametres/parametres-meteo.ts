import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { ConsignesApi } from '../../core/api/consignes-api';
import { WeatherApi } from '../../core/api/weather-api';
import { errorMessage } from '../../core/error-message';
import {
  ParametresMeteo,
  PrereglageConsigne,
  WeatherSettingsView,
  WeatherTestReport,
} from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { NewWindowLink } from '../../shared/new-window-link';
import { borne, phenomenonLabel } from './parametres-meteo-rules';

/**
 * « Alerte météo » of Paramètres › Édition: each morning the forecast of the
 * event's located places is read, and a threshold crossed raises an alert that
 * suggests a consigne preset — never applies it. The state of the last query
 * sits under the form, and « Tester maintenant » reads the forecast on demand,
 * raising nothing.
 */
@Component({
  selector: 'app-parametres-meteo',
  imports: [
    DatePipe,
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    NewWindowLink,
  ],
  templateUrl: './parametres-meteo.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ParametresMeteoPanel implements OnInit {
  private readonly api = inject(WeatherApi);
  private readonly consignes = inject(ConsignesApi);
  private readonly notifications = inject(NotificationService);

  protected readonly view = signal<WeatherSettingsView | null>(null);
  protected readonly reglages = signal<ParametresMeteo | null>(null);
  protected readonly prereglages = signal<PrereglageConsigne[]>([]);
  protected readonly enregistrement = signal(false);
  protected readonly test = signal<WeatherTestReport | null>(null);
  protected readonly testRunning = signal(false);

  ngOnInit(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      const [view, prereglages] = await Promise.all([
        this.api.settings(),
        this.consignes.prereglages(),
      ]);
      this.apply(view);
      this.prereglages.set(prereglages);
    } catch {
      // The rest of the page stays usable; the block does not render.
      this.view.set(null);
    }
  }

  private apply(view: WeatherSettingsView): void {
    this.view.set(view);
    this.reglages.set({ ...view.settings });
  }

  protected patch(champs: Partial<ParametresMeteo>): void {
    const courant = this.reglages();
    if (courant) {
      this.reglages.set({ ...courant, ...champs });
    }
  }

  protected updateNumber(
    champ: 'horizonJours' | 'seuilTemperature' | 'seuilRafales',
    valeur: string,
    min: number,
    max: number,
  ): void {
    const value = borne(valeur, min, max);
    if (value !== null) {
      this.patch({ [champ]: value });
    }
  }

  protected phenomenonLabel(code: string): string {
    return phenomenonLabel(code);
  }

  protected async save(): Promise<void> {
    const reglages = this.reglages();
    if (!reglages) {
      return;
    }
    this.enregistrement.set(true);
    try {
      this.apply(await this.api.save(reglages));
      this.notifications.notify({
        title: $localize`:@@parametres.meteo.enregistre:Alerte météo enregistrée.`,
        variant: 'success',
        timeout: 4000,
      });
    } catch (error) {
      this.notifyError(error);
    } finally {
      this.enregistrement.set(false);
    }
  }

  protected async tester(): Promise<void> {
    this.testRunning.set(true);
    try {
      this.test.set(await this.api.test());
    } catch (error) {
      this.notifyError(error);
    } finally {
      this.testRunning.set(false);
    }
  }

  private notifyError(error: unknown): void {
    this.notifications.notify({
      title: $localize`:@@crud.error:Erreur`,
      message: errorMessage(error),
      variant: 'error',
    });
  }
}
