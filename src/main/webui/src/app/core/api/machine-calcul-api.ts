// Whether a full solve may run on the permanent worker (`/api/machine-calcul`, ADR 0086).

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { MachineCalcul } from '../models';

@Injectable({ providedIn: 'root' })
export class MachineCalculApi {
  private readonly api = inject(ApiService);

  read(): Promise<MachineCalcul> {
    return this.api.get<MachineCalcul>('/api/machine-calcul');
  }
}
