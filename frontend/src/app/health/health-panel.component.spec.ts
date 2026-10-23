import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { HealthPanelComponent } from './health-panel.component';

describe('HealthPanelComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [HealthPanelComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
  });

  it('shows UP when the backend answers HTTP 200', async () => {
    const fixture = TestBed.createComponent(HealthPanelComponent);
    fixture.detectChanges();

    httpMock.expectOne('/api/v1/health').flush(
      { status: 'UP', database: { status: 'UP', latencyMs: 3 }, timestamp: '2026-10-22T10:00:00Z' },
      { status: 200, statusText: 'OK' },
    );
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('UP');
    expect(text).toContain('3 ms');
  });

  it('shows DOWN when the backend answers HTTP 503', async () => {
    const fixture = TestBed.createComponent(HealthPanelComponent);
    fixture.detectChanges();

    httpMock.expectOne('/api/v1/health').flush(
      { status: 'DOWN', database: { status: 'DOWN', latencyMs: 2000 }, timestamp: '2026-10-22T10:00:01Z' },
      { status: 503, statusText: 'Service Unavailable' },
    );
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('DOWN');
    expect(text).toContain('503');
  });

  it('shows a message when the service is not reachable', async () => {
    const fixture = TestBed.createComponent(HealthPanelComponent);
    fixture.detectChanges();

    httpMock
      .expectOne('/api/v1/health')
      .error(new ProgressEvent('network error'), { status: 0, statusText: 'Unknown Error' });
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('not reachable');
  });
});