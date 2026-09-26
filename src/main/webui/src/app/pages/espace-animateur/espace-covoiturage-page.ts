import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { EspaceAnimateurService } from '../../core/espace-animateur.service';
import { toDateKey } from '../../core/date-utils';
import { errorMessage } from '../../core/error-message';
import {
  CARPOOL_MAX,
  carpoolLocked,
  carpoolModified,
  initialTeammates,
  openingAhead,
  toCarpoolRequest,
  toggleTeammate,
} from './covoiturage-brouillon';
import { StatusMessage } from '../../shared/status-message';
import { EspaceContact } from './espace-contact';

/**
 * « Covoiturage »: the animateur names up to three teammates they come with —
 * « Je viens avec… » — and the organisation validates the grouped arrival or
 * sets it aside.
 *
 * <p>Apart from « Mes disponibilités » on purpose: its own form, its own send
 * and its own decision, so applying or refusing a declaration never touches
 * the car. It shares the declaration's window — open for input while the
 * collection is, read-only otherwise — and shows where the request stands:
 * pending, validated with whom, or set aside with the reason the organisation
 * gave.</p>
 */
@Component({
  selector: 'app-espace-covoiturage-page',
  imports: [
    EspaceContact,
    StatusMessage,
    DatePipe,
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
  ],
  templateUrl: './espace-covoiturage-page.html',
  styleUrl: '../../../styles/espace-disponibilites.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like its sibling tab.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EspaceCovoituragePage implements OnInit {
  protected readonly espace = inject(EspaceAnimateurService);

  protected readonly loading = signal(true);
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Set on a successful send, cleared as soon as the draft moves again. */
  protected readonly sent = signal(false);

  /** The teammates picked, by animateur id. */
  protected readonly draft = signal<string[]>([]);
  /** What the teammate search box holds. */
  protected readonly search = signal('');

  protected readonly carpoolMax = CARPOOL_MAX;
  protected readonly view = computed(() => this.espace.carpool());
  protected readonly locked = computed(() => carpoolLocked(this.view()));
  protected readonly modified = computed(() => carpoolModified(this.view(), this.draft()));
  protected readonly notOpenYet = computed(() => openingAhead(this.view(), toDateKey(new Date())));

  private readonly names = computed(
    () =>
      new Map(
        (this.view()?.colleagues ?? []).map((colleague) => [colleague.id, colleague.nomComplet]),
      ),
  );

  /** The teammates picked, named — a removed colleague falls back to their id. */
  protected readonly chosen = computed(() =>
    this.draft().map((id) => ({ id, name: this.names().get(id) ?? id })),
  );

  /** The car the organisation holds or held — validated, pending or set aside — named. */
  protected readonly current = computed(() =>
    (this.view()?.teammateIds ?? []).map((id) => this.names().get(id) ?? id).join(', '),
  );

  /** Colleagues matching the search, not already chosen — eight at most, a phone screen's worth. */
  protected readonly suggestions = computed(() => {
    const search = this.search().trim().toLocaleLowerCase();
    if (!search) {
      return [];
    }
    const chosen = new Set(this.draft());
    return (this.view()?.colleagues ?? [])
      .filter(
        (colleague) =>
          !chosen.has(colleague.id) && colleague.nomComplet.toLocaleLowerCase().includes(search),
      )
      .slice(0, 8);
  });

  /** Emptying a pending car and sending it withdraws the request: the button says so. */
  protected readonly withdrawing = computed(
    () => this.draft().length === 0 && this.view()?.status === 'EN_ATTENTE',
  );

  ngOnInit(): void {
    void this.reload();
  }

  protected async reload(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      await this.espace.loadCarpool();
      this.draft.set(initialTeammates(this.view()));
    } catch (error) {
      this.error.set(errorMessage(error));
    } finally {
      this.loading.set(false);
    }
  }

  /** One of the car, named for the read-only list. */
  protected chosenName(id: string): string {
    return this.names().get(id) ?? id;
  }

  protected toggle(animateurId: string): void {
    this.sent.set(false);
    this.search.set('');
    this.draft.update((draft) => toggleTeammate(draft, animateurId));
  }

  protected async send(): Promise<void> {
    this.sending.set(true);
    this.error.set(null);
    try {
      await this.espace.requestCarpool(toCarpoolRequest(this.draft()));
      this.draft.set(initialTeammates(this.view()));
      this.sent.set(true);
    } catch (error) {
      this.error.set(errorMessage(error));
    } finally {
      this.sending.set(false);
    }
  }
}
