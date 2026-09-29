import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-forbidden',
  imports: [RouterLink],
  template: `
    <section class="card">
      <h1 i18n="@@forbidden.title">Kein Zugriff</h1>
      <p i18n="@@forbidden.body">Dieser Bereich ist Administratoren vorbehalten.</p>
      <a routerLink="/" i18n="@@forbidden.home">Zur Startseite</a>
    </section>
  `,
})
export class ForbiddenPage {}
