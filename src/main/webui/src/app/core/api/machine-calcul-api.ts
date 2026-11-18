// The compute machine a full solve may run on (`/api/machine-calcul`, ADR 0086):
// its state, and the two gestures an operator has on it.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { MachineCalcul } from '../models';

@Injectable({ providedIn: 'root' })
export class MachineCalculApi {
  private readonly api = inject(ApiService);

  read(): Promise<MachineCalcul> {
    return this.api.get<MachineCalcul>('/api/machine-calcul');
  }

  /** Starts the machine ahead of a session, so the first solve does not wait for it. */
  prepare(): Promise<MachineCalcul> {
    return this.api.post<MachineCalcul>('/api/machine-calcul/preparer', {});
  }

  /** Stops the machine now; the server answers 409 while a solve runs on it. */
  stop(): Promise<MachineCalcul> {
    return this.api.post<MachineCalcul>('/api/machine-calcul/arreter', {});
  }
}
