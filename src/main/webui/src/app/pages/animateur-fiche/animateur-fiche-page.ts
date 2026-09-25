import { DatePipe, DecimalPipe, PercentPipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ViewEncapsulation,
  computed,
  inject,
  resource,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { AnimateursApi } from '../../core/api/animateurs-api';
import { ApiError } from '../../core/api.service';
import { statutDemandeLabel } from '../../core/demande-echange-labels';
import { errorPrefix } from '../../core/error-message';
import { SeveriteFragilite } from '../../core/models';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { errorText, retainedValue } from '../../core/resource-state';
import { SolverJobService } from '../../core/solver-job.service';
import { StatusMessage } from '../../shared/status-message';
import { AnimateurFormData, AnimateurFormDialog } from '../animateurs/animateur-form-dialog';
import { formatColonne, indicateursFiche } from '../equite/equite';
import {
  adjustmentScope,
  adjustmentTypeLabel,
  availabilityStrip,
  competenceRows,
  confirmationLabel,
  daysOffOutsideEvent,
  niveauLabel,
  regimeChanges,
  regimeLabel,
  shortTime,
  upcomingCount,
} from './animateur-fiche';

/**
 * « Fiche animateur » (`/animateurs/:id`): everything the application knows
 * about one person on one page — identity and legal regime, availability,
 * appreciations beside wishes, their line of Équité, their fragile seats,
 * their seats of the persisted plan, and the follow-up (publication,
 * acknowledgement, swap requests, adjustments, locks).
 *
 * A read-out: one call, `GET /api/animateurs/{id}/fiche`, assembled
 * server-side from the services the specialised screens read, so the figures
 * here are theirs. The page writes nothing; « Modifier » opens the same form
 * as the Animateurs page, with its concurrent-edit guard, and every section
 * links to the screen where its subject is acted on.
 */
@Component({
  selector: 'app-animateur-fiche-page',
  imports: [
    DatePipe,
    DecimalPipe,
    PercentPipe,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    RouterLink,
    StatusMessage,
  ],
  templateUrl: './animateur-fiche-page.html',
  styleUrls: ['../../../styles/animateur-form.css', './animateur-fiche-page.css'],
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AnimateurFichePage {
  private readonly animateursApi = inject(AnimateursApi);
  private readonly route = inject(ActivatedRoute);
  private readonly dialog = inject(MatDialog);
  private readonly store = inject(ReferenceDataStore);
  private readonly jobs = inject(SolverJobService);

  /** The id in the address; a change of person reloads the fiche. */
  protected readonly animateurId = toSignal(
    this.route.paramMap.pipe(map((params) => params.get('id') ?? '')),
    { initialValue: this.route.snapshot.paramMap.get('id') ?? '' },
  );

  private readonly profileData = resource({
    params: () => ({ id: this.animateurId() }),
    loader: ({ params }) => this.animateursApi.profile(params.id),
  });
  protected readonly profile = retainedValue(this.profileData, this.animateurId);
  protected readonly loading = this.profileData.isLoading;
  /** An unknown id gets a sentence of its own, not the generic failure. */
  protected readonly notFound = computed(() => {
    const error = this.profileData.error();
    return error instanceof ApiError && error.status === 404;
  });
  protected readonly error = errorText(this.profileData, (error) =>
    error instanceof ApiError && error.status === 404 ? '' : errorPrefix(error),
  );

  protected readonly editingLocked = this.jobs.editingLocked;

  protected readonly fullName = computed(() => {
    const animateur = this.profile()?.animateur;
    if (!animateur) {
      return '';
    }
    return `${animateur.prenom ?? ''} ${animateur.nom ?? ''}`.trim() || animateur.id;
  });
  protected readonly regimeChanges = computed(() => {
    const profile = this.profile();
    return profile ? regimeChanges(profile) : false;
  });
  protected readonly strip = computed(() => {
    const profile = this.profile();
    return profile ? availabilityStrip(profile) : [];
  });
  protected readonly offOutsideEvent = computed(() => {
    const profile = this.profile();
    return profile ? daysOffOutsideEvent(profile) : [];
  });
  protected readonly forcedOffAdjustments = computed(
    () =>
      this.profile()?.ajustements.filter(
        (ajustement) => ajustement.type === 'INDISPONIBILITE_FORCEE',
      ) ?? [],
  );
  protected readonly competences = computed(() => {
    const profile = this.profile();
    return profile ? competenceRows(profile.animateur, this.store.typologies()) : [];
  });
  protected readonly equityLine = computed(() => this.profile()?.equite.lignes[0] ?? null);
  protected readonly indicators = computed(() => {
    const profile = this.profile();
    return profile ? indicateursFiche(profile.equite, this.equityLine()) : [];
  });
  protected readonly upcoming = computed(() => {
    const profile = this.profile();
    return profile ? upcomingCount(profile) : 0;
  });

  protected readonly regimeLabel = regimeLabel;
  protected readonly niveauLabel = niveauLabel;
  protected readonly formatColonne = formatColonne;
  protected readonly adjustmentTypeLabel = adjustmentTypeLabel;
  protected readonly adjustmentScope = adjustmentScope;
  protected readonly confirmationLabel = confirmationLabel;
  protected readonly statutDemandeLabel = statutDemandeLabel;
  protected readonly shortTime = shortTime;

  constructor() {
    // The game categories name the appreciations: read once if no page has yet.
    if (this.store.typologies().length === 0) {
      void this.store.reload(['typologies']).catch(() => undefined);
    }
  }

  protected reload(): void {
    this.profileData.reload();
  }

  /** The Animateurs page's own form: the fiche writes nothing itself. */
  protected edit(): void {
    const animateur = this.profile()?.animateur;
    if (!animateur) {
      return;
    }
    this.dialog
      .open<AnimateurFormDialog, AnimateurFormData, boolean>(AnimateurFormDialog, {
        data: { animateur },
        width: '44rem',
        maxWidth: '95vw',
        autoFocus: 'first-tabbable',
      })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.reload();
        }
      });
  }

  protected gapClass(gap: number | null): string {
    if (gap === null || Math.abs(gap) < 0.005) {
      return '';
    }
    return gap > 0 ? 'fiche-ecart-positif' : 'fiche-ecart-negatif';
  }

  protected severityClass(severite: SeveriteFragilite): string {
    return `fiche-severite fiche-severite-${severite.toLowerCase()}`;
  }

  protected severityLabel(severite: SeveriteFragilite): string {
    switch (severite) {
      case 'CRITIQUE':
        return $localize`:@@fragilite.severite.critique:Critique`;
      case 'ELEVEE':
        return $localize`:@@fragilite.severite.elevee:Élevée`;
      case 'MODEREE':
        return $localize`:@@fragilite.severite.moderee:Modérée`;
    }
  }
}
