package com.ivanchesa.trainwatch.horario;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamReader;

/**
 * Lee un fichero CSV de dentro del ZIP del GTFS en streaming: descomprime al vuelo, línea a línea, sin escribir
 * nada a disco ni cargar el fichero en memoria ({@code stop_times.txt} son 270 MB sin comprimir).
 *
 * <p>La ruta del ZIP la deja el paso de descarga en el contexto del trabajo ({@link ImportacionJobConfig#CLAVE_ZIP});
 * el lector la recoge en {@link #beforeStep}, que Spring Batch llama antes de {@link #open}. Las columnas se buscan
 * por el nombre de la cabecera, no por su posición, y se admiten el BOM y los campos entre comillas.
 */
public class LectorCsvZip implements ItemStreamReader<FilaCsv>, StepExecutionListener {

    private final String fichero;
    private final boolean opcional;

    private Path zip;
    private ZipFile zipFile;
    private BufferedReader lector;
    private Map<String, Integer> cabecera;
    private long linea;

    public LectorCsvZip(String fichero, boolean opcional) {
        this.fichero = fichero;
        this.opcional = opcional;
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        zip = Path.of(stepExecution.getJobExecution().getExecutionContext().getString(ImportacionJobConfig.CLAVE_ZIP));
    }

    @Override
    public void open(ExecutionContext executionContext) {
        try {
            zipFile = new ZipFile(zip.toFile(), StandardCharsets.UTF_8);
            ZipEntry entrada = zipFile.getEntry(fichero);
            if (entrada == null) {
                if (opcional) {
                    return;
                }
                throw new GtfsInvalidoException("El ZIP no trae " + fichero);
            }
            lector = new BufferedReader(
                    new InputStreamReader(zipFile.getInputStream(entrada), StandardCharsets.UTF_8), 1 << 16);
            String primera = lector.readLine();
            if (primera == null) {
                throw new GtfsInvalidoException(fichero + " está vacío");
            }
            if (primera.startsWith("﻿")) {
                primera = primera.substring(1);
            }
            String[] nombres = partir(primera);
            cabecera = new HashMap<>();
            for (int i = 0; i < nombres.length; i++) {
                cabecera.putIfAbsent(nombres[i].strip(), i);
            }
            linea = 1;
        } catch (IOException e) {
            throw new ItemStreamException("No se puede abrir " + fichero + " en " + zip, e);
        }
    }

    @Override
    public FilaCsv read() throws IOException {
        if (lector == null) {
            return null;
        }
        String l;
        while ((l = lector.readLine()) != null) {
            linea++;
            if (!l.isBlank()) {
                return new FilaCsv(fichero, linea, cabecera, partir(l));
            }
        }
        return null;
    }

    @Override
    public void close() {
        try {
            if (zipFile != null) {
                zipFile.close();
            }
        } catch (IOException e) {
            throw new ItemStreamException("No se puede cerrar " + zip, e);
        } finally {
            zipFile = null;
            lector = null;
        }
    }

    /** Parte una línea CSV por comas, respetando los campos entre comillas dobles ({@code ""} es una comilla). */
    static String[] partir(String linea) {
        if (linea.indexOf('"') < 0) {
            return linea.split(",", -1);
        }
        List<String> campos = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        boolean entreComillas = false;
        for (int i = 0; i < linea.length(); i++) {
            char c = linea.charAt(i);
            if (entreComillas) {
                if (c == '"' && i + 1 < linea.length() && linea.charAt(i + 1) == '"') {
                    actual.append('"');
                    i++;
                } else if (c == '"') {
                    entreComillas = false;
                } else {
                    actual.append(c);
                }
            } else if (c == '"') {
                entreComillas = true;
            } else if (c == ',') {
                campos.add(actual.toString());
                actual.setLength(0);
            } else {
                actual.append(c);
            }
        }
        campos.add(actual.toString());
        return campos.toArray(String[]::new);
    }
}
