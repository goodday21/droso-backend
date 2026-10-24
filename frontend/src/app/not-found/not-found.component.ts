import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * Shown for every path except the root: the application has no other pages.
 */
@Component({
  selector: 'app-not-found',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  template: `
    <h1>Page not found</h1>
    <p>This page does not exist. <a routerLink="/">Go to the service health</a>.</p>
  `,
})
export class NotFoundComponent {}
