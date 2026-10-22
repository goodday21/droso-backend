import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of } from 'rxjs';
import { HealthCheckOutcome, HealthResponseBody } from './health.model';

export const HEALTH_ENDPOINT = '/api/v1/health';

/**
 * Client of the health endpoint.
 *
 * The endpoint answers HTTP 503 when the database is down, which is a normal, expected answer
 * and not a transport failure. It is therefore mapped to the `down` outcome instead of being
 * reported as an error.
 */
@Injectable({ providedIn: 'root' })
export class HealthApiService {
  private readonly http = inject(HttpClient);

  check(): Observable<HealthCheckOutcome> {
    return this.http.get<HealthResponseBody>(HEALTH_ENDPOINT).pipe(
      map((body) => ({ kind: 'up', body }) as HealthCheckOutcome),
      catchError((error: HttpErrorResponse) => {
        if (error.status === 503) {
          return of({
            kind: 'down',
            body: (error.error as HealthResponseBody) ?? null,
            httpStatus: 503,
          } as HealthCheckOutcome);
        }
        if (error.status === 0) {
          // No response at all: the server is not reachable.
          return of({
            kind: 'unreachable',
            message: 'The service is not reachable',
          } as HealthCheckOutcome);
        }
        return of({
          kind: 'unreachable',
          message: `Unexpected response status ${error.status}`,
        } as HealthCheckOutcome);
      }),
    );
  }
}