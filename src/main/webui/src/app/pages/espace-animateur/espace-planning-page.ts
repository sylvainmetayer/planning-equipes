import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { EspaceAnimateurService } from '../../core/espace-animateur.service';
import { errorMessage } from '../../core/error-message';
import { PauseAnimateurView, PosteAnimateurView } from '../../core/models';

interface JourPlanning {
  /** ISO date, `''` for postes without one. */
  date: string;
  postes: PosteAnimateurView[];
  /** True for an event day without any seat: the card says « Repos » instead of listing shifts. */
  repos: boolean;
  /** The legal breaks this day owes — « 20 min at the latest at 19:00 » — in deadline order. */
  pauses: PauseAnimateurView[];
}

/**
 * The animateur's own planning (issue #165): their seats from the last
 * persisted solve, one card per day, with the teammates they will actually
 * work alongside — the same content as their PDF, always up to date.
 */
@Component({
  selector: 'app-espace-planning-page',
  imports: [DatePipe, MatButtonModule, MatCardModule, MatIconModule],
  templateUrl: './espace-planning-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class EspacePlanningPage {
  protected readonly espace = inject(EspaceAnimateurService);

  /* --------- « J'ai lu et je serai là » (issue #293) ---------- */

  protected readonly confirmationEnCours = signal(false);
  /** Message of a failed confirmation, `null` while everything is fine. */
  protected readonly erreurConfirmation = signal<string | null>(null);

  /**
   * The button only exists once something has been published AND this person
   * actually holds a seat in it.
   *
   * `publieLe` alone is not enough: it is the edition's publication instant,
   * not « this person has assignments ». Someone with no seat would be shown
   * « J'ai lu et je serai là » under an empty planning, and their answer —
   * accepted by the server before this check existed — showed as « — » on the
   * admin column. Being asked nothing is the honest state here; the server
   * refuses it too, this only avoids offering the gesture.
   */
  protected readonly confirmationDemandee = computed(() => {
    const view = this.espace.vue();
    return !!view?.publieLe && view.postes.length > 0 && view.statutConfirmation !== 'CONFIRME';
  });

  protected async confirmer(): Promise<void> {
    this.confirmationEnCours.set(true);
    this.erreurConfirmation.set(null);
    try {
      await this.espace.confirmerPlanning();
    } catch (error) {
      this.erreurConfirmation.set(errorMessage(error));
    } finally {
      this.confirmationEnCours.set(false);
    }
  }

  /** Direct download links — the token in the URL is the whole credential. */
  protected readonly lienPdf = computed(() =>
    this.espace.jeton() ? `/api/espace-animateur/${this.espace.jeton()}/planning.pdf` : null
  );
  protected readonly lienIcs = computed(() =>
    this.espace.jeton() ? `/api/espace-animateur/${this.espace.jeton()}/planning.ics` : null
  );

  /* ---------- Permanent calendar subscription (issue #324) ---------- */

  /**
   * The subscription address, as an absolute URL. Absolute on purpose: it is
   * meant to be pasted into a calendar application, which has no page to
   * resolve a relative path against.
   *
   * A different token from the one in the address bar, and a path of its own:
   * this one opens the calendar and nothing else, which is what makes it
   * tolerable to hand out to a third-party application.
   */
  protected readonly urlAbonnement = computed(() => {
    const token = this.espace.vue()?.abonnementToken;
    return token ? `${window.location.origin}/api/abonnements/${token}/planning.ics` : null;
  });

  /**
   * The same address in the scheme calendar clients register for: clicking it
   * offers to subscribe instead of downloading a file once. The copy button
   * next to it exists because several clients (Google Calendar above all) ask
   * for the address to be pasted rather than clicked.
   */
  protected readonly lienWebcal = computed(() => {
    const url = this.urlAbonnement();
    return url ? url.replace(/^https?:/, 'webcal:') : null;
  });

  protected readonly abonnementCopie = signal(false);
  /**
   * The subscription address, its copy button and its replacement are folded
   * away by default. They are set once and never read again, and the band they
   * live in now sits above the planning: unfolded, they would push the first
   * day below the fold on a phone — the very defect moving the block up was
   * meant to fix.
   */
  protected readonly abonnementDeplie = signal(false);
  protected readonly rotationEnCours = signal(false);
  /** True once the confirm step is showing: rotating breaks the subscriptions already registered. */
  protected readonly rotationADemander = signal(false);
  /** Message of a failed rotation or copy, `null` while everything is fine. */
  protected readonly erreurAbonnement = signal<string | null>(null);

  /**
   * Folding the panel back also drops a pending « replace this address »
   * confirmation: reopening the panel must not land straight on a destructive
   * button the animateur no longer remembers asking for.
   */
  protected basculerDetailsAbonnement(): void {
    const deplie = !this.abonnementDeplie();
    this.abonnementDeplie.set(deplie);
    if (!deplie) {
      this.rotationADemander.set(false);
    }
  }

  protected async copierAbonnement(): Promise<void> {
    const url = this.urlAbonnement();
    if (!url) {
      return;
    }
    // Both outcomes of the previous attempt go, not just the error: a copy
    // that fails after one that worked would otherwise leave « Adresse
    // copiée » on screen next to « Copie impossible », and the fallback the
    // error asks for — select the address by hand — is exactly what the
    // stale success tells the animateur not to bother with.
    this.erreurAbonnement.set(null);
    this.abonnementCopie.set(false);
    try {
      await navigator.clipboard.writeText(url);
      this.abonnementCopie.set(true);
    } catch {
      // No clipboard (insecure context, refusal): say so rather than
      // pretending it worked — the address is displayed right above.
      this.erreurAbonnement.set(
        $localize`:@@espace.planning.abonnementCopieEchec:Copie impossible : sélectionnez l'adresse ci-dessus.`
      );
    }
  }

  protected async regenererAbonnement(): Promise<void> {
    this.rotationEnCours.set(true);
    this.erreurAbonnement.set(null);
    try {
      await this.espace.regenererAbonnement();
      this.rotationADemander.set(false);
      this.abonnementCopie.set(false);
    } catch (error) {
      this.erreurAbonnement.set(errorMessage(error));
    } finally {
      this.rotationEnCours.set(false);
    }
  }

  /**
   * PDF and one-shot ICS only mean something once there is a planning to take a
   * photograph of. The subscription, on the contrary, is offered right away:
   * subscribing ahead of the publication is the good gesture — the feed fills
   * itself.
   */
  protected readonly telechargementsOfferts = computed(
    () => this.jours().length > 0 && !!this.lienPdf()
  );

  protected readonly jours = computed<JourPlanning[]>(() => {
    const parJour = new Map<string, PosteAnimateurView[]>();
    for (const poste of this.espace.vue()?.postes ?? []) {
      const date = poste.date ?? '';
      const existants = parJour.get(date);
      if (existants) {
        existants.push(poste);
      } else {
        parJour.set(date, [poste]);
      }
    }
    const pausesParJour = new Map<string, PauseAnimateurView[]>();
    for (const pause of this.espace.vue()?.pauses ?? []) {
      const existantes = pausesParJour.get(pause.date);
      if (existantes) {
        existantes.push(pause);
      } else {
        pausesParJour.set(pause.date, [pause]);
      }
    }
    const jours: JourPlanning[] = Array.from(parJour.entries()).map(([date, postes]) => ({
      date,
      postes,
      repos: false,
      pauses: (pausesParJour.get(date) ?? []).sort((a, b) => a.debut.localeCompare(b.debut))
    }));
    // Rest days take their chronological place among the worked ones: a day
    // silently missing reads as an oversight, an explicit « Repos » card as a
    // decision. The server sends none for an animateur without any seat.
    for (const date of this.espace.vue()?.joursRepos ?? []) {
      jours.push({ date, postes: [], repos: true, pauses: [] });
    }
    return jours.sort((a, b) => a.date.localeCompare(b.date));
  });
}
