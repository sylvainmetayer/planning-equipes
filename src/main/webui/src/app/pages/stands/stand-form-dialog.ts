import { ChangeDetectionStrategy, Component, ElementRef, Injector, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { focusApresSuppression } from '../../core/focus-apres-suppression';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { JourResolu, resoudreHoraires } from '../../core/horaire-stand';
import { datesEvenement, decrireJour, libelleJour, premiereErreurHoraire } from './stand-horaires';
import { HoraireReglesEditor } from './horaire-regles-editor';
import { IndisponibiliteStand, OuvertureStand, Stand } from '../../core/models';
import {
  StandDraft,
  ajouterA,
  brouillonInvalide,
  conflitOuvertureFermeture,
  effectifInvalide,
  effectifOuvertureInvalide,
  indisponibiliteInvalide,
  ouvertureInvalide,
  ouvertureVide,
  patchDansListe,
  plageVide,
  retirerDe,
  toDraft,
  typologiesVides,
  versStand
} from './stand-draft';

export interface StandFormData {
  stand: Stand | null;
}

/**
 * Add/edit dialog for a stand: identity, staffing bounds, adults-only flag,
 * typologies, and its opening schedule.
 *
 * The schedule is edited on two levels, matching how the backend resolves it:
 * recurring **horaires** state the pattern ("open 10:00-12:00 then 14:00 to
 * closing, every day" — one rule instead of twenty-four dated windows), and
 * dated **exceptions** override them for the single day they name. The preview
 * strip below the editor resolves both against the active group's days, so the
 * effect of a rule is visible without saving and re-reading.
 *
 * The recurring rules are edited by {@link HoraireReglesEditor}, shared with
 * the bulk edit: a rule opens folded, its windows typed as one line.
 */
@Component({
  selector: 'app-stand-form-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCheckboxModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule,
    HoraireReglesEditor
  ],
  templateUrl: './stand-form-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class StandFormDialog {
  protected readonly store = inject(ReferenceDataStore);
  protected readonly jobs = inject(SolverJobService);
  /** Editing is disabled while a solve/analysis runs, to avoid corrupting the data it reads. */
  protected readonly editingLocked = this.jobs.editingLocked;

  protected readonly dialogRef = inject<MatDialogRef<StandFormDialog, boolean>>(MatDialogRef);
  private readonly data = inject<StandFormData>(MAT_DIALOG_DATA);
  private readonly crud = inject(ReferenceCrudService);
  private readonly hote = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  /** Removing a row destroys the focused button: hand the focus to the section's "add" button. */
  private focusApres(selecteur: string): void {
    focusApresSuppression(this.hote.nativeElement, selecteur, this.injector);
  }

  protected readonly editingId = signal<string | null>(this.data.stand?.id ?? null);
  protected readonly draft = signal<StandDraft>(toDraft(this.data.stand));

  protected readonly effectifInvalid = computed(() => effectifInvalide(this.draft()));
  protected readonly typologiesInvalides = computed(() => typologiesVides(this.draft()));
  protected readonly formTitle = computed(() => {
    const id = this.editingId();
    return id ? $localize`:@@stands.form.editTitle:Modifier le stand ${id}:id:` : $localize`:@@stands.form.newTitle:Nouveau stand`;
  });
  protected readonly submitLabel = computed(() =>
    this.editingId() ? $localize`:@@stands.submit.edit:Modifier le stand` : $localize`:@@stands.submit.create:Créer le stand`
  );

  protected readonly indisponibiliteInvalide = computed(() => indisponibiliteInvalide(this.draft()));

  protected readonly ouvertureInvalide = computed(() => ouvertureInvalide(this.draft()));

  protected readonly conflitOuvertureFermeture = computed(() => conflitOuvertureFermeture(this.draft()));
  protected readonly effectifOuvertureInvalide = computed(() => effectifOuvertureInvalide(this.draft()));

  /** First problem among the recurring rules, or `null` — mirrors the backend's own check. */
  protected readonly erreurHoraires = computed(() => premiereErreurHoraire(this.draft().horaires, Number(this.draft().effectifMax)));

  /** The stand's declared capacity, as the rule editor checks the windows against. */
  protected readonly effectifMaxDeclare = computed(() => Number(this.draft().effectifMax));

  /** Days the preview covers: the edition's créneaux — what the solver builds from. */
  protected readonly datesEvenement = computed(() => datesEvenement(this.store.creneaux()));

  /** The schedule as the solver will read it, day by day — the point of the whole editor. */
  protected readonly apercu = computed<JourResolu[]>(() => {
    const draft = this.draft();
    return resoudreHoraires(
      {
        indisponibilites: draft.indisponibilites,
        ouvertures: draft.ouvertures,
        horaires: draft.horaires
      } as Stand,
      this.datesEvenement()
    );
  });

  protected decrireJour(jour: JourResolu): string {
    return decrireJour(jour);
  }

  /** Day label of the preview strip: `08/07`, short enough for a dozen cells in a row. */
  protected libelleJour(date: string): string {
    return libelleJour(date);
  }

  protected patch(patch: Partial<StandDraft>): void {
    this.draft.update((draft) => ({ ...draft, ...patch }));
  }

  protected ajouterIndisponibilite(): void {
    this.patch({ indisponibilites: ajouterA(this.draft().indisponibilites, plageVide()) });
  }

  protected patchIndisponibilite(index: number, patch: Partial<IndisponibiliteStand>): void {
    this.patch({ indisponibilites: patchDansListe(this.draft().indisponibilites, index, patch) });
  }

  protected retirerIndisponibilite(index: number): void {
    this.patch({ indisponibilites: retirerDe(this.draft().indisponibilites, index) });
    this.focusApres('[data-focus="ajouter-indisponibilite"]');
  }

  protected ajouterOuverture(): void {
    this.patch({ ouvertures: ajouterA(this.draft().ouvertures, ouvertureVide()) });
  }

  protected patchOuverture(index: number, patch: Partial<OuvertureStand>): void {
    this.patch({ ouvertures: patchDansListe(this.draft().ouvertures, index, patch) });
  }

  protected retirerOuverture(index: number): void {
    this.patch({ ouvertures: retirerDe(this.draft().ouvertures, index) });
    this.focusApres('[data-focus="ajouter-ouverture"]');
  }

  protected readonly formulaireInvalide = computed(
    () => brouillonInvalide(this.draft()) || this.effectifOuvertureInvalide() || this.erreurHoraires() !== null
  );

  protected async save(): Promise<void> {
    if (this.formulaireInvalide()) {
      return;
    }
    const stand = versStand(this.draft(), this.store.emplacements());
    if (await this.crud.save('stands', stand, this.editingId(), $localize`:@@stands.entityLabel:Stand`)) {
      this.dialogRef.close(true);
    }
  }
}
