package com.areascript.taxiapp.user.domain;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

// Validación de la URL de la foto de perfil.
//
// La app sube la foto a Firebase Storage (las reglas la dejan escribir solo en
// su propia ruta) y le manda la URL al servidor. El servidor no recibe el
// archivo, así que no puede confiar en que la URL sea legítima: sin esta
// comprobación un cliente podría guardar como "su foto" cualquier dirección
// (la de otra persona, un sitio externo que rastree a quien abre la app, etc.).
//
// Es válida solo si apunta al objeto que ES de esa persona:
//
//   https://firebasestorage.googleapis.com/v0/b/<bucket>/o/<carpeta>%2F<uid>.jpg?...
//
// Es una función pura: la carpeta y el bucket se los pasa quien llama.
public final class ProfilePhotoUrl {

    static final String HOST = "firebasestorage.googleapis.com";

    private ProfilePhotoUrl() {
    }

    // `bucket` es el nombre del bucket de Storage, o null/en blanco si no está
    // configurado (entonces solo se comprueban host, carpeta y uid).
    public static boolean belongsTo(String url, String bucket, String folder, String uid) {
        if (url == null || url.isBlank() || uid == null || uid.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !HOST.equalsIgnoreCase(uri.getHost())) {
            return false;
        }
        // Sin credenciales embebidas ni puerto raro.
        if (uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return false;
        }

        // /v0/b/<bucket>/o/<objeto codificado>
        String path = uri.getRawPath();
        String[] segments = path == null ? new String[0] : path.split("/", -1);
        if (segments.length != 6
                || !segments[0].isEmpty()
                || !"v0".equals(segments[1])
                || !"b".equals(segments[2])
                || !"o".equals(segments[4])) {
            return false;
        }
        if (bucket != null && !bucket.isBlank() && !bucket.equals(segments[3])) {
            return false;
        }

        String objectName = URLDecoder.decode(segments[5], StandardCharsets.UTF_8);
        return objectName.equals(folder + "/" + uid + ".jpg");
    }
}
