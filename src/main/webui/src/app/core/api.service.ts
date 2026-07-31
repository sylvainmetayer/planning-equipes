// Thin HTTP layer shared across the app.
//
// Every method returns a promise so components can use async/await, and every
// failure is normalised into an Error carrying a readable message.

import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  get<T>(url: string): Promise<T> {
    return this.run(this.http.get<T>(url));
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

  /** Sends a raw (non JSON) payload such as a SQL dump or a CSV file. */
  postRaw<T>(url: string, body: string, contentType: string): Promise<T> {
    return this.run(this.http.post<T>(url, body, { headers: { 'Content-Type': contentType } }));
  }

  /** POSTs a payload and saves the response as a file. */
  async downloadPost(url: string, filename: string, payload: unknown, contentType: string): Promise<string> {
    const blob = await this.run(this.http.post(url, payload, { responseType: 'blob' }));
    return this.saveAs(blob, filename, contentType);
  }

  /** GETs a file and saves it. */
  async downloadGet(url: string, filename: string, contentType: string): Promise<string> {
    const blob = await this.run(this.http.get(url, { responseType: 'blob' }));
    return this.saveAs(blob, filename, contentType);
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
    return `${filename} téléchargé (${contentType}).`;
  }
}

/**
 * Turns an HTTP failure into a readable Error: the server message when the
 * backend sent one, the status code otherwise.
 */
export function toError(error: unknown): Error {
  if (error instanceof HttpErrorResponse) {
    const body = error.error as { message?: string } | string | null;
    if (body && typeof body === 'object' && body.message) {
      return new Error(body.message);
    }
    return new Error(`Échec de la requête (code ${error.status})`);
  }
  return error instanceof Error ? error : new Error(String(error));
}
