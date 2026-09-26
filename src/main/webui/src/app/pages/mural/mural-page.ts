import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  OnDestroy,
  OnInit,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { AffichageMuralApi } from '../../core/api/affichage-mural-api';
import { AffichageMuralView, MuralAlert, MuralShift } from '../../core/models';
import {
  advance,
  DEAD_LINK_AFTER,
  DEAD_LINK_RETRY_MS,
  groupByEmplacement,
  heureOf,
  ScreenMeasure,
  minutesSince,
  momentOf,
  nextPage,
  pageLabel,
  paginate,
  RAFRAICHISSEMENT_MS,
  READ_TIMEOUT_MS,
  ROTATION_MS,
  secondsSince,
  shiftKey,
  StandMoment,
  standsFermes,
  tableauImpression,
  tilesPerPage,
} from './mural';

/**
 * The wall display of the control room (`/mural/:jeton`): outside both shells,
 * full screen, no session — the token in the URL opens this read and nothing
 * else (ADR 0053).
 *
 * It reads the server every minute and **keeps the last state** when a read
 * fails or hangs past {@link READ_TIMEOUT_MS}, saying since when it is offline
 * rather than going blank; an answer older than the one on screen is dropped.
 * Only {@link DEAD_LINK_AFTER} 404s in a row conclude that the link is dead,
 * and even then the screen keeps asking, every five minutes. Between two
 * reads the server's moment is advanced by the time elapsed on this machine, so
 * a shift ends on screen at its minute; the television's own clock is never
 * read as a time of day. When the tiles do not fit, they turn by pages every
 * fifteen seconds — as many as the tiles <b>measured</b> let fit, the stands
 * closed at the moment kept out of the rotation on one line. `?impression=1`
 * lays the whole day out for print instead, as a table of stands × shifts.
 *
 * <p>The same page serves « Imprimer cette journée » of the Planning page
 * (`/impression/:date`, route data `apercu`): the whole day for print, read
 * through the admin session from `GET /api/affichage-mural/apercu` — no token,
 * no link created, and `/api/mural/` still names one route only.</p>
 */
@Component({
  selector: 'app-mural-page',
  imports: [DatePipe],
  templateUrl: './mural-page.html',
  styleUrl: './mural-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MuralPage implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(AffichageMuralApi);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  private jeton = '';
  /** `/impression/:date`: the admin's print of a day, read under the session. */
  private apercu = false;
  private date = '';
  private timers: ReturnType<typeof setInterval>[] = [];

  protected readonly impression = signal(false);
  protected readonly view = signal<AffichageMuralView | null>(null);
  /** The link answered 404 {@link DEAD_LINK_AFTER} times in a row: unknown or revoked. */
  protected readonly lienMort = signal(false);
  /** 404s in a row: one alone may be a restart or a proxy hiccup. */
  private notFoundInARow = 0;
  /** Sequence number of the last read started, and of the last one applied. */
  private lastStarted = 0;
  private lastApplied = 0;
  /** `performance.now()` of the last read started, to slow down on a dead link. */
  private lastStartedAt = 0;
  /** `performance.now()` of the last good read. */
  private readonly lueA = signal<number | null>(null);
  /** `performance.now()` of the first failed read since the last good one. */
  private readonly echecDepuis = signal<number | null>(null);
  /** Ticks every second, so the clock and the « il y a » stay current. */
  private readonly tick = signal(performance.now());
  private readonly screen = signal({ largeur: window.innerWidth, hauteur: window.innerHeight });
  private readonly page = signal(0);
  /** The layout as drawn, measured after each read and resize; `null` before the first. */
  private readonly mesure = signal<ScreenMeasure | null>(null);

  /** The server's moment, advanced by what elapsed here since the read. */
  protected readonly now = computed(() => {
    const view = this.view();
    const lueA = this.lueA();
    return view && lueA !== null ? advance(view.now, this.tick() - lueA) : null;
  });

  protected readonly heure = computed(() => {
    const now = this.now();
    return now ? heureOf(now) : '';
  });

  private readonly moments = computed<StandMoment[]>(() => {
    const view = this.view();
    const now = this.now();
    return view && now ? view.stands.map((stand) => momentOf(stand, now)) : [];
  });

  /** The stands with a shift under way: the only ones the pages turn over. */
  private readonly ouverts = computed(() =>
    this.moments().filter((moment) => moment.current.length > 0),
  );

  /** The others, on one line: « fermés jusqu'à 18:00 : … ». */
  protected readonly fermes = computed(() => standsFermes(this.moments()));

  private readonly pages = computed(() => {
    const { largeur, hauteur } = this.screen();
    return paginate(this.ouverts(), tilesPerPage(this.mesure(), largeur, hauteur));
  });

  protected readonly groupes = computed(() => {
    const pages = this.pages();
    return groupByEmplacement(pages[this.page() % pages.length]);
  });

  /** Every stand, every shift of the day: the print version, as a table. */
  protected readonly tableau = computed(() => tableauImpression(this.view()?.stands ?? []));

  protected readonly pagination = computed(() =>
    pageLabel(this.page() % this.pages().length, this.pages().length),
  );

  protected readonly misAJourIlYa = computed(() => {
    const lueA = this.lueA();
    return lueA === null ? null : secondsSince(lueA, this.tick());
  });

  protected readonly horsLigneDepuis = computed(() => {
    const depuis = this.echecDepuis();
    return depuis === null ? null : minutesSince(depuis, this.tick());
  });

  ngOnInit(): void {
    this.jeton = this.route.snapshot.paramMap.get('jeton') ?? '';
    this.apercu = this.route.snapshot.data?.['apercu'] === true;
    this.date = this.route.snapshot.paramMap.get('date') ?? '';
    this.impression.set(this.apercu || this.route.snapshot.queryParamMap.get('impression') === '1');
    void this.read();
    if (this.impression()) {
      return;
    }
    this.timers = [
      setInterval(() => void this.poll(), RAFRAICHISSEMENT_MS),
      setInterval(
        () => this.page.update((index) => nextPage(index, this.pages().length)),
        ROTATION_MS,
      ),
      setInterval(() => this.tick.set(performance.now()), 1000),
    ];
    window.addEventListener('resize', this.onResize);
  }

  ngOnDestroy(): void {
    this.timers.forEach((timer) => clearInterval(timer));
    window.removeEventListener('resize', this.onResize);
  }

  protected timeOf(iso: string): string {
    return heureOf(iso);
  }

  /** `HH:mm` of a server `LocalTime` (`13:00:00`); « minuit » for a band with no end. */
  protected hhmm(time: string | null): string {
    return time === null ? $localize`:@@mural.minuit:minuit` : time.slice(0, 5);
  }

  protected readonly shiftKey = shiftKey;

  /** One entry per seat opened since the publication: « Place libre », in red. */
  protected placesNouvelles(shift: MuralShift): number[] {
    return Array.from({ length: shift.newEmptySeats }, (_, index) => index);
  }

  /** One entry per hole the published plan already had: faded, known to all. */
  protected placesConnues(shift: MuralShift): number[] {
    return Array.from({ length: shift.emptySeats - shift.newEmptySeats }, (_, index) => index);
  }

  protected alertText(alerte: MuralAlert): string {
    const heures = `${heureOf(alerte.start)}–${heureOf(alerte.end)}`;
    switch (alerte.type) {
      case 'BREAK_WITHOUT_RELAY':
        return alerte.nom
          ? $localize`:@@mural.alerte.pause:${alerte.standNom}:stand: : pause sans relais ${heures}:heures: (${alerte.nom}:nom:)`
          : $localize`:@@mural.alerte.pauseAnonyme:${alerte.standNom}:stand: : pause sans relais ${heures}:heures:`;
      case 'STARTING_SOON':
        return $localize`:@@mural.alerte.bientot:${alerte.standNom}:stand: : commence à ${heureOf(alerte.start)}:heure: avec ${alerte.count}:nombre: × place libre`;
      case 'NEW_EMPTY_SEATS':
        return $localize`:@@mural.alerte.nouvelles:${alerte.standNom}:stand: : ${alerte.count}:nombre: × place libre depuis ce matin ${heures}:heures:`;
    }
  }

  /** « Peut différer », said only with a number: the people the working plan moved since the publication. */
  protected peutDiffererText(nombre: number): string {
    return nombre === 1
      ? $localize`:@@mural.peutDiffere.une:Peut différer du planning envoyé : 1 personne concernée`
      : $localize`:@@mural.peutDiffere:Peut différer du planning envoyé : ${nombre}:nombre: personnes concernées`;
  }

  /** The stands closed at the moment, by reopening hour. */
  protected fermesText(fermes: { reouverture: string | null; noms: string[] }): string {
    const noms = fermes.noms.join(', ');
    return fermes.reouverture
      ? $localize`:@@mural.fermesJusqua:Fermés jusqu'à ${fermes.reouverture}:heure: : ${noms}:noms:`
      : $localize`:@@mural.fermesJournee:Fermés pour la journée : ${noms}:noms:`;
  }

  private readonly onResize = (): void => {
    this.screen.set({ largeur: window.innerWidth, hauteur: window.innerHeight });
    this.measureSoon();
  };

  /**
   * Once the browser has drawn the tiles: measure them. A timer rather than an
   * animation frame — a television whose tab the browser deems hidden may
   * never paint one, and the pages must still turn.
   */
  private measureSoon(): void {
    setTimeout(() => this.measure(), 100);
  }

  /**
   * The tiles as drawn: the tallest one, one's width, the grid's width, and
   * the height the header, the closed-stands line and the band leave. Written
   * only when it changed, so a page turn does not loop on its own measure.
   */
  private measure(): void {
    const racine = this.host.nativeElement;
    const grille = racine.querySelector<HTMLElement>('.mural-grille');
    const tuiles = Array.from(racine.querySelectorAll<HTMLElement>('.mural-tuile'));
    if (!grille || tuiles.length === 0) {
      return;
    }
    const gap = parseFloat(getComputedStyle(grille).rowGap) || 0;
    const occupe = ['.mural-entete', '.mural-fermes', '.mural-bandeau']
      .map((selecteur) => racine.querySelector<HTMLElement>(selecteur)?.offsetHeight ?? 0)
      .reduce((somme, hauteur) => somme + hauteur, 0);
    const titres = racine.querySelectorAll('.mural-groupe h2').length;
    const mesure: ScreenMeasure = {
      largeurGrille: grille.clientWidth + gap,
      // The group headings of a page take a line each; kept aside.
      hauteurDisponible: window.innerHeight - occupe - titres * 48 - 48,
      hauteurTuile: Math.max(...tuiles.map((tuile) => tuile.offsetHeight)) + gap,
      largeurTuile: tuiles[0].offsetWidth + gap,
    };
    const previous = this.mesure();
    if (
      !previous ||
      previous.largeurGrille !== mesure.largeurGrille ||
      previous.hauteurDisponible !== mesure.hauteurDisponible ||
      previous.hauteurTuile !== mesure.hauteurTuile ||
      previous.largeurTuile !== mesure.largeurTuile
    ) {
      this.mesure.set(mesure);
    }
  }

  /** Every minute — every five once the link is concluded dead. */
  private poll(): void {
    if (this.lienMort() && performance.now() - this.lastStartedAt < DEAD_LINK_RETRY_MS) {
      return;
    }
    void this.read();
  }

  private async read(): Promise<void> {
    const sequence = ++this.lastStarted;
    this.lastStartedAt = performance.now();
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      const view = await Promise.race([
        this.apercu ? this.api.preview(this.date) : this.api.view(this.jeton),
        new Promise<never>((_, reject) => {
          timer = setTimeout(() => reject(new Error('timeout')), READ_TIMEOUT_MS);
        }),
      ]);
      if (sequence <= this.lastApplied) {
        return;
      }
      this.lastApplied = sequence;
      this.notFoundInARow = 0;
      this.lienMort.set(false);
      this.view.set(view);
      this.lueA.set(performance.now());
      this.tick.set(performance.now());
      this.echecDepuis.set(null);
      if (!this.impression()) {
        this.measureSoon();
      }
    } catch (error) {
      if (sequence <= this.lastApplied) {
        return;
      }
      if (error instanceof HttpErrorResponse && error.status === 404) {
        this.notFoundInARow++;
        if (this.notFoundInARow >= DEAD_LINK_AFTER) {
          this.lienMort.set(true);
          this.view.set(null);
          return;
        }
      } else {
        this.notFoundInARow = 0;
      }
      // Anything else — the network, a restart, a 429, a read that hangs, a
      // first 404 — is ridden out: the last state stays on screen, and the
      // band says since when.
      if (this.echecDepuis() === null) {
        this.echecDepuis.set(performance.now());
      }
    } finally {
      clearTimeout(timer);
    }
  }
}
