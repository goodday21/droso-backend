import { ChangeDetectionStrategy, Component } from '@angular/core';
import { HealthPanelComponent } from './health-panel.component';

/**
 * The root page of the application.
 */
@Component({
  selector: 'app-health-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [HealthPanelComponent],
  template: `
    <h1>Service health</h1>
    <app-health-panel />
  `,
})
export class HealthPageComponent {}
