import { Component, computed, inject } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { computeStaffingSummary } from './staffing';

/**
 * Staffing-need calculator: from the configured stands and créneaux alone
 * (effectifMin, reserveMajeurs, standsOuvertsIds), computes the minimum
 * number of animateurs to recruit and how many of them must be majeurs vs
 * can be mineurs. Purely derived client-side, no backend call — see
 * `computeStaffingSummary` for the methodology.
 */
@Component({
  selector: 'app-staffing-page',
  imports: [MatCardModule, MatIconModule, MatTableModule, MatTooltipModule],
  templateUrl: './staffing-page.html'
})
export class StaffingPage {
  protected readonly store = inject(ReferenceDataStore);
  protected readonly columns = ['creneau', 'standsOuverts', 'total', 'majeurs', 'mineurs'];
  protected readonly summary = computed(() => computeStaffingSummary(this.store.stands(), this.store.creneaux()));

  private readonly crud = inject(ReferenceCrudService);

  constructor() {
    void this.crud.reload();
  }
}
