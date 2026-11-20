package com.ivanchesa.trainwatch.directo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Estado de la ingesta en directo: hora del último dato, trenes y cobertura por núcleo. */
@RestController
@RequestMapping("/api/directo")
public class DirectoController {

    private final IngestaDirecto ingesta;

    public DirectoController(IngestaDirecto ingesta) {
        this.ingesta = ingesta;
    }

    @GetMapping("/estado")
    public ResumenDirecto estado() {
        return ingesta.resumen();
    }
}
