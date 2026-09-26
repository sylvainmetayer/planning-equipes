// The simulated clock of the instance (`/api/horloge`): the date, and the time
// of day if any, that the screens reasoning on « now » read in place of the
// machine's. Set from Paramètres › Instance where the server allows it.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { DateJourJView } from '../models';

@Injectable({ providedIn: 'root' })
export class HorlogeApi {
  private readonly api = inject(ApiService);

  read(): Promise<DateJourJView> {
    return this.api.get<DateJourJView>('/api/horloge');
  }

  /** A null date hands the whole clock back to the machine; the server answers 400 where it is not allowed. */
  write(dateDuJour: string | null, heureDuJour: string | null): Promise<DateJourJView> {
    return this.api.put<DateJourJView>('/api/horloge', { dateDuJour, heureDuJour });
  }
}
