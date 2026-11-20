# ADR 0015 · El directo va por SSE: instantánea al conectarse y después solo los cambios

**Fecha:** 20-11-2026 · **Estado:** aceptada · **Fase:** 4 · **Lo pedía:** PLAN §3

## Contexto

El mapa (fase 5) tiene que ver moverse los trenes de un núcleo sin recargar. La ingesta pone al día el estado en memoria cada ~7 s (ADR 0008) y Renfe renueva sus feeds cada ~20 s. El navegador **solo recibe**: no manda nada al servidor mientras mira el mapa. La API irá detrás de Cloudflare (PLAN §5) en un servidor pequeño (Azure for Students, decisión D2).

## Decisión

1. **Server-Sent Events, no WebSocket.** `GET /api/directo?nucleo=51` devuelve `text/event-stream` (`SseEmitter` de Spring MVC):
   - el flujo va en un solo sentido, que es lo que hace falta;
   - es HTTP normal: pasa por Cloudflare y cualquier proxy sin configurar nada, con CORS de siempre y cacheando nada;
   - el navegador (`EventSource`) **se reconecta solo** y manda `Last-Event-ID`;
   - en el servidor no hace falta otra pila (STOMP, sesiones): un controlador de Spring MVC y una cola por cliente.
2. **Eventos:**
   - `instantanea`, el primero de cada conexión: todos los trenes del núcleo;
   - `cambios`, al final de cada ciclo de la ingesta en que algo cambie: `altas`, `cambios` (el tren entero) y `bajas` (ids);
   - `latido`, cada 15 s, con la antigüedad del último dato: mantiene viva la conexión (Cloudflare corta a los 100 s sin datos), descubre los clientes que se han ido y deja al mapa decir «datos de hace 6 min».
   - Las instantáneas y los cambios llevan su **secuencia** como `id`; el primero lleva `retry:` (3-6 s, con azar, para que no vuelvan todos a la vez tras un reinicio).
3. **Al reconectarse, una instantánea nueva**, no los cambios perdidos. Se lee `Last-Event-ID` (y se cuenta), pero no se reenvía un historial, porque:
   - **casi todos los trenes en marcha cambian en cada ciclo** (la posición estimada avanza con la hora): en la prueba de carga con los feeds reales, un evento `cambios` midió de media **18,7 KB** y una instantánea **30,9 KB** (el 60 %): con perderse dos eventos, reenviarlos ya ocupa más que una instantánea;
   - habría que guardar un historial por núcleo y decidir cuánto, y aun así tras un reinicio del servidor (secuencias nuevas) habría que mandar la instantánea;
   - la instantánea es idempotente: el mapa sustituye lo que tenga y no puede quedar desincronizado.
4. **Coherencia:** cada núcleo es un canal con su foto, su secuencia y sus suscriptores. Calcular los cambios, guardar la foto y encolarlos se hace con el cerrojo del canal; dar de alta a un suscriptor (encolar su instantánea y apuntarlo), también. Nadie recibe la instantánea *n* y luego otra vez los cambios de *n*, ni se salta los de *n+1*.
5. **Control de la carga:**
   - el hilo de la ingesta **no escribe nunca en un socket**: calcula los cambios una vez por núcleo, los **serializa una vez** y los deja en la cola de cada cliente sin bloquear;
   - cada conexión tiene **un hilo virtual** (Java 25) que vacía su cola: un cliente lento solo se retrasa a sí mismo. Las conexiones son asíncronas en Tomcat: no ocupan un hilo del servidor mientras esperan;
   - **cola acotada** (50 eventos, unos 6 min): si se llena, se desconecta al cliente, que vuelve con una instantánea nueva, en vez de acumular memoria;
   - **máximo de conexiones** (`trainwatch.api.directo.maximo-conexiones`, 500): la siguiente recibe un **503** con `Retry-After: 30` y un `ProblemDetail`;
   - **duración máxima** de 30 min por conexión: el navegador se reconecta solo; limpia conexiones medio muertas y, con varias instancias, reparte la carga;
   - al apagar, las conexiones se cierran antes del apagado ordenado del servidor web (si no, esperaría a peticiones que no terminan).

## Alternativas

- **WebSocket (STOMP sobre Spring):** bidireccional, que no hace falta; más piezas (broker simple o externo, sesiones), reconexión a mano en el navegador y algo más delicado detrás de proxies.
- **Sondeo (`GET` cada 7 s):** lo más sencillo, pero 200 clientes son ~30 peticiones por segundo con la foto entera cada vez, y la latencia es la del intervalo.
- **Reenviar los cambios perdidos con un historial por núcleo:** ver la decisión 3.
- **Mandar solo los campos que cambian de cada tren** (un *diff* por campo): menos bytes, pero el cliente tendría que fusionar y es fácil que se desincronice; el tren entero es idempotente.
- **Reactor/WebFlux:** el resto de la aplicación es Spring MVC y JDBC bloqueante; con hilos virtuales, un hilo por conexión es barato y el código se lee de arriba abajo.

## Consecuencias

- Con **una instancia** basta para el portfolio. Prueba de carga (informe de la fase 4): 200 clientes durante 90 s con los feeds reales, latencia p99 de 42 ms, heap entre 71 y 91 MB, CPU del proceso por debajo del 5 %, 0 errores y 0 huecos de secuencia; la conexión 201 recibió el 503.
- El límite real es el **ancho de banda**: unos 3 KB/s por cliente (≈ 0,6 MB/s con 200). Si hiciera falta, lo primero sería mandar solo los campos que cambian o comprimir. Para varias, cada instancia tendría que recibir los ciclos de la ingesta: o una ingesta por instancia (tres llamadas a Renfe por ciclo y por instancia) o, mejor, una sola ingesta que publique la foto en un bus (Redis pub/sub, PostgreSQL `LISTEN/NOTIFY`) y las instancias de la API solo difundan. Está en la guía de defensa.
- Un cliente que se pierde eventos (o se reconecta) recibe una instantánea: unos KB más, a cambio de no guardar historial.
- La secuencia es por núcleo y empieza en 0 al arrancar: no sirve como identificador global de una actualización.
