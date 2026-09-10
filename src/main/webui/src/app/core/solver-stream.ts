// The pushed half of SolverJobService: /api/jobs/stream, and everything that
// makes server-sent events safe to lean on.
//
// SSE fails SILENTLY. A proxy that buffers, a connection cut that never
// reconnects: nothing throws, nothing logs, and the screen simply stops being
// true. So this owns three things beside the EventSource itself — a watchdog
// for the silence, a capped backoff for the reconnections, and the one place a
// stream is closed before another is opened, so handlers never pile up. It
// holds no job state: what an event carries goes to the listener, and so does
// every change of its proof of life, because the listener is the poll that
// takes the lead back whenever the stream loses it.

import { JobView } from './models';
import { ScoreStreamDelta } from './score-trace';

/** Pushed counterpart of `/api/jobs/active` + `/api/jobs/file`, in one event. */
const STREAM_URL = '/api/jobs/stream';

/**
 * How long the stream may say nothing — no state, no heartbeat — before it is
 * treated as dead: the listener is told it lost the lead and the stream is
 * reopened. Comfortably above the server's 20 s heartbeat, so one missed beat
 * (a hiccup, a suspended laptop) does not count as a death.
 */
export const STREAM_SILENCE_MS = 45000;

/**
 * Reconnection backoff. `EventSource` does reconnect on its own, but always
 * after the same fixed delay: a server that is down is then hammered at that
 * rate for as long as it stays down, by every open tab. So the stream is closed
 * on error and reopened by hand, doubling from one second and capped — the cap
 * matters as much as the growth, since a stream that never comes back must
 * still be retried while the user has the page open.
 */
const STREAM_RETRY_MIN_MS = 1000;
const STREAM_RETRY_MAX_MS = 30000;

/** One `state` event: everything `/jobs/active` and `/jobs/file` answer. */
export interface JobsStreamState {
  active: JobView | null;
  file: JobView[];
}

/** What the stream reports to whoever owns the state. */
export interface SolverStreamListener {
  /** A pushed state: the same aggregate as a poll, plus the queue. */
  onState(state: JobsStreamState): void;
  /** New points of the running solve's curve — see {@link ScoreStreamDelta}. */
  onScore(delta: ScoreStreamDelta): void;
  /** The stream has just proved it is alive: the poll may drop to its idle pace. */
  onAlive(): void;
  /**
   * A stream that was alive went silent or failed. The failure this whole
   * design exists for: nothing may have been reported — the connection may
   * well still be open — and the state on screen has simply stopped being
   * true. The poll must take the lead back at once, not at its next idle tick.
   */
  onLost(): void;
}

/**
 * One `EventSource` on `/api/jobs/stream`, kept open between {@link open} and
 * {@link close} through silences and errors.
 *
 * <p>`EventSource` is read off the global rather than imported, and its absence
 * is a supported case rather than a crash: an environment without it — a very
 * old browser, the jsdom the unit tests run in — simply never proves alive,
 * which leaves the poll in the lead, which is exactly what the fallback is
 * for anyway.</p>
 */
export class SolverStream {
  /** The open source, or null when there is none. */
  private source: EventSource | null = null;
  /** Between open() and close(): the only state in which a reconnection is scheduled. */
  private wanted = false;
  /**
   * Whether the stream has proved it is alive — a state event or a heartbeat
   * within the last {@link STREAM_SILENCE_MS}. Until it has said something,
   * and again as soon as it goes quiet, polling is what keeps the screen
   * truthful.
   */
  private provenAlive = false;
  /** Fires when the stream has been silent for too long. */
  private silenceHandle: ReturnType<typeof setTimeout> | null = null;
  /** Pending reconnection, so two failures never schedule two reopens. */
  private retryHandle: ReturnType<typeof setTimeout> | null = null;
  /** Consecutive failed opens, driving the exponential backoff. */
  private retryCount = 0;

  constructor(private readonly listener: SolverStreamListener) {}

  /** True while the stream has spoken within the last {@link STREAM_SILENCE_MS}. */
  get alive(): boolean {
    return this.provenAlive;
  }

  open(): void {
    if (this.wanted) {
      return;
    }
    this.wanted = true;
    this.connect();
  }

  /**
   * Closes the stream and stops reopening it. `EventSource` reconnects by
   * itself, so without this an expired session would keep reopening a stream
   * the server answers with a 401, forever — not even a pending reconnection
   * survives. {@link open} works again afterwards.
   */
  close(): void {
    this.wanted = false;
    this.disconnect();
    if (this.retryHandle !== null) {
      clearTimeout(this.retryHandle);
      this.retryHandle = null;
    }
    this.provenAlive = false;
    this.retryCount = 0;
  }

  private connect(): void {
    if (!this.wanted || this.source !== null || typeof EventSource === 'undefined') {
      return;
    }
    const source = new EventSource(STREAM_URL);
    this.source = source;
    source.addEventListener('state', (event) => this.onStateEvent(event as MessageEvent<string>));
    // A heartbeat carries no state; it is only the proof the stream is alive,
    // and that proof is precisely what the watchdog below waits for.
    source.addEventListener('heartbeat', () => this.markAlive());
    source.addEventListener('score', (event) => this.onScoreEvent(event as MessageEvent<string>));
    source.onerror = () => this.onError();
    // Armed from the open, not from the first event: a proxy that accepts the
    // connection and then buffers it forever never sends a first event, and
    // that silence has to be caught too.
    this.armSilenceWatchdog();
  }

  private onStateEvent(event: MessageEvent<string>): void {
    this.markAlive();
    let state: JobsStreamState;
    try {
      state = JSON.parse(event.data) as JobsStreamState;
    } catch {
      return; // a truncated event says nothing about the state; wait for the next
    }
    this.listener.onState(state);
  }

  private onScoreEvent(event: MessageEvent<string>): void {
    this.markAlive();
    let delta: ScoreStreamDelta;
    try {
      delta = JSON.parse(event.data) as ScoreStreamDelta;
    } catch {
      return; // a truncated event says nothing about the curve; wait for the next
    }
    this.listener.onScore(delta);
  }

  private markAlive(): void {
    this.retryCount = 0;
    if (!this.provenAlive) {
      this.provenAlive = true;
      this.listener.onAlive();
    }
    this.armSilenceWatchdog();
  }

  private armSilenceWatchdog(): void {
    if (this.silenceHandle !== null) {
      clearTimeout(this.silenceHandle);
    }
    this.silenceHandle = setTimeout(() => this.onSilent(), STREAM_SILENCE_MS);
  }

  /** No error, no close, and nothing said for too long: thrown away and reopened. */
  private onSilent(): void {
    this.silenceHandle = null;
    this.disconnect();
    this.lose();
    this.scheduleRetry();
  }

  private onError(): void {
    const wasAlive = this.provenAlive;
    this.disconnect();
    if (wasAlive) {
      this.lose();
    }
    // Never proved alive: polling was already in the lead, and telling it again
    // on every failed reconnection would turn a server outage into a flood.
    this.scheduleRetry();
  }

  private lose(): void {
    this.provenAlive = false;
    this.listener.onLost();
  }

  private scheduleRetry(): void {
    if (!this.wanted || this.retryHandle !== null) {
      return;
    }
    const delay = Math.min(STREAM_RETRY_MAX_MS, STREAM_RETRY_MIN_MS * 2 ** this.retryCount);
    this.retryCount += 1;
    this.retryHandle = setTimeout(() => {
      this.retryHandle = null;
      this.connect();
    }, delay);
  }

  /**
   * Closes the source and forgets it. Every reconnection goes through here
   * first, which is what keeps handlers from piling up: a reopen builds a
   * brand-new `EventSource` with its own listeners, and the previous one is
   * closed and dropped rather than left listening beside it.
   */
  private disconnect(): void {
    if (this.source !== null) {
      this.source.close();
      this.source = null;
    }
    if (this.silenceHandle !== null) {
      clearTimeout(this.silenceHandle);
      this.silenceHandle = null;
    }
  }
}
