Eres la sesión de programación de **TrainWatch**, la fase 7 («Despliegue»). Tiene dos partes:
- **Parte A:** la haces **sola**, sin nadie al otro lado. No hagas preguntas: decide con criterio y deja apuntado lo que decidas.
- **Parte B:** se hace **con Iván delante**, porque necesita sus inicios de sesión. No la empieces hasta que él lo diga en esta conversación.

**Lee primero**, enteros:
- `CLAUDE.md`;
- `docs/PLAN.md`, sobre todo «Cambios tras la fase 0», §3, §5, §7, la fase 7 en §6 y la decisión D2 en §9;
- los informes de las fases 0 a 4 (y los de la 5 y la 6, si ya existen);
- los ADR.

Construye sobre lo fusionado en `main`. Si la fase 4 no está fusionada o `./mvnw verify` o `npm test` fallan, **para** y explica por qué en `docs/informes/fase-7.md`.

## Objetivo

Poner TrainWatch **en marcha en la nube, recogiendo datos las 24 horas**. Las estadísticas (fase 6) necesitan semanas de datos, y ahora solo se recogen mientras el portátil de Iván tiene la aplicación abierta.

Además, la infraestructura tiene que poder defenderse en una entrevista de Cloud: está escrita como código, se puede repetir y cuesta lo que se ha calculado.

El hito de portfolio (README final, vídeo, ficha en ivanchesa.com y publicación en LinkedIn) **no entra aquí**. Llegará en un encargo aparte, cuando estén el mapa (fase 5) y las estadísticas (fase 6).

## Lo que ya está decidido

- **Nube (D2):** **Azure for Students**, con la cuenta de UNIR de Iván. Son 100 $ de crédito sin tarjeta, que caducan el **20-11-2027**.
- **Regiones:** la suscripción solo deja crear recursos en algunas regiones (directiva «Allowed resource deployment regions»). Entre ellas está **`spaincentral`**, que es la que se usa.
- **Permisos:** Iván es **Owner** de la suscripción, pero el directorio de UNIR **no deja registrar aplicaciones** en Entra ID (comprobado el 20-11). Para que GitHub Actions entre en Azure por OIDC, usa una **identidad administrada asignada por el usuario con credencial federada** (`azurerm_user_assigned_identity` + `azurerm_federated_identity_credential`), con el rol mínimo sobre el grupo de recursos, no una aplicación registrada.
- **Presupuesto:** ya existe uno, creado a mano: `trainwatch-gasto-mensual`, de 10 $ al mes, con avisos al 50, 80 y 100 % al correo de Iván. Decide si se importa a Terraform o se deja fuera y se documenta.
- **Direcciones** (§5 del plan):
  - la web, en `trainwatch.ivanchesa.com` (Cloudflare Pages);
  - la API y el directo, en `trainwatch-api.ivanchesa.com`, detrás del proxy de Cloudflare.

  El DNS de `ivanchesa.com` está en Cloudflare, en la cuenta de Iván.
- **El código** está en GitHub, en `IvanChesa/trainwatch` (público). La sesión de control sube `main`; **tú no haces push**. Si falta la licencia MIT, añádela.

## Parte A · Sin nadie al otro lado

En esta parte **no se crea nada en Azure, Cloudflare ni GitHub**, ni se ejecuta `terraform apply`.

1. **Elegir la arquitectura, con cifras.** ADR obligatorio.
   - **Mide en local cuánto consume de verdad:** memoria y CPU del backend y de PostGIS con la ingesta en marcha, y el pico de la importación nocturna del horario. Usa `docker stats` y Actuator, y apunta las cifras.
   - **Compara al menos dos opciones:**
     - una máquina virtual con Docker Compose, con el backend y PostGIS juntos;
     - el backend en una máquina pequeña y la base de datos en **Azure Database for PostgreSQL – Flexible Server**, que admite PostGIS y trae copias de seguridad gestionadas.
   - **Comprueba las ofertas gratuitas** de Azure for Students: horas de máquina, de PostgreSQL y almacenamiento. Comprueba también los precios en `spaincentral` con la API pública de precios (`prices.azure.com/api/retail/prices`, sin clave). No te fíes de cifras de memoria.
   - **El gasto tiene que caber en el crédito:** como mucho, unos 8 $ al mes de media, para que los 100 $ duren el año. Si la opción buena no cabe, dilo con números y propón alternativas.
   - Ten en cuenta el disco: `posiciones` (de 0,3 a 0,5 M filas al día, 48-72 h) y `retrasos` (unas 51.000 filas al día, para siempre).
2. **Infraestructura como código** (`infra/terraform/`):
   - Terraform con el proveedor `azurerm`, versiones fijadas, variables y salidas;
   - el estado remoto en una cuenta de almacenamiento de Azure, con el código para crearla;
   - red y grupo de seguridad: el 443 solo desde los rangos de Cloudflare, y SSH cerrado o limitado a una IP de Iván, mejor sin SSH abierto;
   - identidad administrada si hace falta;
   - etiquetas `proyecto=trainwatch`.
   - Que `terraform fmt -check` y `terraform validate` pasen, y que la CI los ejecute.
3. **Imagen y arranque:**
   - `Dockerfile` del backend en varias etapas, con usuario sin privilegios, JRE 25 y memoria ajustada a la máquina elegida;
   - `compose` de producción, o lo que corresponda a la arquitectura elegida;
   - **HTTPS de verdad** entre Cloudflare y el servidor, en modo «Full (strict)»: certificado de origen de Cloudflare o Caddy con Let's Encrypt. Explícalo;
   - el **SSE** tiene que atravesar Cloudflare sin que se acumule en búferes: cabeceras correctas y un latido por debajo del tiempo de espera del proxy;
   - rotación de logs.
4. **Despliegue automático** (GitHub Actions):
   - compilar, probar, publicar la imagen en **GHCR** y desplegar al fusionar en `main`;
   - entrar en Azure con **OIDC** (la identidad administrada con credencial federada de arriba) y **sin secretos de larga duración** en el repo;
   - la web, a Cloudflare Pages: desde Actions con `wrangler pages deploy`, o con la integración de Pages y GitHub. Elige una y explícala.

   Si la fase 5 aún no está fusionada, la web publica una página provisional: proyecto no oficial, estado de los datos y la cita «Datos: Renfe (CC BY 4.0)».
5. **Copias de la base de datos:**
   - copias automáticas con retención: `pg_dump` nocturno a Blob Storage, o las gestionadas si eliges Flexible Server;
   - un **procedimiento de restauración escrito**, probado en local con una copia real.
6. **Vigilancia y alertas gratis:**
   - un workflow programado de GitHub Actions que cada 15 minutos consulta `/api/directo/estado` y falla si los datos tienen más de 5 minutos o la API no responde (GitHub avisa a Iván por correo), u otra opción gratuita equivalente;
   - métricas de Actuator que no queden públicas;
   - qué mirar cada día: las métricas de la ingesta de las fases 3 y 3b.
7. **Seguridad básica:**
   - Actuator y los endpoints de gestión no son públicos;
   - cabeceras de seguridad;
   - límites de las fases 4 y 3b activos en producción;
   - secretos solo en Azure Key Vault o en las variables de GitHub, nunca en el repo.
8. **Guía de la parte B** en `docs/despliegue.md`: los pasos exactos para Iván, en orden, con lo que tiene que hacer él (iniciar sesión en Azure, crear un token de Cloudflare con el mínimo de permisos) y lo que harás tú. Incluye el coste mensual calculado y cómo desmontarlo todo (`terraform destroy`).

Cierra la parte A con:
- commits en la rama `fase-7-despliegue`;
- el informe `docs/informes/fase-7.md`, con la parte A completa y la B pendiente;
- **revisión independiente** con un subagente sin contexto, sobre todo de seguridad y coste.

**No fusiones todavía:** se fusiona al acabar la parte B. Termina con un resumen corto y la lista de lo que necesitas de Iván.

## Parte B · Con Iván delante

Empieza solo cuando Iván lo diga. Sigue `docs/despliegue.md`.

1. **Iván:**
   - inicia sesión en Azure (`az login`); la de GitHub (`gh`) ya está iniciada;
   - crea el token de Cloudflare.

   Tú no escribes contraseñas ni códigos de verificación.
2. **`terraform plan`:** enséñale el plan y el **coste mensual calculado**, y espera su «sí» antes de `terraform apply`.
3. **DNS y Pages:**
   - DNS de `trainwatch-api` con proxy;
   - proyecto de Pages con el dominio `trainwatch`;
   - modo SSL «Full (strict)».
4. **Primer despliegue por la CI**, no a mano. Después, comprueba:
   - la importación del horario;
   - la ingesta (`/api/directo/estado`);
   - el SSE a través de Cloudflare desde fuera;
   - la web;
   - que la alerta salta si se para la ingesta. Pruébalo parándola un momento.
5. **La captura de 24 h**, que está pendiente desde la fase 3: al día siguiente, volúmenes reales, errores, huecos de la noche y si Bilbao y Cádiz emiten. Apúntalo en el informe.
6. **Aceptación del plan:** **7 días seguidos en marcha.** La parte B se puede cerrar antes con 24 h sin errores. Los 7 días los comprueba la sesión de control y se apuntan en el diario.

Cierra con:
- el informe completo;
- fusión `--no-ff`;
- línea en `DIARIO.md`;
- la guía de defensa `docs/defensa/fase-7.md`:
  - Terraform y el estado remoto;
  - OIDC frente a secretos;
  - Cloudflare delante del servidor;
  - SSE detrás de un proxy;
  - el cálculo del coste;
  - las copias y la restauración;
  - qué pasa cuando se acabe el crédito y cómo se migraría a otra nube.

## Cómo trabajar

- **El ciclo del `CLAUDE.md`:** rama, especificación en el informe, pruebas, informe, revisión independiente, fusión, diario y guía de defensa.
- **ADR:** arquitectura y tamaño; HTTPS y Cloudflare; despliegue y OIDC; copias.
- **Entorno:**
  - `JAVA_HOME` al JDK 25 (`C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot`) y Docker Desktop arrancado;
  - si falta Terraform o la CLI de Azure, instálalos con `winget`. Son gratuitos; apúntalo en el informe.
- **Límites:**
  - no toques nada fuera de la carpeta `trainwatch`;
  - **nada que gaste dinero o crédito sin el «sí» de Iván** en la parte B;
  - sin push: lo hace la sesión de control al revisar la fase;
  - no toques el repo de ivanchesa.com.
- **Presupuesto limitado.**
  - Si no llegas, este es el orden de prioridad: la arquitectura con cifras y su ADR; Terraform; Dockerfile y arranque con HTTPS; despliegue automático; copias; alertas; guía de la parte B.
  - Haz commits frecuentes.
- **Al terminar la parte A,** resumen de 10 líneas como máximo:
  - la arquitectura elegida y su coste mensual;
  - qué está listo;
  - pruebas;
  - lo que necesitas de Iván para la parte B.
