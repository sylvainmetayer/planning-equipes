import { Pipe, PipeTransform } from '@angular/core';
import { formatHeure } from '../core/time-of-day';

/**
 * `HH:mm` of a time the API sends as `HH:mm:ss`, for a template: « 18:00–22:00 »
 * where the raw value read « 18:00:00–22:00:00 ». Empty for a missing time.
 */
@Pipe({ name: 'hhmm' })
export class HhmmPipe implements PipeTransform {
  transform(time: string | null | undefined): string {
    return time ? formatHeure(time) : '';
  }
}
