import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prueba de carga del directo de TrainWatch (fase 4): N clientes SSE a la vez contra una aplicación en marcha.
 *
 * <p>Un programa de un solo fichero, sin dependencias ni herramientas nuevas (JDK 25):
 *
 * <pre>
 * java backend/carga/CargaSse.java [url] [clientes] [segundos] [núcleos]
 * java backend/carga/CargaSse.java http://localhost:8080 200 90 51,10,40,20,60
 * </pre>
 *
 * <p>Cada cliente es un hilo virtual que abre {@code GET /api/directo?nucleo=…} (los núcleos se reparten por turnos),
 * lee los eventos como un {@code EventSource} y apunta:
 * <ul>
 * <li>la <b>latencia</b> de cada evento {@code cambios}: desde su campo {@code generado} (el final del ciclo de la
 * ingesta que lo produce, con el reloj del servidor) hasta que llega al cliente. Servidor y clientes van en la misma
 * máquina, así que comparten reloj;</li>
 * <li>que las secuencias llegan seguidas (sin huecos) y que el primer evento es una instantánea;</li>
 * <li>bytes y eventos recibidos.</li>
 * </ul>
 * Con todos conectados, prueba una conexión de más: si el servidor tiene {@code maximo-conexiones} igual a N, tiene
 * que recibir un 503. Cada 5 s lee de {@code /actuator/metrics} (perfil {@code local}) la CPU del proceso, el heap y
 * los hilos del servidor.
 */
public class CargaSse {

    static final Pattern GENERADO = Pattern.compile("\"generado\":\"([^\"]+)\"");
    static final Pattern SECUENCIA = Pattern.compile("\"secuencia\":(\\d+)");
    static final Pattern VALOR = Pattern.compile("\"statistic\":\"VALUE\",\"value\":([0-9.eE+-]+)");

    static final ConcurrentLinkedQueue<Long> latencias = new ConcurrentLinkedQueue<>();
    static final AtomicLong bytes = new AtomicLong();
    static final AtomicInteger instantaneas = new AtomicInteger();
    static final AtomicInteger cambios = new AtomicInteger();
    static final AtomicInteger latidos = new AtomicInteger();
    static final AtomicInteger huecos = new AtomicInteger();
    static final AtomicInteger primeroNoInstantanea = new AtomicInteger();
    static final AtomicInteger errores = new AtomicInteger();
    static final AtomicInteger cortes = new AtomicInteger();
    static final AtomicInteger conectados = new AtomicInteger();
    static final AtomicLong instantaneaBytes = new AtomicLong();
    static final AtomicLong cambiosBytes = new AtomicLong();

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "http://localhost:8080";
        int clientes = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        int segundos = args.length > 2 ? Integer.parseInt(args[2]) : 90;
        String[] nucleos = (args.length > 3 ? args[3] : "51,10,40,20,60").split(",");

        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
        List<String> muestras = Collections.synchronizedList(new ArrayList<>());
        System.out.printf("Base del servidor: %s%n", metricas(http, url));

        long fin = System.nanoTime() + TimeUnit.SECONDS.toNanos(segundos);
        CountDownLatch todos = new CountDownLatch(clientes);
        List<Thread> hilos = new ArrayList<>();
        Instant inicio = Instant.now();
        for (int i = 0; i < clientes; i++) {
            String nucleo = nucleos[i % nucleos.length];
            hilos.add(Thread.ofVirtual().start(() -> cliente(http, url, nucleo, fin, todos)));
        }
        boolean llegaron = todos.await(30, TimeUnit.SECONDS);
        System.out.printf("%d clientes conectados en %d ms (todos: %s)%n", conectados.get(),
                Duration.between(inicio, Instant.now()).toMillis(), llegaron);

        // Una conexión de más: con maximo-conexiones = clientes, un 503.
        HttpResponse<String> sobra = http.send(HttpRequest.newBuilder(URI.create(url + "/api/directo?nucleo=" + nucleos[0]))
                .header("Accept", "text/event-stream").timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
        System.out.printf("Conexión %d: HTTP %d, Retry-After %s, %s%n", clientes + 1, sobra.statusCode(),
                sobra.headers().firstValue("Retry-After").orElse("-"),
                sobra.body().length() > 160 ? sobra.body().substring(0, 160) : sobra.body());

        Thread medidor = Thread.ofVirtual().start(() -> {
            while (System.nanoTime() < fin) {
                muestras.add(metricas(http, url));
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        for (Thread h : hilos) {
            h.join();
        }
        medidor.interrupt();
        medidor.join();

        List<Long> l = new ArrayList<>(latencias);
        Collections.sort(l);
        System.out.println();
        System.out.printf("Duración: %d s · clientes: %d · núcleos: %s%n", segundos, clientes, String.join(",", nucleos));
        System.out.printf("Eventos: %d instantáneas, %d cambios, %d latidos · %.1f MB recibidos%n", instantaneas.get(),
                cambios.get(), latidos.get(), bytes.get() / 1e6);
        System.out.printf("Tamaño medio: instantánea %.1f KB, cambios %.1f KB%n",
                instantaneas.get() == 0 ? 0 : instantaneaBytes.get() / 1024.0 / instantaneas.get(),
                cambios.get() == 0 ? 0 : cambiosBytes.get() / 1024.0 / cambios.get());
        System.out.printf("Correcto: primeros que no eran instantánea %d, huecos de secuencia %d, errores %d, cortes %d%n",
                primeroNoInstantanea.get(), huecos.get(), errores.get(), cortes.get());
        if (!l.isEmpty()) {
            System.out.printf("Latencia de «cambios» (ms): p50 %d · p95 %d · p99 %d · máx %d (n = %d)%n", p(l, 50),
                    p(l, 95), p(l, 99), l.getLast(), l.size());
        }
        System.out.println("Servidor cada 5 s:");
        muestras.forEach(m -> System.out.println("  " + m));
    }

    static void cliente(HttpClient http, String url, String nucleo, long fin, CountDownLatch todos) {
        String ultimoId = null;
        boolean contado = false;
        while (System.nanoTime() < fin) {
            HttpRequest.Builder peticion = HttpRequest.newBuilder(URI.create(url + "/api/directo?nucleo=" + nucleo))
                    .header("Accept", "text/event-stream");
            if (ultimoId != null) {
                peticion.header("Last-Event-ID", ultimoId);
            }
            try {
                HttpResponse<java.io.InputStream> r = http.send(peticion.build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (r.statusCode() != 200) {
                    errores.incrementAndGet();
                    r.body().close();
                    Thread.sleep(1000);
                    continue;
                }
                if (!contado) {
                    conectados.incrementAndGet();
                    todos.countDown();
                    contado = true;
                }
                try (BufferedReader in = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
                    String nombre = "message";
                    String id = null;
                    StringBuilder datos = new StringBuilder();
                    boolean primero = true;
                    long secuencia = -1;
                    String linea;
                    while (System.nanoTime() < fin && (linea = in.readLine()) != null) {
                        bytes.addAndGet(linea.length() + 1);
                        if (!linea.isEmpty()) {
                            int dos = linea.indexOf(':');
                            String campo = dos < 0 ? linea : linea.substring(0, dos);
                            String valor = dos < 0 ? "" : linea.substring(dos + 1).stripLeading();
                            switch (campo) {
                                case "event" -> nombre = valor;
                                case "id" -> id = valor;
                                case "data" -> datos.append(valor);
                                default -> {
                                }
                            }
                            continue;
                        }
                        long llegada = System.currentTimeMillis();
                        if (primero && !nombre.equals("instantanea")) {
                            primeroNoInstantanea.incrementAndGet();
                        }
                        primero = false;
                        switch (nombre) {
                            case "instantanea" -> {
                                instantaneas.incrementAndGet();
                                instantaneaBytes.addAndGet(datos.length());
                                secuencia = numero(SECUENCIA, datos);
                            }
                            case "cambios" -> {
                                cambios.incrementAndGet();
                                cambiosBytes.addAndGet(datos.length());
                                long s = numero(SECUENCIA, datos);
                                if (secuencia >= 0 && s != secuencia + 1) {
                                    huecos.incrementAndGet();
                                }
                                secuencia = s;
                                Matcher g = GENERADO.matcher(datos);
                                if (g.find()) {
                                    latencias.add(llegada - OffsetDateTime.parse(g.group(1)).toInstant().toEpochMilli());
                                }
                            }
                            case "latido" -> latidos.incrementAndGet();
                            default -> {
                            }
                        }
                        if (id != null) {
                            ultimoId = id;
                        }
                        nombre = "message";
                        id = null;
                        datos.setLength(0);
                    }
                }
                if (System.nanoTime() < fin) {
                    cortes.incrementAndGet(); // el servidor cerró (duración máxima o cliente lento): se reconecta
                }
            } catch (Exception e) {
                errores.incrementAndGet();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
        if (!contado) {
            todos.countDown();
        }
    }

    static long numero(Pattern p, CharSequence texto) {
        Matcher m = p.matcher(texto);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    static long p(List<Long> ordenadas, int percentil) {
        int i = (int) Math.ceil(percentil / 100.0 * ordenadas.size()) - 1;
        return ordenadas.get(Math.clamp(i, 0, ordenadas.size() - 1));
    }

    /** CPU del proceso, heap usado, hilos vivos y conexiones del directo, de /actuator/metrics. */
    static String metricas(HttpClient http, String url) {
        double cpu = metrica(http, url, "process.cpu.usage", null);
        double heap = metrica(http, url, "jvm.memory.used", "area:heap");
        double hilos = metrica(http, url, "jvm.threads.live", null);
        double conexiones = metrica(http, url, "trainwatch.api.directo.conexiones", null);
        return String.format(Locale.ROOT, "%tT · CPU del proceso %.1f %% · heap %.0f MB · hilos %.0f · conexiones %.0f",
                new java.util.Date(), cpu * 100, heap / 1e6, hilos, conexiones);
    }

    static double metrica(HttpClient http, String url, String nombre, String etiqueta) {
        try {
            String u = url + "/actuator/metrics/" + nombre + (etiqueta == null ? "" : "?tag=" + etiqueta);
            String cuerpo = http.send(HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            Matcher m = VALOR.matcher(cuerpo);
            return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}
