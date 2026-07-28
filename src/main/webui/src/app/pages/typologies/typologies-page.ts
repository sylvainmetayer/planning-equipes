import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { TypologieItem } from '../../core/models';

/** Typologies CRUD: the game families a stand can propose and an animator master. */
@Component({
  selector: 'app-typologies-page',
  imports: [
    FormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatTableModule,
    MatTooltipModule
  ],
  templateUrl: './typologies-page.html'
})
export class TypologiesPage {
  protected readonly columns = ['id', 'label', 'actions'];
  protected readonly store = inject(ReferenceDataStore);
  protected readonly draft = signal<TypologieItem>(emptyDraft());
  protected readonly editingId = signal<string | null>(null);
  protected readonly formTitle = computed(() =>
    this.editingId() ? `Edit typology ${this.editingId()}` : 'New typology'
  );

  private readonly crud = inject(ReferenceCrudService);

  constructor() {
    void this.crud.reload();
  }

  protected patch(patch: Partial<TypologieItem>): void {
    this.draft.update((draft) => ({ ...draft, ...patch }));
  }

  protected async save(): Promise<void> {
    const draft = this.draft();
    const typologie: TypologieItem = { id: draft.id.trim(), label: draft.label.trim() };
    if (await this.crud.save('typologies', typologie, this.editingId(), 'Typology')) {
      this.cancel();
    }
  }

  protected edit(typologie: TypologieItem): void {
    this.draft.set({ id: typologie.id, label: typologie.label ?? '' });
    this.editingId.set(typologie.id);
  }

  protected cancel(): void {
    this.draft.set(emptyDraft());
    this.editingId.set(null);
  }

  protected async remove(typologie: TypologieItem): Promise<void> {
    if (await this.crud.remove('typologies', typologie.id, 'Typology') && this.editingId() === typologie.id) {
      this.cancel();
    }
  }
}

function emptyDraft(): TypologieItem {
  return { id: '', label: '' };
}
