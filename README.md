# TrainWatch

Mapa con los trenes de Cercanías y Rodalies moviéndose en directo, con su retraso y estadísticas propias de puntualidad por línea, por hora y por día.

> **Estado:** en desarrollo. Ahora mismo solo contiene la prueba técnica (fase 0), que mide la calidad de los datos abiertos antes de construir la aplicación. El plan está en [`docs/PLAN.md`](docs/PLAN.md) y el informe de la prueba en [`docs/informes/fase-0.md`](docs/informes/fase-0.md).

**Proyecto personal, no oficial.** No tiene relación con Renfe ni con Adif.

## Stack previsto

Java 25 y Spring Boot · PostgreSQL con PostGIS · Angular con MapLibre · Docker · GitHub Actions · Terraform.

## La prueba técnica (`spike/`)

Programa Java sin Spring que lee los feeds GTFS Realtime con `gtfs-realtime-bindings` (MobilityData) y los cruza con el GTFS estático. Necesita JDK 25; Maven lo trae el wrapper.

```bash
cd spike
./mvnw package
# Graba los tres feeds cada 20 s durante 30 min en spike/datos/
java -cp "target/classes:target/lib/*" com.ivanchesa.trainwatch.spike.Captura datos 30 20
# Analiza la captura contra el GTFS estático (fomento_transit.zip, descargado aparte)
java -Xmx3g -cp "target/classes:target/lib/*" com.ivanchesa.trainwatch.spike.Analisis datos fomento_transit.zip resultados.md
```

En Windows, el separador del classpath es `;` en vez de `:`.

## Datos

Datos: Renfe (CC BY 4.0), de [data.renfe.com](https://data.renfe.com/): feeds GTFS Realtime de Cercanías (`gtfsrt.renfe.com`) y GTFS estático de Cercanías.

## Licencia

Código con licencia [MIT](LICENSE).
