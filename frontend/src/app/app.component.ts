import { Component } from '@angular/core';
import { HealthPanelComponent } from './health/health-panel.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [HealthPanelComponent],
  template: `
    <main class="page">
      <h1>Service health</h1>
      <app-health-panel />
    </main>
  `,
  styles: [
    `
      .page {
        max-width: 40rem;
        margin: 0 auto;
      }
    `,
  ],
})
export class AppComponent {}