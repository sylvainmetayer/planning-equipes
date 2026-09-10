// The `/api/mcp/*` endpoints and the `/api/config` probe the MCP page reads.

import { HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { CleMcp, PromptMcp, StatutMcp } from '../models';

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

  /** A fresh key, against the administrator's password: the old one stops working at once. */
  regenerateKey(motDePasse: string): Promise<CleMcp> {
    return this.api.post<CleMcp>('/api/mcp/cle', { motDePasse });
  }
}
