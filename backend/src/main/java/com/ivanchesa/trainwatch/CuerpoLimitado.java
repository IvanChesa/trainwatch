package com.ivanchesa.trainwatch;

import java.io.IOException;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Un {@link BodyHandler} con tamaño máximo para el {@code HttpClient} de Java (fase 3b). Antes se miraba el tamaño
 * después de bajar el cuerpo entero, a memoria o a disco: una respuesta enorme (un error del servidor, un ataque)
 * se leía igual. Ahora:
 * <ul>
 * <li>si la respuesta trae {@code Content-Length} por encima del máximo, no se lee nada;</li>
 * <li>si no lo trae (o miente), se cuentan los bytes según llegan y se corta la lectura al pasar el máximo.</li>
 * </ul>
 * En los dos casos la petición falla con {@link CuerpoDemasiadoGrande}, que es una {@link IOException}.
 */
public final class CuerpoLimitado {

    /** El cuerpo pasaba del máximo. */
    public static class CuerpoDemasiadoGrande extends IOException {
        public CuerpoDemasiadoGrande(String mensaje) {
            super(mensaje);
        }
    }

    private CuerpoLimitado() {
    }

    /** Envuelve {@code base} con un máximo de {@code maximo} bytes. */
    public static <T> BodyHandler<T> de(BodyHandler<T> base, long maximo) {
        return info -> {
            OptionalLong longitud = info.headers().firstValueAsLong("Content-Length");
            if (longitud.isPresent() && longitud.getAsLong() > maximo) {
                return new Rechazo<>(new CuerpoDemasiadoGrande(
                        "Content-Length " + longitud.getAsLong() + " bytes, más del máximo (" + maximo + ")"));
            }
            return new Limitador<>(base.apply(info), maximo);
        };
    }

    /** No lee nada: cancela la suscripción y falla. */
    private static final class Rechazo<T> implements BodySubscriber<T> {
        private final CompletableFuture<T> cuerpo;

        Rechazo(IOException error) {
            this.cuerpo = CompletableFuture.failedFuture(error);
        }

        @Override
        public CompletionStage<T> getBody() {
            return cuerpo;
        }

        @Override
        public void onSubscribe(Flow.Subscription suscripcion) {
            suscripcion.cancel();
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            // nada: la suscripción ya está cancelada
        }

        @Override
        public void onError(Throwable error) {
            // el resultado ya es un fallo
        }

        @Override
        public void onComplete() {
            // el resultado ya es un fallo
        }
    }

    /** Pasa los bytes al suscriptor de verdad mientras no pasen del máximo; al pasarlo, corta. */
    private static final class Limitador<T> implements BodySubscriber<T> {
        private final BodySubscriber<T> destino;
        private final long maximo;
        private Flow.Subscription suscripcion;
        private long leidos;
        private boolean cortado;

        Limitador(BodySubscriber<T> destino, long maximo) {
            this.destino = destino;
            this.maximo = maximo;
        }

        @Override
        public CompletionStage<T> getBody() {
            return destino.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            this.suscripcion = s;
            destino.onSubscribe(s);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            if (cortado) {
                return;
            }
            for (ByteBuffer b : item) {
                leidos += b.remaining();
            }
            if (leidos > maximo) {
                cortado = true;
                suscripcion.cancel();
                destino.onError(new CuerpoDemasiadoGrande("Más de " + maximo + " bytes: se corta la lectura"));
                return;
            }
            destino.onNext(item);
        }

        @Override
        public void onError(Throwable error) {
            if (!cortado) {
                destino.onError(error);
            }
        }

        @Override
        public void onComplete() {
            if (!cortado) {
                destino.onComplete();
            }
        }
    }
}
