import { TestBed } from '@angular/core/testing';
import { App } from './app';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
    }).compileComponents();
  });

  async function pinta(): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('dice que está en construcción', async () => {
    const pagina = await pinta();
    expect(pagina.querySelector('h1')?.textContent).toContain('TrainWatch · en construcción');
  });

  it('avisa de que es un proyecto no oficial', async () => {
    const pagina = await pinta();
    expect(pagina.querySelector('.aviso')?.textContent).toContain('no oficial');
  });

  it('cita la fuente de los datos', async () => {
    const pagina = await pinta();
    expect(pagina.querySelector('footer')?.textContent).toContain('Datos: Renfe (CC BY 4.0)');
  });
});
