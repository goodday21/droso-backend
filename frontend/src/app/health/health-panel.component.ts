import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { HealthApiService } from './health-api.service';
import { HealthCheckOutcome } from './health.model';

/**
 * Shows the current health of the service and of its PostgreSQL dependency.
 */
@Component({
  selector: 'app-health-panel',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="card" [attr.data-state]="state()">
      <div class="row">
        <span class="badge" [class.badge--up]="state() === 'up'" [class.badge--down]="state() === 'down'">
          {{ label() }}
        </span>
        <button type="button" (click)="refresh()" [disabled]="loading()">Refresh</button>
      </div>

      @if (outcome(); as result) {
        @switch (result.kind) {
          @case ('up') {
            <dl>
              <dt>Database</dt>
              <dd>{{ result.body.database.status }}</dd>
              <dt>Latency (ms)</dt>
              <dd>{{ result.body.database.latencyMs }} ms</dd>
              <dt>Checked at</dt>
              <dd>{{ result.body.timestamp }}</dd>
            </dl>
          }
          @case ('down') {
            <dl>
              <dt>Database</dt>
              <dd>DOWN</dd>
              <dt>HTTP status</dt>
              <dd>{{ result.httpStatus }}</dd>
              @if (result.body) {
                <dt>Checked at</dt>
                <dd>{{ result.body.timestamp }}</dd>
              }
            </dl>
          }
          @case ('unreachable') {
            <p class="message">{{ result.message }}</p>
          }
        }
      }
    </section>
  `,
  styles: [
    `
      .card {
        background: #fff;
        border-radius: 8px;
        padding: 1.25rem;
        box-shadow: 0 1px 3px rgba(0, 0, 0, 0.12);
      }
      .row {
        display: flex;
        align-items: center;
        gap: 0.75rem;
      }
      .badge {
        display: inline-block;
        padding: 0.25rem 0.75rem;
        border-radius: 999px;
        font-weight: 600;
        color: #fff;
        background: #9aa5b1;
      }
      .badge--up {
        background: #2f9e44;
      }
      .badge--down {
        background: #e03131;
      }
      dl {
        display: grid;
        grid-template-columns: max-content 1fr;
        gap: 0.25rem 1rem;
        margin-bottom: 0;
      }
      dt {
        font-weight: 600;
        color: #52606d;
      }
      dd {
        margin: 0;
      }
      .message {
        margin-bottom: 0;
        color: #e03131;
      }
    `,
  ],
})
export class HealthPanelComponent {
  private readonly api = inject(HealthApiService);

  readonly outcome = signal<HealthCheckOutcome | null>(null);
  readonly loading = signal(false);

  constructor() {
    this.refresh();
  }

  state(): 'up' | 'down' | 'unknown' {
    const result = this.outcome();
    if (!result) {
      return 'unknown';
    }
    return result.kind === 'up' ? 'up' : 'down';
  }

  label(): string {
    switch (this.state()) {
      case 'up':
        return 'UP';
      case 'down':
        return 'DOWN';
      default:
        return 'CHECKING';
    }
  }

  refresh(): void {
    this.loading.set(true);
    this.api.check().subscribe((result) => {
      this.outcome.set(result);
      this.loading.set(false);
    });
  }
}