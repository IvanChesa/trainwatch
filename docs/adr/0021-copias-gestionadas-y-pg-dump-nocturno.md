# ADR 0021 · Copias: las gestionadas de PostgreSQL (14 días) y un `pg_dump` nocturno a Blob Storage (14 días)

**Fecha:** 20-11-2026 · **Estado:** aceptada · **Fase:** 7

## Contexto

`retrasos` es la base de las estadísticas (fase 6) y crece para siempre (~51.000 filas al día). Perderla es perder semanas de datos que no se pueden volver a pedir a Renfe. `posiciones` solo vive 48-72 h y el horario se vuelve a descargar cada noche. Además, el crédito de Azure caduca el 20-11-2027 y habrá que llevarse los datos a otra nube (D2).

## Decisión

1. **Copias gestionadas de PostgreSQL Flexible Server**, con **14 días** de restauración a cualquier instante (`backup_retention_days = 14`). Son gratis hasta el 100 % del disco (32 GB). Sirven para el error de hoy (un `DELETE` de más, una migración que sale mal): se restaura a un servidor nuevo en el instante de antes.
2. **`pg_dump` cada noche a Blob Storage**, para tener una copia **portable** que se puede restaurar en cualquier PostgreSQL con PostGIS (en local o en otra nube):
   - `trainwatch-copia.timer` a las **03:15 de Madrid**, antes de la importación del horario de las 04:30. `Persistent=true`: si la máquina estaba apagada, la hace al arrancar;
   - `copia.sh` usa el cliente de PostgreSQL de la misma versión (`postgres:18-alpine`, fijado por digest en `infra/produccion/versiones.env`), TLS con el certificado comprobado, formato propio (`-Fc`, comprimido), sin propietarios, y **sin los datos de `posiciones`** (se regeneran solas); su estructura sí va;
   - lo sube con la identidad administrada de la máquina, sin claves, con un **rol propio que solo puede escribir blobs, no borrarlos ni listarlos**, y solo en el contenedor `copias`: si alguien entra en la máquina (la única pieza expuesta a Internet), no puede borrar las copias, y lo que sobrescriba se recupera 7 días por el borrado temporal;
   - la cuenta de almacenamiento no admite claves compartidas, solo acepta la subred de la máquina y las IP de administración, y una regla de ciclo de vida **borra las copias a los 14 días**.
   - **Tamaño:** hoy, 15 MB (casi todo el horario). `retrasos` crece ~51.000 filas al día; en el formato comprimido de `pg_dump` serán unos cientos de MB a final de año (estimación, a afinar con datos reales). Catorce copias más siete en borrado temporal caben en los 5 GB gratis la mayor parte del año; al final, si se pasa, son céntimos (0,0184 $/GB al mes).
3. **Procedimiento de restauración escrito** (`docs/despliegue.md`, «Restaurar una copia») y **probado en local** con una copia real de la base de datos de la medición (docs/informes/fase-7.md, §3).

## Alternativas

- **Solo las gestionadas:** cubren el error de hoy, pero no se pueden descargar: solo se restauran en otro Flexible Server de Azure. No sirven para migrar ni para probar en local.
- **Copias a largo plazo (LTR) de Azure:** el nivel Burstable no las admite, y tampoco las copias bajo demanda.
- **Copia de seguridad de la máquina (Azure Backup):** la máquina no guarda datos (la base de datos está fuera) y Azure Backup cuesta por instancia.
- **`pg_dump` con `pg_dumpall` o volcado en SQL plano:** el formato propio es más pequeño y permite restaurar tablas sueltas y en paralelo.

## Consecuencias

- Hay dos niveles: «me he equivocado hace una hora» (gestionadas) y «necesito los datos fuera de Azure» (`pg_dump`).
- Con 30 días de retención (la primera versión), hacia el final del año las copias pasarían de los 5 GB gratis (revisión independiente): por eso, 14.
- Para restaurar en local hace falta la IP del portátil en `ips_administracion` y el rol `Storage Blob Data Reader` (Terraform se lo da a quien aplica).
- Si `copia.sh` falla, queda en el diario de la máquina (`journalctl -u trainwatch-copia`) y `revisar.sh` lo enseña. No avisa por correo: anotado como mejora.
