# ADR 0018 · HTTPS de punta a punta: Cloudflare en «Full (strict)», certificado de origen, Authenticated Origin Pulls y Caddy

**Fecha:** 20-11-2026 · **Estado:** aceptada · **Fase:** 7

## Contexto

La API y el directo van en `trainwatch-api.ivanchesa.com`, detrás del proxy de Cloudflare (§5 del plan). El tramo navegador → Cloudflare ya es HTTPS con el certificado de Cloudflare; falta el tramo **Cloudflare → servidor**. En «Flexible» ese tramo va en claro, y en «Full» sin «strict» Cloudflare acepta cualquier certificado. El encargo pide **«Full (strict)»**: Cloudflare comprueba que el certificado del origen es válido y del nombre correcto.

Además, el servidor tiene IP pública y el directo es SSE, una respuesta que no termina nunca y que ningún proxy debe acumular.

## Decisión

1. **Certificado de origen de Cloudflare** (Origin CA), de 15 años, para `trainwatch-api.ivanchesa.com`. Lo pide Terraform (`cloudflare_origin_ca_certificate`) con una clave ECDSA P-256 generada allí mismo. La clave y el certificado van a **Key Vault**, y `desplegar.sh` los copia en la máquina (`/etc/trainwatch/tls`, solo root) en cada despliegue.
2. **Caddy 2.11.9** delante del backend, con ese certificado y sin certificados automáticos ni puerto 80 (`auto_https off`, `admin off`).
3. **Authenticated Origin Pulls solo para `trainwatch-api`** (por nombre de host): Terraform crea una **CA propia** y un certificado de cliente firmado por ella, sube el certificado a Cloudflare para ese nombre y guarda la CA en Key Vault. Cloudflare lo presenta al conectar con el origen y Caddy lo exige (`client_auth require_and_verify` contra esa CA). Quien llega a la IP sin pasar por Cloudflare, o desde otra cuenta de Cloudflare, no completa el TLS.
4. **Grupo de seguridad de Azure:** el 443 solo desde los rangos IPv4 de Cloudflare, que Terraform lee de su API (`data.cloudflare_ip_ranges`). Nada más entra, SSH tampoco.
5. **Caddy solo deja pasar lecturas a `/api/*`:** un `POST` (la importación manual del horario) devuelve 405 desde fuera; se hace desde la máquina por el 8080. Actuator está en el 8081, que no se publica.
6. **Cabeceras de seguridad** en Caddy: HSTS, `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Permissions-Policy` y una CSP `default-src 'none'` (la API solo devuelve JSON), más permisiva solo en `/api/docs` para que funcione Swagger UI. En la web, las mismas ideas en `frontend/public/_headers` (Cloudflare Pages).
7. **SSE a través de los dos proxies:**
   - el backend manda `Cache-Control: no-cache, no-transform` y `X-Accel-Buffering: no` en `/api/directo` (con prueba). `no-transform` evita que Cloudflare lo comprima, que es lo que lo haría acumularse;
   - Caddy, `flush_interval -1`: cada evento sale en cuanto llega;
   - Cloudflare corta una conexión sin datos a los **100 s**; el latido es de **15 s**, y cada conexión dura como mucho 30 min (ADR 0015).
8. **Nada de ajustes de toda la zona.** `ivanchesa.com` es compartida (la web principal, otros proyectos y el correo) y está en «Full» sin «strict». Terraform solo crea los registros `trainwatch` y `trainwatch-api` y ajustes por nombre de host. «Full (strict)» va en una **regla de configuración solo para `trainwatch-api`**, que se crea en la parte B (docs/despliegue.md, paso 5): una zona solo admite un conjunto de reglas de configuración, y si lo creara Terraform se adueñaría del de toda la zona.

## Alternativas

- **Caddy con Let's Encrypt:** el reto HTTP-01 o TLS-ALPN-01 necesita que Let's Encrypt llegue al servidor, y el grupo de seguridad solo deja pasar a Cloudflare. Con el reto DNS-01, la máquina guardaría un token de Cloudflare con permiso de editar el DNS, y hay que compilar Caddy con un plugin. El certificado de origen no caduca en 15 años y no da a la máquina ningún permiso sobre Cloudflare.
- **Cloudflare Tunnel (`cloudflared`):** sin IP pública ni puertos abiertos, y gratis. Es una alternativa muy buena y se queda apuntada. Se descarta ahora porque esconde la parte de red que el encargo pide defender (grupo de seguridad, HTTPS en el origen), y porque la máquina necesita salida a Internet igualmente (Renfe, GHCR) y la salida por defecto de Azure está retirada: la IP pública hace falta de todos modos.
- **TLS en Spring Boot** (sin Caddy): un proceso menos, pero el certificado de cliente, las cabeceras y el control de métodos quedarían mezclados con la aplicación. Caddy usa unos 15 MB.
- **Solo el grupo de seguridad, sin Authenticated Origin Pulls:** los rangos de Cloudflare los comparte todo el mundo que usa Cloudflare; alguien podría apuntar su propia zona a la IP. Con el certificado de cliente, no.
- **Authenticated Origin Pulls de zona, con el certificado compartido de Cloudflare:** es un interruptor y no hace falta ninguna CA, pero cambia toda la zona `ivanchesa.com`, y ese certificado lo presenta Cloudflare para *cualquier* zona: no distingue la nuestra de la de otro.

## Consecuencias

- La IP de la máquina no responde a nadie más que a Cloudflare: un escaneo ve el 443 cerrado (filtrado) y, aunque entrara, sin el certificado de cliente no hay TLS.
- Si Cloudflare publica rangos nuevos, hay que volver a aplicar Terraform (el plan lo enseña).
- El certificado de cliente caduca a los 5 años (Terraform lo renueva 30 días antes, en el siguiente `apply`), la CA a los 10 y el de origen a los 15.
- Las claves del certificado de origen y del de cliente están en el estado de Terraform (las genera `tls_private_key`). El estado vive en una cuenta sin claves compartidas, solo con Entra ID (ADR 0019).
- La fase 5 tiene que ampliar la CSP de la web con el proveedor de mapas.
