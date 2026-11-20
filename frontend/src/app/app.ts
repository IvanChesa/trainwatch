import { ChangeDetectionStrategy, Component, signal } from '@angular/core';

/**
 * Página provisional de la fase 1: el mapa llega en la fase 5.
 * Componente standalone (sin NgModule) y con el estado en signals.
 */
@Component({
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  protected readonly nombre = signal('TrainWatch');
  protected readonly estado = signal('en construcción');
}
