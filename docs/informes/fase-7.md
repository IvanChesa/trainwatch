# Fase 7 · Despliegue: informe

**Fecha:** 20-11-2026. **Rama:** `fase-7-despliegue`. **Resultado:** **parte A hecha; parte B pendiente** (necesita a Iván: inicios de sesión, tokens y su «sí» al gasto). La arquitectura elegida es una **máquina B2ats_v2 con el backend y Caddy en Docker Compose y PostgreSQL Flexible Server B1ms**, en `spaincentral`, detrás de Cloudflare: **~4,2 $/mes** con la oferta gratuita de Azure for Students (~43 $/mes sin ella). Todo está escrito como código (Terraform, compose, scripts y workflows) y comprobado en local; no se ha creado nada en Azure, Cloudflare ni GitHub.

## 1. Especificación

Punto de partida: `docs/prompts/fase-7.md`, la fase 7 del §6 del plan, el §5 (direcciones) y la decisión D2 (Azure for Students).

**Entra (parte A):**

1. **Arquitectura con cifras** y ADR: consumo medido en local (memoria y CPU del backend y de PostGIS, en marcha y en la importación del horario), al menos dos opciones comparadas, la oferta gratuita y los precios de `spaincentral` de la API pública, ≤ 8 $/mes de media y el disco.
2. **Terraform** en `infra/terraform/`: `azurerm` con versiones fijadas, variables y salidas; estado remoto en Azure Storage con el código para crearlo; red y grupo de seguridad (443 solo desde Cloudflare, sin SSH); identidad administrada; etiquetas `proyecto=trainwatch`; `fmt` y `validate` en la CI.
3. **Imagen y arranque:** `Dockerfile` multietapa sin root con JRE 25 y memoria ajustada; compose de producción; HTTPS «Full (strict)»; SSE sin búferes a través de Cloudflare; rotación de registros.
4. **Despliegue automático** con GitHub Actions: compilar, probar, imagen en GHCR y desplegar al fusionar en `main`; OIDC con identidad administrada federada, sin secretos de larga duración; la web a Cloudflare Pages (y una página provisional, porque la fase 5 no está).
5. **Copias:** automáticas con retención y un procedimiento de restauración probado en local con una copia real.
6. **Vigilancia gratis:** un workflow cada 15 min que falla si la API no responde o los datos tienen más de 5 min; Actuator no público; qué mirar cada día.
7. **Seguridad básica:** Actuator y gestión no públicos, cabeceras, límites de las fases 3b y 4 activos, secretos solo en Key Vault o en GitHub.
8. **Guía de la parte B** (`docs/despliegue.md`) con coste y desmontaje.

**No entra:** crear nada en Azure, Cloudflare o GitHub, `terraform apply`, push, ni la parte B. El hito de portfolio (encargo aparte).

**Aceptación de la parte A:**
- `./mvnw verify` y `npm test` en verde, con pruebas nuevas de lo que cambia en el código (perfil `prod`, cabeceras del SSE, estado de los datos en la web);
- `terraform fmt -check` y `terraform validate` en verde en las dos raíces;
- la imagen se construye y arranca con los límites de producción; Caddy, el compose y los scripts, comprobados en local;
- una copia real restaurada en local;
- revisión independiente, sobre todo de seguridad y coste.

La aceptación del plan (**7 días seguidos en marcha**) es de la parte B.

## 2. Qué se hizo

PENDIENTE

## 3. Cifras

PENDIENTE

## 4. Revisión independiente

PENDIENTE

## 5. Decisiones tomadas sin consultar

PENDIENTE

## 6. Parte B (pendiente)

PENDIENTE

## 7. Qué cambiaría del plan

PENDIENTE
