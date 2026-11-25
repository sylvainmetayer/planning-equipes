// The `/api/mcp/*` endpoints and the `/api/config` probe the MCP page reads.

import { HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { GeneratedMcpKey, CleMcp, PromptMcp, StatutMcp } from '../models';

@Injectable({ providedIn: 'root' })
export class McpApi {
  private readonly api = inject(ApiService);

  /**
   * The public configuration, as a whole response: the page reads the
   * `X-Pangolin` header off it to know whether an access proxy sits in front.
   */
  configResponse(): Promise<HttpResponse<unknown>> {
    return this.api.getResponse<unknown>('/api/config');
  }

  /** Whether a key is configured server-side — never the key itself. */
  status(): Promise<StatutMcp> {
    return this.api.get<StatutMcp>('/api/mcp/statut');
  }

  /** The prompts the MCP server itself announces. */
  prompts(): Promise<PromptMcp[]> {
    return this.api.get<PromptMcp[]>('/api/mcp/prompts');
  }

  /** The key of the configuration, to an administrator who signed in a few minutes ago. */
  regenerateKey(): Promise<CleMcp> {
    return this.api.post<CleMcp>('/api/mcp/cle', {});
  }

  /**
   * A new key, shown once: the old one keeps working for `recouvrementMinutes`
   * (0: it stops at once).
   */
  rotateKey(recouvrementMinutes: number): Promise<GeneratedMcpKey> {
    return this.api.post<GeneratedMcpKey>('/api/mcp/cle/rotation', { recouvrementMinutes });
  }

  /**
   * Leaves for the provider to sign in again, then comes back to `retour`
   * (a path of this application): the server drops the OIDC session and asks
   * the provider to authenticate afresh.
   */
  reauthenticate(retour: string): void {
    window.location.assign(`/api/auth/oidc/reconnexion?retour=${encodeURIComponent(retour)}`);
  }
}
