import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { HealthApiService } from './health-api.service';
import { HealthResponseBody } from './health.model';

describe('HealthApiService', () => {
  let service: HealthApiService;
  let httpMock: HttpTestingController;

  const upBody: HealthResponseBody = {
    status: 'UP',
    database: { status: 'UP', latencyMs: 4 },
    timestamp: '2026-10-22T10:00:00Z',
  };

  const downBody: HealthResponseBody = {
    status: 'DOWN',
    database: { status: 'DOWN', latencyMs: 2000 },
    timestamp: '2026-10-22T10:00:01Z',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(HealthApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('requests GET /api/v1/health', () => {
    let outcome: unknown;
    service.check().subscribe((result) => (outcome = result));

    const request = httpMock.expectOne('/api/v1/health');
    expect(request.request.method).toBe('GET');
    request.flush(upBody);

    expect(outcome).toEqual({ kind: 'up', body: upBody });
  });

  it('maps HTTP 200 to the "up" outcome', () => {
    let outcome: any;
    service.check().subscribe((result) => (outcome = result));

    httpMock.expectOne('/api/v1/health').flush(upBody, { status: 200, statusText: 'OK' });

    expect(outcome.kind).toBe('up');
    expect(outcome.body.database.latencyMs).toBe(4);
  });

  it('maps HTTP 503 to the "down" outcome instead of an error', () => {
    let outcome: any;
    let failed = false;
    service.check().subscribe({
      next: (result) => (outcome = result),
      error: () => (failed = true),
    });

    httpMock
      .expectOne('/api/v1/health')
      .flush(downBody, { status: 503, statusText: 'Service Unavailable' });

    expect(failed).toBeFalse();
    expect(outcome.kind).toBe('down');
    expect(outcome.httpStatus).toBe(503);
    expect(outcome.body.database.status).toBe('DOWN');
  });

  it('maps a network error to the "unreachable" outcome', () => {
    let outcome: any;
    service.check().subscribe((result) => (outcome = result));

    httpMock.expectOne('/api/v1/health').error(new ProgressEvent('network error'), {
      status: 0,
      statusText: 'Unknown Error',
    });

    expect(outcome.kind).toBe('unreachable');
  });

  it('maps an unexpected status to the "unreachable" outcome', () => {
    let outcome: any;
    service.check().subscribe((result) => (outcome = result));

    httpMock.expectOne('/api/v1/health').flush('boom', { status: 500, statusText: 'Server Error' });

    expect(outcome.kind).toBe('unreachable');
  });
});