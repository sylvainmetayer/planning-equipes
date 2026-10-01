// The weather alert of the edition (Paramètres › Édition): its settings, the
// state of the last morning query, and « Tester maintenant ».

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { ParametresMeteo, WeatherSettingsView, WeatherTestReport } from '../models';

@Injectable({ providedIn: 'root' })
export class WeatherApi {
  private readonly api = inject(ApiService);

  settings(): Promise<WeatherSettingsView> {
    return this.api.get<WeatherSettingsView>('/api/parametres/meteo');
  }

  save(parametres: ParametresMeteo): Promise<WeatherSettingsView> {
    return this.api.put<WeatherSettingsView>('/api/parametres/meteo', parametres);
  }

  test(): Promise<WeatherTestReport> {
    return this.api.post<WeatherTestReport>('/api/parametres/meteo/test', null);
  }
}
