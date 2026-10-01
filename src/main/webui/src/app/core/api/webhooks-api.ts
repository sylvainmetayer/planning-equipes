// The outgoing webhooks of the instance (Paramètres › Instance): the list, the
// form, the test and the journal of deliveries.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import {
  WebhookDeliveryView,
  WebhookRequest,
  WebhookSaved,
  WebhookSecret,
  WebhookTestResult,
  WebhookView,
} from '../models';

@Injectable({ providedIn: 'root' })
export class WebhooksApi {
  private readonly api = inject(ApiService);

  list(): Promise<WebhookView[]> {
    return this.api.get<WebhookView[]>('/api/webhooks');
  }

  create(request: WebhookRequest): Promise<WebhookSaved> {
    return this.api.post<WebhookSaved>('/api/webhooks', request);
  }

  update(id: string, request: WebhookRequest): Promise<WebhookSaved> {
    return this.api.put<WebhookSaved>(`/api/webhooks/${encodeURIComponent(id)}`, request);
  }

  delete(id: string): Promise<void> {
    return this.api.delete(`/api/webhooks/${encodeURIComponent(id)}`);
  }

  regenerateSecret(id: string): Promise<WebhookSecret> {
    return this.api.post<WebhookSecret>(`/api/webhooks/${encodeURIComponent(id)}/secret`, null);
  }

  test(id: string): Promise<WebhookTestResult> {
    return this.api.post<WebhookTestResult>(`/api/webhooks/${encodeURIComponent(id)}/test`, null);
  }

  deliveries(id: string): Promise<WebhookDeliveryView[]> {
    return this.api.get<WebhookDeliveryView[]>(
      `/api/webhooks/${encodeURIComponent(id)}/livraisons`,
    );
  }

  resend(deliveryId: string): Promise<WebhookDeliveryView> {
    return this.api.post<WebhookDeliveryView>(
      `/api/webhooks/livraisons/${encodeURIComponent(deliveryId)}/renvoi`,
      null,
    );
  }
}
