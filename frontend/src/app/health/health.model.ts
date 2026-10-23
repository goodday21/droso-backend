/** Overall status of the service, as reported by the backend. */
export type HealthStatus = 'UP' | 'DOWN';

/** Health of the PostgreSQL dependency. */
export interface DatabaseHealth {
  status: HealthStatus;
  latencyMs: number;
}

/** Body of a successful (HTTP 200) health response. */
export interface HealthResponseBody {
  status: HealthStatus;
  database: DatabaseHealth;
  timestamp: string;
}

/**
 * Outcome of a health call as seen by the UI.
 *
 * - `up`:        the backend answered HTTP 200 and the database is reachable.
 * - `down`:      the backend answered HTTP 503, the database is not reachable.
 * - `unreachable`: the backend itself could not be reached (network error, wrong URL).
 */
export type HealthCheckOutcome =
  | { kind: 'up'; body: HealthResponseBody }
  | { kind: 'down'; body: HealthResponseBody | null; httpStatus: number }
  | { kind: 'unreachable'; message: string };