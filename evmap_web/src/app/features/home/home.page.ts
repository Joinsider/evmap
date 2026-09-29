import { Component } from '@angular/core';

@Component({
  selector: 'app-home',
  template: `
    <section class="card">
      <h1 i18n="@@home.title">EVMap im Browser</h1>
      <p i18n="@@home.body">Karte, Stationen und Kommentare kommen hier später dazu. Bis dahin gibt es EVMap als App für das iPhone.</p>
    </section>
  `,
})
export class HomePage {}
