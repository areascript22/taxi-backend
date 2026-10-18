package com.areascript.taxiapp.appversion.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Versión de una app: la parte numérica "1.0.7" de lo que reporta el sistema.
// Se ignora todo lo que venga después (sufijo de flavor "-dev", build number
// "+19"): lo que decide si una app es demasiado vieja es la versión de
// marketing, igual que lo que se le muestra al usuario.
//
// Se compara numéricamente por segmentos ("1.10.0" es mayor que "1.9.0") y un
// segmento ausente cuenta como 0 ("1.0" == "1.0.0").
public record AppVersion(List<Integer> segments) implements Comparable<AppVersion> {

    private static final Pattern LEADING_NUMERIC = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)*)");

    // Vacío si no empieza con una versión numérica (texto vacío, basura, null).
    public static Optional<AppVersion> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Matcher matcher = LEADING_NUMERIC.matcher(raw);
        if (!matcher.find()) {
            return Optional.empty();
        }

        List<Integer> segments = new ArrayList<>();
        for (String part : matcher.group(1).split("\\.")) {
            try {
                segments.add(Integer.parseInt(part));
            } catch (NumberFormatException tooBig) {
                // Un segmento absurdamente largo no es una versión real.
                return Optional.empty();
            }
        }
        return Optional.of(new AppVersion(List.copyOf(segments)));
    }

    @Override
    public int compareTo(AppVersion other) {
        int length = Math.max(segments.size(), other.segments.size());
        for (int i = 0; i < length; i++) {
            int mine = i < segments.size() ? segments.get(i) : 0;
            int theirs = i < other.segments.size() ? other.segments.get(i) : 0;
            if (mine != theirs) {
                return Integer.compare(mine, theirs);
            }
        }
        return 0;
    }

    public boolean isOlderThan(AppVersion other) {
        return compareTo(other) < 0;
    }

    // "1.0.7"
    @Override
    public String toString() {
        return String.join(".", segments.stream().map(String::valueOf).toList());
    }
}
