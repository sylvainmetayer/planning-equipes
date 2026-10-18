import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../api.service';
import { AffichageMuralApi } from './affichage-mural-api';

describe('AffichageMuralApi', () => {
  const post = vi.fn();
  let api: AffichageMuralApi;

  beforeEach(() => {
    post.mockReset();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), { provide: ApiService, useValue: { post } }],
    });
    api = TestBed.inject(AffichageMuralApi);
  });

  it('asks the QR code of a link', async () => {
    post.mockResolvedValue({ size: 1, rows: ['1'] });
    await expect(api.qrCode('https://planning.test/mural/t')).resolves.toEqual({
      size: 1,
      rows: ['1'],
    });
    expect(post).toHaveBeenCalledWith('/api/affichage-mural/qr-code', {
      link: 'https://planning.test/mural/t',
    });
  });

  it('answers null when the code could not be drawn: the address alone still does the job', async () => {
    post.mockRejectedValue(new Error('500'));
    await expect(api.qrCode('https://planning.test/mural/t')).resolves.toBeNull();
  });
});
