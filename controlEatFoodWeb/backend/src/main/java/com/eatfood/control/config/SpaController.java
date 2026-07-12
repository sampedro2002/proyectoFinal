package com.eatfood.control.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwarding de rutas SPA a index.html para soportar History API (F5 en sub-rutas).
 *
 * Sin este controlador, al recargar en una ruta profunda como /restaurants, el
 * navegador envia GET /restaurants al backend, no encuentra el recurso estatico
 * y Spring devuelve 404 (o 401 si la ruta no esta marcada como publica en
 * SecurityConfig). Con el forward, Spring sirve siempre index.html y deja que
 * React Router (history API) decida que pagina mostrar segun la URL.
 *
 * Se enumeran explicitamente todas las rutas del SPA (ver App.jsx) en vez de
 * un catch-all, para no colisionar con /api/**, /v3/api-docs/**, /swagger-ui/**,
 * /actuator/** ni los recursos estaticos (/assets/**, favicon, manifest, sw...),
 * que ya son servidos por sus respectivos handlers o controladores.
 */
@Controller
public class SpaController {

    @GetMapping(value = {
            "/login", "/login/**",
            "/kiosk", "/kiosk/**",
            "/employees", "/employees/**",
            "/restaurants", "/restaurants/**",
            "/users", "/users/**",
            "/schedules", "/schedules/**",
            "/reports", "/reports/**",
            "/audit", "/audit/**",
            "/manual-scan", "/manual-scan/**",
            "/conexion", "/conexion/**"
    })
    public String forward() {
        return "forward:/index.html";
    }
}