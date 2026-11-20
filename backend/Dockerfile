# syntax=docker/dockerfile:1
# TrainWatch · imagen del backend (fase 7). Se construye desde la RAÍZ del repo, porque el pom.xml copia los
# ejemplos de docs/api/ al classpath:
#   docker build -f backend/Dockerfile -t trainwatch-backend .
# Tres etapas: compilar con el JDK, separar el JAR en capas y ejecutar con solo el JRE, sin root.

# 1 · Compilar (las pruebas ya pasan en la CI antes de construir la imagen).
FROM eclipse-temurin:25-jdk-noble AS compilacion
WORKDIR /fuente
COPY backend/mvnw backend/pom.xml backend/
COPY backend/.mvn backend/.mvn
# Primero las dependencias, en una capa que solo cambia si cambia el pom.xml.
RUN --mount=type=cache,target=/root/.m2 cd backend && ./mvnw -B -ntp -q dependency:go-offline
COPY docs/api docs/api
COPY backend/src backend/src
RUN --mount=type=cache,target=/root/.m2 cd backend && ./mvnw -B -ntp -q package -Dmaven.test.skip=true \
    && cp target/trainwatch-backend-*.jar /fuente/app.jar

# 2 · Capas de Spring Boot: dependencias, cargador y código propio, para que un cambio en el código solo
#     suba la última capa (unos cientos de KB) y no los ~80 MB de dependencias.
FROM eclipse-temurin:25-jdk-noble AS capas
WORKDIR /capas
COPY --from=compilacion /fuente/app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extraido

# 3 · Ejecución: solo el JRE 25, un usuario sin privilegios y la memoria ajustada a la máquina (ADR 0017).
FROM eclipse-temurin:25-jre-noble
LABEL org.opencontainers.image.source="https://github.com/IvanChesa/trainwatch" \
      org.opencontainers.image.description="TrainWatch · backend (Spring Boot 4.2, Java 25)" \
      org.opencontainers.image.licenses="MIT"
RUN groupadd --system --gid 10001 trainwatch \
    && useradd --system --uid 10001 --gid trainwatch --no-create-home --shell /usr/sbin/nologin trainwatch
WORKDIR /app
COPY --from=capas --chown=trainwatch:trainwatch /capas/extraido/dependencies/ ./
COPY --from=capas --chown=trainwatch:trainwatch /capas/extraido/spring-boot-loader/ ./
COPY --from=capas --chown=trainwatch:trainwatch /capas/extraido/snapshot-dependencies/ ./
COPY --from=capas --chown=trainwatch:trainwatch /capas/extraido/application/ ./
USER trainwatch:trainwatch
# Memoria: el heap es un porcentaje del límite del contenedor (mem_limit en el compose), no de la máquina.
# Serial GC: con 1-2 vCPU y un heap pequeño es el que menos memoria y CPU gasta. Si se acaba la memoria,
# el proceso termina y Docker lo reinicia, en vez de quedarse medio vivo.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -XX:ReservedCodeCacheSize=64m -Xss512k" \
    SPRING_PROFILES_ACTIVE=prod
# 8080: la API (detrás de Caddy). 8081: Actuator, que no se publica (solo para la máquina).
EXPOSE 8080 8081
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
