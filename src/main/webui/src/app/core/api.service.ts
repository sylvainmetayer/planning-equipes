// Thin HTTP layer shared across the app.
//
// Every method returns a promise so components can use async/await, and every
// failure is normalised into an Error carrying a readable message.

import { HttpClient, HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  get<T>(url: string): Promise<T> {
    return this.run(this.http.get<T>(url));
  }

  /**
   * GET returning the whole response, for the callers that must tell an empty
   * 204 from a body (`/api/jobs/active` answers 204 when the solver is idle).
   */
  getResponse<T>(url: string): Promise<HttpResponse<T>> {
    return this.run(this.http.get<T>(url, { observe: 'response' }));
  }

  /**
   * GET scoped to an explicit edition, bypassing the browser's ambient one —
   * the edition interceptor leaves a pre-set `X-Edition-Id` untouched.
   */
  getDansEdition<T>(url: string, editionId: string): Promise<T> {
    return this.run(this.http.get<T>(url, { headers: { 'X-Edition-Id': editionId } }));
  }

  /**
   * POST rejecting with the untouched `HttpErrorResponse` instead of the
   * flattened Error, for the callers that must read the status and the body of
   * a failure — the solver job service turns a 409 into "this other job is
   * already running" using the conflicting job carried in the body.
   */
  postPreservingHttpError<T>(url: string, body: unknown): Promise<T> {
    return firstValueFrom(this.http.post<T>(url, body));
  }

  /** GET flavour of {@link postPreservingHttpError} — the espace animateur tells a 401 (code required) from a 404 (dead link). */
  getPreservingHttpError<T>(url: string): Promise<T> {
    return firstValueFrom(this.http.get<T>(url));
  }

  post<T>(url: string, body: unknown): Promise<T> {
    return this.run(this.http.post<T>(url, body));
  }

  put<T>(url: string, body: unknown): Promise<T> {
    return this.run(this.http.put<T>(url, body));
  }

  delete(url: string): Promise<void> {
    return this.run(this.http.delete<void>(url));
  }

  /** DELETE whose answer carries a body — the state left once the thing is gone. */
  deleteReturning<T>(url: string): Promise<T> {
    return this.run(this.http.delete<T>(url));
  }

  /** Sends a raw (non JSON) payload such as a SQL dump or a CSV file. */
  postRaw<T>(url: string, body: string, contentType: string): Promise<T> {
    return this.run(this.http.post<T>(url, body, { headers: { 'Content-Type': contentType } }));
  }

  /** POSTs a payload and saves the response as a file. */
  async downloadPost(
    url: string,
    filename: string,
    payload: unknown,
    contentType: string,
  ): Promise<string> {
    const blob = await this.run(this.http.post(url, payload, { responseType: 'blob' }));
    return this.saveAs(blob, filename, contentType);
  }

  /** GETs a file and saves it. */
  async downloadGet(url: string, filename: string, contentType: string): Promise<string> {
    const blob = await this.run(this.http.get(url, { responseType: 'blob' }));
    return this.saveAs(blob, filename, contentType);
  }

  /**
   * GETs a file and saves it under the name the server gave it in
   * `Content-Disposition`, `fallback` when it gave none: a name carrying the
   * edition and the day is the server's to decide, not one more copy of that
   * rule here.
   */
  async downloadGetNamedByServer(
    url: string,
    fallback: string,
    contentType: string,
  ): Promise<string> {
    const response = await this.run(
      this.http.get(url, { responseType: 'blob', observe: 'response' }),
    );
    const filename = attachmentName(response.headers.get('Content-Disposition')) ?? fallback;
    return this.saveAs(response.body ?? new Blob(), filename, contentType);
  }

  /**
   * A file the page built itself — a list exported as it is displayed —
   * saved the way a server download is, and answered with the same status
   * line.
   */
  saveText(content: string, filename: string, contentType: string): string {
    return this.saveAs(new Blob([content], { type: contentType }), filename, contentType);
  }

  private async run<T>(request: Observable<T>): Promise<T> {
    try {
      return await firstValueFrom(request);
    } catch (error) {
      throw toError(error);
    }
  }

  // Browser download without touching the application DOM.
  private saveAs(blob: Blob, filename: string, contentType: string): string {
    const objectUrl = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = objectUrl;
    link.download = filename;
    link.click();
    URL.revokeObjectURL(objectUrl);
    return $localize`:@@api.downloaded:${filename}:filename: téléchargé (${contentType}:contentType:).`;
  }
}

/** The `filename="…"` of a `Content-Disposition` header, `null` when there is none. */
export function attachmentName(header: string | null): string | null {
  const match = header ? /filename="([^"]+)"/.exec(header) : null;
  return match ? match[1] : null;
}

/**
 * What kind of refusal the server expressed, in the client's own words.
 *
 * <p>Mirrors the sealed hierarchy the backend answers with (`ErreurMetierMapper`:
 * `Invalide` to 400, `Introuvable` to 404, `Conflit` to 409, body `{message}`).
 * Flattening every failure into a bare `Error` threw the status away, and with
 * it the only thing telling "this reference no longer exists, reload the list"
 * (404) from "your form is wrong" (400) and from "someone else changed it, try
 * again" (409) — three situations that all ended in the same red banner.</p>
 */
export type ApiErrorKind = 'invalid' | 'notFound' | 'conflict' | 'session' | 'technical';

/** The `code` a 409 carries when the write was based on an out-of-date read (issue #362). */
export const CODE_MODIFICATION_CONCURRENTE = 'MODIFICATION_CONCURRENTE';

/** A server refusal that still knows what it was. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly kind: ApiErrorKind,
    message: string,
    /**
     * The discriminator the body carries when a status is not enough — today
     * only {@link CODE_MODIFICATION_CONCURRENTE}, the one 409 the client
     * answers with a choice rather than with a banner.
     */
    readonly code: string | null = null,
    /**
     * When the row was actually last written, on a
     * {@link CODE_MODIFICATION_CONCURRENTE} refusal: the server sends the
     * instant rather than a formatted date, because it runs in UTC while
     * every date the user reads is rendered by their own browser.
     */
    readonly modifieLe: string | null = null,
  ) {
    super(message);
    this.name = 'ApiError';
  }

  /** True for the 409 that means "someone else wrote this row since you loaded it". */
  get modificationConcurrente(): boolean {
    return this.code === CODE_MODIFICATION_CONCURRENTE;
  }
}

function kindForStatus(status: number): ApiErrorKind {
  switch (status) {
    case 400:
      return 'invalid';
    case 401:
      return 'session';
    case 404:
      return 'notFound';
    case 409:
      return 'conflict';
    default:
      return 'technical';
  }
}

/**
 * A 401 on an admin API call: the session is missing or expired. The auth
 * interceptor is already sending the user to /login when this happens, so
 * this error is a technical detail, not news — {@code reportError} and the
 * other toast paths skip it instead of stacking an « Échec de la requête
 * (code 401) » notification on top of the redirect.
 */
export class SessionExpireeError extends ApiError {
  constructor() {
    super(401, 'session', $localize`:@@api.sessionExpiree:Session expirée — reconnexion en cours.`);
    this.name = 'SessionExpireeError';
  }
}

/**
 * Turns an HTTP failure into a readable Error: the server message when the
 * backend sent one, the status code otherwise. A 401 becomes a
 * {@link SessionExpireeError} so notification paths can stay silent about it.
 */
export function toError(error: unknown): Error {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 401) {
      return new SessionExpireeError();
    }
    const body = error.error as
      { message?: string; code?: string; modifieLe?: string } | string | null;
    const message =
      body && typeof body === 'object' && body.message
        ? body.message
        : $localize`:@@api.requestFailed:Échec de la requête (code ${error.status}:status:)`;
    const code =
      body && typeof body === 'object' && typeof body.code === 'string' ? body.code : null;
    // Produced here and nowhere else: every caller can now switch on `kind`
    // instead of re-deriving the meaning from the message text.
    const modifieLe =
      body && typeof body === 'object' && typeof body.modifieLe === 'string'
        ? body.modifieLe
        : null;
    return new ApiError(error.status, kindForStatus(error.status), message, code, modifieLe);
  }
  return error instanceof Error ? error : new Error(String(error));
}
