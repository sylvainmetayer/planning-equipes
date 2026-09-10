import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  computed,
  inject,
  input,
  output,
} from '@angular/core';
import { ControlContainer, FormsModule, NgForm } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { focusApresSuppression } from '../../core/focus-apres-suppression';
import {
  estCasParticulier,
  formaterFenetres,
  horaireVide,
  parseFenetres,
} from '../../core/horaire-stand';
import { FenetreHoraire, JourSemaine } from '../../core/models';
import {
  HoraireDraft,
  ajouterA,
  basculerJour,
  datesFromText,
  fenetreVide,
  patchDansListe,
  retirerDe,
} from './stand-draft';
import { erreurRegle, libelleJourSemaine, messageConflitDeMode } from './stand-horaires';

/**
 * The editor of a stand's recurring rules, shared by the stand form and the
 * bulk edit — the two used to carry the same cards character for character.
 *
 * A rule opens folded on the only shape the reference event uses — open,
 * every day — with its windows typed as one line (`10:00-12:00@2, 14:00-`);
 * the mode and day selectors sit behind « Cas particulier », and the windows
 * can still be detailed one row each. Every edit comes back through
 * `horairesChange` as a new array: the parent owns the rules, this component
 * only says how they change.
 *
 * <p>Its `ngModel`s register with the parent's `<form>`, so the dialogs keep
 * one form and one NG01352 guard — hence the `ControlContainer` bridge, which
 * is what lets a control declared in a child view reach the ancestor form.</p>
 */
@Component({
  selector: 'app-horaire-regles-editor',
  imports: [
    FormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCheckboxModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule,
  ],
  viewProviders: [{ provide: ControlContainer, useExisting: NgForm }],
  templateUrl: './horaire-regles-editor.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HoraireReglesEditor {
  readonly horaires = input.required<readonly HoraireDraft[]>();
  /** The stand's declared capacity, when one stand is edited: a window may not ask for more. */
  readonly effectifMax = input<number | undefined>(undefined);
  readonly disabled = input(false);
  /** Prefix of the control names, so two editors in one form never share a name. */
  readonly prefixe = input('');
  readonly horairesChange = output<HoraireDraft[]>();

  private readonly hote = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  protected readonly joursSemaine: readonly JourSemaine[] = [
    'MONDAY',
    'TUESDAY',
    'WEDNESDAY',
    'THURSDAY',
    'FRIDAY',
    'SATURDAY',
    'SUNDAY',
  ];

  /** The problems rule by rule, so each card carries its own. */
  protected readonly erreursRegles = computed(() =>
    this.horaires().map((horaire) => erreurRegle(horaire, this.effectifMax())),
  );

  /** The one check that spans several rules: two rules of one scope disagreeing on the mode. */
  protected readonly conflitDeMode = computed(() => messageConflitDeMode(this.horaires()));

  protected nom(base: string): string {
    return this.prefixe() + base;
  }

  /** Whether the mode and day selectors of a rule are shown: asked for, or needed by what the rule says. */
  protected deplie(horaire: HoraireDraft): boolean {
    return horaire.deplie === true || estCasParticulier(horaire);
  }

  protected estCasParticulier(horaire: HoraireDraft): boolean {
    return estCasParticulier(horaire);
  }

  protected libelleJourSemaine(jour: JourSemaine): string {
    return libelleJourSemaine(jour);
  }

  /** The windows of a rule as one line — what was typed while it is being typed, the windows otherwise. */
  protected ligneFenetres(horaire: HoraireDraft): string {
    return typeof horaire.saisie === 'string' ? horaire.saisie : formaterFenetres(horaire.fenetres);
  }

  /** A new rule starts on an empty line, not on an empty row: the line's own error says what to type. */
  protected ajouterHoraire(): void {
    this.emettre(ajouterA(this.horaires(), { ...horaireVide(), fenetres: [], saisie: '' }));
  }

  protected patchHoraire(index: number, patch: Partial<HoraireDraft>): void {
    this.emettre(patchDansListe(this.horaires(), index, patch));
  }

  protected retirerHoraire(index: number): void {
    this.emettre(retirerDe(this.horaires(), index));
    this.focusApres(`[data-focus="${this.nom('ajouter-horaire')}"]`);
  }

  protected basculerCasParticulier(index: number): void {
    const horaire = this.horaires()[index];
    if (horaire) {
      this.patchHoraire(index, { deplie: !this.deplie(horaire) });
    }
  }

  /**
   * A keystroke on the compact line. The windows follow every line that
   * parses; a line that does not is kept as typed and reported on the card,
   * without touching the windows it will replace once it does.
   */
  protected patchLigneFenetres(index: number, saisie: string): void {
    const resultat = parseFenetres(saisie);
    this.patchHoraire(
      index,
      resultat.erreur === null ? { saisie, fenetres: resultat.fenetres } : { saisie },
    );
  }

  /** Shows the windows one row of fields each, or back as one line — the same windows either way. */
  protected basculerDetail(index: number): void {
    const horaire = this.horaires()[index];
    if (horaire) {
      // Leaving the line while it does not parse would carry an error the
      // rows cannot show: the line is dropped, the rows edit the windows as they are.
      this.patchHoraire(index, { detail: !horaire.detail, saisie: null });
    }
  }

  protected ajouterFenetre(indexHoraire: number): void {
    this.majFenetres(indexHoraire, (fenetres) => ajouterA(fenetres, fenetreVide()));
  }

  protected patchFenetre(
    indexHoraire: number,
    indexFenetre: number,
    patch: Partial<FenetreHoraire>,
  ): void {
    this.majFenetres(indexHoraire, (fenetres) => patchDansListe(fenetres, indexFenetre, patch));
  }

  protected retirerFenetre(indexHoraire: number, indexFenetre: number): void {
    this.majFenetres(indexHoraire, (fenetres) => retirerDe(fenetres, indexFenetre));
    this.focusApres(`[data-focus="${this.nom('ajouter-fenetre-' + indexHoraire)}"]`);
  }

  /** Seven checkboxes rather than a multi-select: the shape the question has. */
  protected basculerJourSemaine(indexHoraire: number, jour: JourSemaine, coche: boolean): void {
    const horaire = this.horaires()[indexHoraire];
    if (horaire) {
      this.patchHoraire(indexHoraire, {
        joursSemaine: basculerJour(horaire.joursSemaine, jour, coche),
      });
    }
  }

  /** Comma-separated ISO dates, for the `DATES` scope — a plain text field beats seven date pickers. */
  protected patchDates(indexHoraire: number, valeur: string): void {
    this.patchHoraire(indexHoraire, { dates: datesFromText(valeur) });
  }

  private majFenetres(
    indexHoraire: number,
    transformer: (fenetres: FenetreHoraire[]) => FenetreHoraire[],
  ): void {
    const horaire = this.horaires()[indexHoraire];
    if (horaire) {
      // Edited row by row: the line is derived again from the windows.
      this.patchHoraire(indexHoraire, { fenetres: transformer(horaire.fenetres), saisie: null });
    }
  }

  private emettre(horaires: HoraireDraft[]): void {
    this.horairesChange.emit(horaires);
  }

  /** Removing a row destroys the focused button: hand the focus to the section's "add" button. */
  private focusApres(selecteur: string): void {
    focusApresSuppression(this.hote.nativeElement, selecteur, this.injector);
  }
}
