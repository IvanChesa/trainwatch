package com.ivanchesa.trainwatch.horario;

import java.io.StringReader;
import java.sql.Connection;
import java.util.List;

import javax.sql.DataSource;

import org.postgresql.PGConnection;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.jdbc.datasource.DataSourceUtils;

/**
 * Escribe cada lote con un único {@code COPY ... FROM STDIN} de PostgreSQL, la forma más rápida de cargar filas:
 * nada de un INSERT por fila. Usa la conexión de la transacción del lote, así que el lote entra entero o no entra.
 */
public class EscritorCopy implements ItemWriter<Object[]> {

    private final DataSource dataSource;
    private final String sql;
    private final int columnas;

    public EscritorCopy(DataSource dataSource, String esquema, String tabla, List<String> columnas) {
        this.dataSource = dataSource;
        this.columnas = columnas.size();
        this.sql = "COPY " + esquema + "." + tabla + " (" + String.join(", ", columnas) + ") FROM STDIN (FORMAT csv)";
    }

    @Override
    public void write(Chunk<? extends Object[]> lote) throws Exception {
        StringBuilder csv = new StringBuilder(lote.size() * 64);
        for (Object[] fila : lote) {
            if (fila.length != columnas) {
                throw new IllegalStateException("Fila con " + fila.length + " valores para " + columnas + " columnas");
            }
            for (int i = 0; i < fila.length; i++) {
                if (i > 0) {
                    csv.append(',');
                }
                Object v = fila[i];
                // En el CSV de COPY, un campo vacío sin comillas es NULL; todo lo demás va entre comillas.
                if (v != null) {
                    csv.append('"').append(v.toString().replace("\"", "\"\"")).append('"');
                }
            }
            csv.append('\n');
        }
        Connection conexion = DataSourceUtils.getConnection(dataSource);
        try {
            conexion.unwrap(PGConnection.class).getCopyAPI().copyIn(sql, new StringReader(csv.toString()));
        } finally {
            DataSourceUtils.releaseConnection(conexion, dataSource);
        }
    }
}
