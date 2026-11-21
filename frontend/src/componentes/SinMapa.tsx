import { Component, type ReactNode } from 'react';

/**
 * Si el mapa no puede funcionar (sin WebGL, o si no carga su código), no se cae toda la página: se avisa y se pasa a la
 * vista de lista, que es la alternativa.
 */
export class SinMapa extends Component<{ alFallar(): void; children: ReactNode }, { fallo: boolean }> {
  state = { fallo: false };

  static getDerivedStateFromError(): { fallo: boolean } {
    return { fallo: true };
  }

  componentDidCatch(): void {
    this.props.alFallar();
  }

  render() {
    if (this.state.fallo)
      return (
        <p className="aviso-mapa" role="alert">
          No se puede mostrar el mapa en este navegador. Los trenes están en la vista de lista.
        </p>
      );
    return this.props.children;
  }
}
