import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  input,
  OnInit,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { ParametresLegaux, ParametresQualite } from '../../core/models';
import { errorPrefix } from '../../core/error-message';
import { ModifiedFormsRegistry } from '../../core/formulaires-modifies';
import { NotificationService } from '../../core/notification.service';
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
        };
  return Object.entries(libelles).map(([cle, libelle]) => ({
    cle,
    libelle,
    descriptor: SEUILS[cle] ?? GRILLE[cle],
  }));
}

/**
 * « Paramètres › Légal » and « Paramètres › Organisation »: every setting of
 * the section on one form, one « Enregistrer ». The same values are edited in
 * context, on the row of the rule that reads them, in « Règles du planning »;
 * both re-read the record just before writing it and send the typed fields
 * over it, so neither resets what the other stored. Nothing is written as it
 * is typed.
 */
@Component({
  selector: 'app-parametres-section',
  imports: [MatButtonModule, MatCardModule, MatIconModule, RouterLink, StatusMessage],
  templateUrl: './parametres-section.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ParametresSection implements OnInit {
  readonly section = input.required<SectionReglages>();

  private readonly constraintsApi = inject(ConstraintsApi);
  private readonly notifications = inject(NotificationService);

  protected readonly stored = signal<SettingsRecord | null>(null);
  protected readonly draft = signal<SettingsRecord>({});
  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  protected readonly champs = computed(() => fieldsOf(this.section()));
  readonly dirty = computed(() => hasChanges(this.stored(), this.draft()));

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

  protected cancel(): void {
    this.draft.set({});
    this.error.set('');
  }

  protected async save(): Promise<void> {
    if (!this.dirty() || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.error.set('');
    try {
      const current = await this.read();
      const merged = { ...current, ...this.draft() };
      this.stored.set(
        (this.section() === 'legal'
          ? await this.constraintsApi.saveLegalParameters(merged as unknown as ParametresLegaux)
          : await this.constraintsApi.saveQualityParameters(
              merged as unknown as ParametresQualite,
            )) as SettingsRecord,
      );
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
