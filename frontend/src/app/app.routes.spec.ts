import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from './app.routes';
import { HealthPageComponent } from './health/health-page.component';
import { NotFoundComponent } from './not-found/not-found.component';

describe('routes', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    });
  });

  it('shows the health page at the root', async () => {
    const harness = await RouterTestingHarness.create();
    const page = await harness.navigateByUrl('/', HealthPageComponent);

    expect(page).toBeInstanceOf(HealthPageComponent);
  });

  it('shows "Page not found" for any other path', async () => {
    const harness = await RouterTestingHarness.create();

    for (const url of ['/qwerty', '/api', '/qwerty/nested']) {
      await harness.navigateByUrl(url, NotFoundComponent);
      expect(harness.routeNativeElement?.textContent).toContain('Page not found');
    }
  });
});
