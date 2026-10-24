import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet],
  template: `
    <main class="page">
      <router-outlet />
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