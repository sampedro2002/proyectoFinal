package com.eatfood.control.web;

import com.eatfood.control.config.AppProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ayuda al generador de QR del panel a proponer la URL <b>correcta</b> por la que el backend
 * es alcanzable, cubriendo todos los escenarios de despliegue:
 * <ul>
 *   <li><b>URL pública configurada</b> ({@code app.public-url}) — autoritativa si se define.</li>
 *   <li><b>URL de la petición</b> — derivada del {@code Host}/{@code X-Forwarded-*}; refleja
 *       exactamente cómo el admin accede (ideal para dominio + proxy inverso + IP pública).</li>
 *   <li><b>IPs de LAN</b> — interfaces privadas del equipo (ideal para red local).</li>
 * </ul>
 * El frontend combina estas señales; el admin siempre puede escribir una URL a mano.
 */
@Tag(name = "Información del servidor")
@RestController
@RequestMapping("/api/server-info")
@RequiredArgsConstructor
public class ServerInfoController {

    @Value("${server.port:3000}")
    private int port;

    private final AppProperties props;

    @Operation(summary = "URLs candidatas por las que este servidor es alcanzable")
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> info(HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("port", port);

        // 1) URL pública configurada explícitamente (autoritativa).
        String configured = props.getPublicUrl();
        body.put("configuredUrl", configured != null && !configured.isBlank()
                ? stripTrailingSlash(configured.trim()) : null);

        // 2) URL con la que llegó esta petición (respeta proxy inverso vía X-Forwarded-*).
        body.put("requestUrl", buildRequestUrl(request));

        // 3) Direcciones IPv4 privadas de LAN del equipo.
        body.put("lanUrls", lanUrls());
        return body;
    }

    /** Reconstruye el origen (scheme://host[:puerto]) desde la petición y sus cabeceras de proxy. */
    private String buildRequestUrl(HttpServletRequest request) {
        String proto = firstHeaderValue(request.getHeader("X-Forwarded-Proto"));
        if (proto == null || proto.isBlank()) proto = request.getScheme();

        // El host puede venir del proxy (con puerto incluido si aplica) o del Host original.
        String host = firstHeaderValue(request.getHeader("X-Forwarded-Host"));
        if (host == null || host.isBlank()) host = request.getHeader("Host");

        if (host != null && !host.isBlank()) {
            // Si el host no trae puerto pero el proxy informa uno no estándar, se agrega.
            if (!host.contains(":")) {
                String fwdPort = firstHeaderValue(request.getHeader("X-Forwarded-Port"));
                if (fwdPort != null && !fwdPort.isBlank() && !isDefaultPort(proto, fwdPort)) {
                    host = host + ":" + fwdPort;
                }
            }
            return proto + "://" + host;
        }

        // Fallback: datos directos del contenedor de servlets.
        String name = request.getServerName();
        int p = request.getServerPort();
        return isDefaultPort(proto, String.valueOf(p))
                ? proto + "://" + name
                : proto + "://" + name + ":" + p;
    }

    // Nombres de adaptadores de red virtuales (hipervisores/VPN/contenedores) que
    // NetworkInterface#isVirtual() no detecta de forma fiable en Windows: los
    // adaptadores de VirtualBox, VMware, Hyper-V/WSL o Docker aparecen como
    // interfaces "normales" (up, no loopback, no isVirtual), y si la laptop
    // tiene alguna de estas herramientas instaladas su IP puede colarse antes
    // que la del Wi-Fi/Ethernet real, apuntando al admin a la máquina virtual
    // en vez de a la laptop física.
    private static final List<String> VIRTUAL_ADAPTER_HINTS = List.of(
            "virtualbox", "vmware", "hyper-v", "hyperv", "vethernet", "virtual switch",
            "wsl", "docker", "npcap", "tap-", "teredo", "loopback", "isatap", "ppp", "bluetooth"
    );

    private List<String> lanUrls() {
        List<String> urls = new ArrayList<>();

        // 1. Intentar descubrir la IP principal (LAN activa) usando el routing del SO,
        //    validando que la interfaz dueña de esa IP no sea un adaptador virtual.
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
            InetAddress primary = socket.getLocalAddress();
            String primaryIp = primary.getHostAddress();
            if (primaryIp != null && !primaryIp.startsWith("127.") && !primaryIp.startsWith("0.")
                    && !isVirtualAdapterAddress(primary)) {
                urls.add("http://" + primaryIp + ":" + port);
            }
        } catch (Exception ignored) {
            // Si falla (ej. sin internet), pasamos a enumerar interfaces.
        }

        // 2. Agregar también las demás IPs locales por si acaso, descartando
        //    adaptadores de hipervisor/VPN por nombre.
        try {
            var ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || looksVirtual(ni)) continue;
                var addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress() && !addr.isLoopbackAddress()) {
                        String url = "http://" + addr.getHostAddress() + ":" + port;
                        if (!urls.contains(url)) urls.add(url);
                    }
                }
            }
        } catch (Exception ignored) {
            // Sin interfaces enumerables: el admin escribe la URL a mano.
        }
        return urls;
    }

    private static boolean isVirtualAdapterAddress(InetAddress addr) {
        try {
            NetworkInterface ni = NetworkInterface.getByInetAddress(addr);
            return ni != null && looksVirtual(ni);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean looksVirtual(NetworkInterface ni) {
        String name = (ni.getName() + " " + ni.getDisplayName()).toLowerCase();
        for (String hint : VIRTUAL_ADAPTER_HINTS) {
            if (name.contains(hint)) return true;
        }
        return false;
    }

    private static String firstHeaderValue(String header) {
        if (header == null) return null;
        int comma = header.indexOf(',');
        return (comma >= 0 ? header.substring(0, comma) : header).trim();
    }

    private static boolean isDefaultPort(String proto, String port) {
        return ("https".equalsIgnoreCase(proto) && "443".equals(port))
                || ("http".equalsIgnoreCase(proto) && "80".equals(port));
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
