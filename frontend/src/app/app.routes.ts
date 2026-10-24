import { Routes } from '@angular/router';
import { HealthPageComponent } from './health/health-page.component';
import { NotFoundComponent } from './not-found/not-found.component';

// Only the root is a page. The dev server answers every unknown path with index.html, so the
// router has to reject the rest itself.
export const routes: Routes = [
  { path: '', pathMatch: 'full', component: HealthPageComponent, title: 'Service health' },
  { path: '**', component: NotFoundComponent, title: 'Page not found' },
];
