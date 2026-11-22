/**
 * Mirrors Spring Boot Actuator's default health response shape
 * (org.springframework.boot.health.contributor.Status via GET /actuator/health). Only `status`
 * is guaranteed present at the default `show-details: never` exposure this backend uses (see
 * application.yaml) - components/details are intentionally not modeled since the backend never
 * returns them here.
 */
export type HealthState = 'UP' | 'DOWN' | 'UNKNOWN';

export interface HealthResponse {
  readonly status: HealthState;
}
