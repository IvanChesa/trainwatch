package com.ivanchesa.trainwatch.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ivanchesa.trainwatch.api.ConsultasApi.Extension;
import com.ivanchesa.trainwatch.api.ExtensionDelServicio.Parada;

/**
 * El encuadre de un núcleo (fase 5b, ADR 0023): el rectángulo donde está el servicio, no el de todas sus estaciones.
 *
 * <p>Con los datos reales: {@code horas-de-paso-rodalies-y-bilbao.csv} son las estaciones de tren de Rodalies (51) y
 * de Bilbao (60) con sus horas de paso en el horario de Renfe importado el 20-11-2026 (Datos: Renfe, CC BY 4.0). Con
 * el rectángulo de todas las estaciones (lo de antes), Rodalies iba de Villanueva de Gállego (Aragón) a Cerbère
 * (Francia) y el mapa abría con media península a la vista: estas pruebas fallaban.
 */
class ExtensionDelServicioTests {

    static List<Parada> paradas(int nucleo) throws IOException {
        List<Parada> paradas = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ExtensionDelServicioTests.class.getResourceAsStream("/horas-de-paso-rodalies-y-bilbao.csv"),
                StandardCharsets.UTF_8))) {
            r.readLine(); // cabecera: nucleo;stop_name;stop_lon;stop_lat;pasos
            for (String linea = r.readLine(); linea != null; linea = r.readLine()) {
                String[] c = linea.split(";");
                if (Integer.parseInt(c[0]) == nucleo) {
                    paradas.add(new Parada(Double.parseDouble(c[2]), Double.parseDouble(c[3]), Long.parseLong(c[4])));
                }
            }
        }
        return paradas;
    }

    @Test
    void rodaliesSinAragonNiUlldeconaNiFrancia() throws IOException {
        Extension e = ExtensionDelServicio.de(51, paradas(51));
        // Fuera: la R15 por Aragón (Villanueva de Gállego, -0,82; Caspe, -0,04), Ulldecona (40,60) y Cerbère (3,163).
        assertThat(e.lonMin()).isGreaterThan(0.4);
        assertThat(e.latMin()).isGreaterThan(40.6);
        assertThat(e.lonMax()).isLessThan(3.163);
        // Dentro: Lleida-Pirineus (0,634), Tortosa (40,81), Portbou (3,158) y casi hasta Puigcerdà (42,43).
        assertThat(e.lonMin()).isLessThan(0.634);
        assertThat(e.latMin()).isLessThan(40.81);
        assertThat(e.lonMax()).isGreaterThan(3.14);
        assertThat(e.latMax()).isGreaterThan(42.4);
        // El zoom para 800 × 600 px pasa de 6,9 (media península) a 7,4.
        assertThat(NucleosController.zoom(e)).isGreaterThanOrEqualTo(7.3);
    }

    @Test
    void bilbaoSinLaMitadLeonesaDeLaC4() throws IOException {
        Extension e = ExtensionDelServicio.de(60, paradas(60));
        // La C4 (antigua La Robla) llega a León (-5,57) con muy pocos trenes: se queda fuera.
        assertThat(e.lonMin()).isGreaterThan(-5.2);
        assertThat(e.latMin()).isGreaterThan(42.7);
        // Bilbao-Abando (-2,93, 43,26) sigue dentro.
        assertThat(e.lonMax()).isGreaterThan(-2.93);
        assertThat(e.latMax()).isGreaterThan(43.26);
    }

    @Test
    void unaEstacionApartadaConMuchosTrenesSeQueda() {
        List<Parada> p = new ArrayList<>(List.of(new Parada(-0.40, 39.40, 100), new Parada(-0.40, 39.43, 100)));
        p.add(new Parada(-0.10, 39.60, 50)); // apartada, pero con el 20 % de los pasos
        Extension e = ExtensionDelServicio.de(40, p);
        assertThat(e).isEqualTo(new Extension(40, -0.40, 39.40, -0.10, 39.60));
    }

    @Test
    void conUnaSolaParadaOSinPasosSaleUnRectanguloValido() {
        Extension una = ExtensionDelServicio.de(70, List.of(new Parada(-0.88, 41.65, 3)));
        assertThat(una).isEqualTo(new Extension(70, -0.88, 41.65, -0.88, 41.65));
        assertThat(NucleosController.zoom(una)).isBetween(4.0, 14.0);

        Extension sinPasos = ExtensionDelServicio.de(70, List.of(new Parada(-1, 41, 0), new Parada(1, 42, 0)));
        assertThat(sinPasos).isEqualTo(new Extension(70, -1, 41, 1, 42));
    }
}
