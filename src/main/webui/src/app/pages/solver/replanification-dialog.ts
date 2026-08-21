import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { intlLocale } from '../../core/locale';
import { PerimetreReplanification } from '../../core/models';
import { ReferenceDataStore } from '../../core/reference-data.store';

/**
 * Perimeter of an incremental re-solve (issue #86).
 *
 * <p>Everything the plan still satisfies is frozen by default, and only what a
 * late change invalidated re-opens — that automatic part needs no input at all,
 * which is why this dialog can be validated empty. The three lists below are
 * the other half of the real workflow: "untel se désiste, refais sa journée",
 * where the operator knows what must move before the referential says so.</p>
 */
@Component({
  selector: 'app-replanification-dialog',
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatSelectModule
  ],
  templateUrl: './replanification-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ReplanificationDialog {
  protected readonly dialogRef =
    inject<MatDialogRef<ReplanificationDialog, PerimetreReplanification>>(MatDialogRef);

  protected readonly store = inject(ReferenceDataStore);

  protected readonly animateurIds = signal<string[]>([]);
  protected readonly jours = signal<string[]>([]);
  protected readonly standIds = signal<string[]>([]);

  /** The festival days, deduplicated from the créneaux and in chronological order. */
  protected readonly joursDisponibles = computed(() =>
    [...new Set(this.store.creneaux().map((creneau) => creneau.date))].sort()
  );

  protected readonly animateursTries = computed(() =>
    [...this.store.animateurs()].sort((a, b) =>
      `${a.nom} ${a.prenom}`.localeCompare(`${b.nom} ${b.prenom}`, intlLocale())
    )
  );

  protected readonly standsTries = computed(() =>
    [...this.store.stands()].sort((a, b) => a.nom.localeCompare(b.nom, intlLocale()))
  );

  protected readonly perimetreVide = computed(
    () => this.animateurIds().length === 0 && this.jours().length === 0 && this.standIds().length === 0
  );

  protected jourLabel(jour: string): string {
    return new Date(`${jour}T00:00:00`).toLocaleDateString(intlLocale(), {
      weekday: 'long',
      day: 'numeric',
      month: 'long'
    });
  }

  protected lancer(): void {
    this.dialogRef.close({
      animateurIds: this.animateurIds(),
      jours: this.jours(),
      standIds: this.standIds()
    });
  }
}
