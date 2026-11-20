-- Fase 3b · Paradas inferidas (ADR 0012).
--
-- Si entre dos muestras de trip_updates un tren pasa de ir a la parada i a ir a la i+2 o más, las paradas
-- intermedias no se vieron nunca como "próxima parada". Se cierran con el último retraso conocido y se marcan como
-- inferidas, para que las estadísticas puedan quitarlas o contarlas aparte. Lo mismo con las paradas que faltan
-- cuando un viaje que había desaparecido a mitad vuelve más adelante.
-- En una tabla particionada, ADD COLUMN llega a todas las particiones; con DEFAULT constante no reescribe nada.
ALTER TABLE retrasos ADD COLUMN inferido BOOLEAN NOT NULL DEFAULT false;
