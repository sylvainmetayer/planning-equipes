import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  input,
  OnInit,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { ParametresLegaux, ParametresQualite, PlacementRepas } from '../../core/models';
import { errorPrefix } from '../../core/error-message';
import { ModifiedFormsRegistry } from '../../core/formulaires-modifies';
import { NotificationService } from '../../core/notification.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { writeOrAskStale } from '../../shared/stale-write';
import { StatusMessage } from '../../shared/status-message';
import {
  hasChanges,
  SEUILS,
  SeuilDescriptor,
  SettingsRecord,
  shownValue,
  storedValue,
} from '../regles/regles';

/** The two sections edited here as a list of settings; the two others have a card of their own. */
export type SectionReglages = 'legal' | 'organisation';

interface Champ {
  readonly cle: string;
  readonly libelle: string;
  readonly descriptor: SeuilDescriptor;
}

/** The two legal settings no rule reads: the grid check of the timeslots warns with them. */
const GRILLE: Readonly<Record<string, SeuilDescriptor>> = {
  reposQuotidienMinimalMinutes: {
    source: 'legaux',
    kind: 'heures',
    fields: ['reposQuotidienMinimalMinutes'],
    min: 0,
    step: 0.5,
  },
  dureeVacationMaxMinutes: {
    source: 'legaux',
    kind: 'heures',
    fields: ['dureeVacationMaxMinutes'],
    min: 0.5,
    step: 0.5,
  },
};

/** Called lazily (never at module scope): the settings of a section, in reading order, in the words of the screen. */
function fieldsOf(section: SectionReglages): Champ[] {
  const libelles: Record<string, string> =
    section === 'legal'
      ? {
          dureeHebdomadaireMaxMinutes: $localize`:@@parametres.section.dureeHebdomadaireMax:Durée hebdomadaire maximale, majeurs`,
          dureeHebdomadaireMaxMineurMinutes: $localize`:@@parametres.section.dureeHebdomadaireMaxMineur:Durée hebdomadaire maximale, mineurs`,
          dureePauseMinutes: $localize`:@@parametres.section.dureePause:Durée de la pause légale`,
          coupureRepasMinutes: $localize`:@@parametres.section.coupureRepas:Durée de la coupure repas`,
          coupureRepasMidi: $localize`:@@parametres.section.coupureRepasMidi:Fenêtre du midi`,
          coupureRepasSoir: $localize`:@@parametres.section.coupureRepasSoir:Fenêtre du soir`,
          reposQuotidienMinimalMinutes: $localize`:@@constraints.legal.field.reposQuotidien:Repos quotidien minimal (heures)`,
          dureeVacationMaxMinutes: $localize`:@@constraints.legal.field.dureeVacationMax:Durée maximale d'une vacation (heures)`,
        }
      : {
          maxEmplacementsDistinctsParJour: $localize`:@@parametres.section.maxEmplacements:Emplacements distincts par jour`,
          typologiesDistinctesMax: $localize`:@@parametres.section.typologiesDistinctes:Typologies distinctes par animateur`,
          joursConsecutifsMax: $localize`:@@parametres.section.joursConsecutifs:Jours travaillés d'affilée`,
          vitesseMarcheKmH: $localize`:@@parametres.section.vitesseMarche:Vitesse de marche (km/h)`,
          facteurDetour: $localize`:@@parametres.section.facteurDetour:Facteur de détour`,
          toleranceTrajetMinutes: $localize`:@@parametres.section.toleranceTrajet:Tolérance de trajet`,
          toleranceArriveeGroupeeMinutes: $localize`:@@parametres.section.toleranceArrivee:Tolérance d'une arrivée groupée`,
          heureServiceTardif: $localize`:@@parametres.section.serviceTardif:Heure d'un service tardif`,
          heureServiceMatinal: $localize`:@@parametres.section.serviceMatinal:Heure d'un service matinal`,
          reposSouhaiteApresServiceTardifMinutes: $localize`:@@parametres.section.reposApresTardif:Repos souhaité après un service tardif`,
          placementRepas: $localize`:@@parametres.section.placementRepas:Placement préféré de la coupure repas`,
        };
  return Object.entries(libelles).map(([cle, libelle]) => ({
    cle,
    libelle,
    descriptor: SEUILS[cle] ?? GRILLE[cle],
  }));
}

/** Called lazily: the values of the meal-break placement, in the words of the screen. */
function placementOptions(): readonly { value: PlacementRepas; label: string }[] {
  return [
    {
      value: 'FIN_MIDI_DEBUT_SOIR',
      label: $localize`:@@parametres.placement.defaut:Fin du midi, début du soir (par défaut)`,
    },
    { value: 'DEBUT', label: $localize`:@@parametres.placement.debut:Début de chaque fenêtre` },
    { value: 'FIN', label: $localize`:@@parametres.placement.fin:Fin de chaque fenêtre` },
    { value: 'AUCUN', label: $localize`:@@parametres.placement.aucun:Aucune préférence` },
  ];
}

/** A typed value the server can store as is: a number in its bounds, a window end. A single hour may be emptied: no hour makes its rule inert. */
function acceptable(descriptor: SeuilDescriptor, field: string, draft: SettingsRecord): boolean {
  if (descriptor.kind === 'heure' || descriptor.kind === 'choix') {
    return true;
  }
  if (draft[field] === null || draft[field] === undefined || draft[field] === '') {
    return false;
  }
  if (descriptor.kind === 'plage') {
    return true;
  }
  // Bounds are in the unit of the screen (hours for a duration in hours).
  const shown = Number(shownValue(descriptor, field, null, draft));
  return (
    (descriptor.min === undefined || shown >= descriptor.min) &&
    (descriptor.max === undefined || shown <= descriptor.max)
  );
}

/**
 * « Paramètres › Légal » and « Paramètres › Organisation »: every setting of
 * the section on one form, one « Enregistrer ». The same values are edited in
 * context, on the row of the rule that reads them, in « Règles du planning »;
 * both write under the `modifieLe` they loaded, so neither overwrites what the
 * other stored without being told (« Modifiée entre-temps »). Nothing is written
 * as it is typed.
 */
@Component({
  selector: 'app-parametres-section',
  // FormsModule for `ngSubmit`: without NgForm, « Enregistrer » or Enter would submit natively and reload the page.
  imports: [FormsModule, MatButtonModule, MatCardModule, MatIconModule, RouterLink, StatusMessage],
  templateUrl: './parametres-section.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ParametresSection implements OnInit {
  readonly section = input.required<SectionReglages>();

  private readonly constraintsApi = inject(ConstraintsApi);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);

  protected readonly stored = signal<SettingsRecord | null>(null);
  protected readonly draft = signal<SettingsRecord>({});
  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  protected readonly champs = computed(() => fieldsOf(this.section()));
  readonly dirty = computed(() => hasChanges(this.stored(), this.draft()));

  /** Set by a save refused for a typed value: from then on, each field says what is wrong with it. */
  protected readonly attempted = signal(false);

  /** The typed fields the save would refuse: an emptied number would be stored as 0, an out-of-range one refused by the server. */
  protected readonly invalid = computed(() => {
    const draft = this.draft();
    return new Set(
      this.champs().flatMap((champ) =>
        champ.descriptor.fields.filter(
          (field) => field in draft && !acceptable(champ.descriptor, field, draft),
        ),
      ),
    );
  });

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  private readonly registry = inject(ModifiedFormsRegistry);
  private readonly destroyRef = inject(DestroyRef);

  ngOnInit(): void {
    // Declared once the input is known: the Solveur asks before « Calculer » while something is typed here.
    this.destroyRef.onDestroy(
      this.registry.register(
        `parametres-${this.section()}`,
        $localize`:@@parametres.section.saisie.label:les paramètres`,
        this.dirty,
      ),
    );
    void this.load();
  }

  private read(): Promise<SettingsRecord> {
    // Both records are flat bags of settings: the descriptors name the fields.
    return (
      this.section() === 'legal'
        ? this.constraintsApi.legalParameters()
        : this.constraintsApi.qualityParameters()
    ) as Promise<SettingsRecord>;
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      this.stored.set(await this.read());
    } catch (error) {
      this.error.set(errorPrefix(error));
    } finally {
      this.loading.set(false);
    }
  }

  protected value(champ: Champ, field: string): string | number | null {
    return shownValue(champ.descriptor, field, this.stored(), this.draft());
  }

  protected typed(champ: Champ, field: string, text: string): void {
    this.draft.update((current) => ({
      ...current,
      [field]: storedValue(champ.descriptor, text),
    }));
  }

  protected unit(champ: Champ): string {
    switch (champ.descriptor.kind) {
      case 'heures':
        return $localize`:@@regles.unite.heures:h`;
      case 'minutes':
        return $localize`:@@regles.unite.minutes:min`;
      default:
        return '';
    }
  }

  protected readonly placements = computed(placementOptions);

  protected shownInvalid(field: string): boolean {
    return this.attempted() && this.invalid().has(field);
  }

  protected champInvalid(champ: Champ): boolean {
    return champ.descriptor.fields.some((field) => this.shownInvalid(field));
  }

  /** What a field refused at save time expects, read out with it. */
  protected expected(champ: Champ): string {
    const { min, max } = champ.descriptor;
    if (champ.descriptor.kind === 'plage') {
      return $localize`:@@parametres.section.attendu.heure:Saisir une heure.`;
    }
    if (min !== undefined && max !== undefined) {
      return $localize`:@@parametres.section.attendu.entre:Saisir une valeur entre ${min}:min: et ${max}:max:.`;
    }
    if (min !== undefined) {
      return $localize`:@@parametres.section.attendu.min:Saisir une valeur d'au moins ${min}:min:.`;
    }
    return $localize`:@@parametres.section.attendu.valeur:Saisir une valeur.`;
  }

  /** The two ends of a window, told apart for a screen reader. */
  protected endLabel(champ: Champ, index: number): string {
    const libelle = champ.libelle;
    return index === 0
      ? $localize`:@@parametres.section.debut:${libelle}:libelle:, début`
      : $localize`:@@parametres.section.fin:${libelle}:libelle:, fin`;
  }

  protected cancel(): void {
    this.draft.set({});
    this.attempted.set(false);
    this.error.set('');
  }

  protected async save(): Promise<void> {
    if (!this.dirty() || this.saving()) {
      return;
    }
    const first = this.champs()
      .flatMap((champ) => champ.descriptor.fields)
      .find((field) => this.invalid().has(field));
    if (first !== undefined) {
      // Nothing is sent: the alert says why, the focus goes where to fix it.
      this.attempted.set(true);
      this.error.set(
        $localize`:@@parametres.section.invalide:Des valeurs sont à corriger : chacune est signalée sous son champ. Rien n'a été enregistré.`,
      );
      this.host.nativeElement
        .querySelector<HTMLInputElement>(`input[data-field="${first}"]`)
        ?.focus();
      return;
    }
    this.attempted.set(false);
    this.saving.set(true);
    this.error.set('');
    try {
      // Written under the stamp the section was loaded with: a section written since is a 409.
      // « Écraser » re-reads it and sends the typed fields over it, without a stamp.
      const written = await writeOrAskStale(
        { confirm: this.confirm, notifications: this.notifications },
        async (overwrite) => {
          const base = (overwrite ? null : this.stored()) ?? (await this.read());
          const merged = {
            ...base,
            ...this.draft(),
            modifieLe: overwrite ? null : (base['modifieLe'] ?? null),
          };
          return (
            this.section() === 'legal'
              ? await this.constraintsApi.saveLegalParameters(merged as unknown as ParametresLegaux)
              : await this.constraintsApi.saveQualityParameters(
                  merged as unknown as ParametresQualite,
                )
          ) as SettingsRecord;
        },
        () => this.load(),
      );
      if (written === null) {
        this.cancel();
        return;
      }
      this.stored.set(written.saved);
      this.draft.set({});
      this.notifications.notify({
        title: $localize`:@@parametres.section.saved:Paramètres enregistrés : le prochain calcul en tiendra compte.`,
        variant: 'success',
        timeout: 4000,
      });
    } catch (error) {
      this.error.set(errorPrefix(error));
    } finally {
      this.saving.set(false);
    }
  }
}
