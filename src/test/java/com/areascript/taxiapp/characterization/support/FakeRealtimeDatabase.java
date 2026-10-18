package com.areascript.taxiapp.characterization.support;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.InternalHelpers;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.database.snapshot.IndexedNode;
import com.google.firebase.database.snapshot.Node;
import com.google.firebase.database.snapshot.NodeUtilities;
import org.mockito.invocation.InvocationOnMock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

// Realtime Database en memoria para los tests de caracterización.
//
// No es un mock de cada llamada: guarda un árbol de datos y ejecuta los
// Transaction.Handler REALES del servidor contra MutableData/DataSnapshot
// reales del SDK (vía InternalHelpers, que el SDK expone como público). Así
// los tests ejercitan la lógica de negocio que vive dentro de doTransaction
// tal cual corre en producción, y siguen sirviendo cuando esa lógica se mueva
// de clase en el refactor: lo único que se simula es el borde con Firebase.
//
// Diferencias conocidas con el SDK real (no afectan a lo que se verifica):
// - Las transacciones se ejecutan una sola vez, sin la primera pasada con
//   datos nulos que hace el SDK sobre una referencia "fría".
// - ServerValue.TIMESTAMP se resuelve a FIXED_SERVER_TIME.
// - Los callbacks se invocan en el mismo hilo, de forma síncrona.
public final class FakeRealtimeDatabase {

    public static final long FIXED_SERVER_TIME = 1_760_000_000_000L;

    private final Map<String, Object> root = new LinkedHashMap<>();
    private final Map<String, DatabaseError> failingTransactions = new LinkedHashMap<>();

    public void install(FirebaseDatabase database) {
        doAnswer(invocation -> ref(invocation.getArgument(0))).when(database).getReference(anyString());
        doAnswer(invocation -> ref("")).when(database).getReference();
    }

    // ---- API para los tests ----

    public synchronized void put(String path, Object value) {
        write(segments(path), deepCopy(value));
    }

    public synchronized Object get(String path) {
        return deepCopy(read(segments(path)));
    }

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> getMap(String path) {
        Object value = get(path);
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    public synchronized void failTransactionsOn(String path, DatabaseError error) {
        failingTransactions.put(normalize(path), error);
    }

    // ---- Referencias simuladas ----

    private DatabaseReference ref(String rawPath) {
        String path = normalize(rawPath);
        return mock(DatabaseReference.class, invocation -> handle(path, invocation));
    }

    private Object handle(String path, InvocationOnMock invocation) throws Throwable {
        String method = invocation.getMethod().getName();
        Object[] args = invocation.getArguments();
        switch (method) {
            case "child":
                return ref(path.isEmpty() ? (String) args[0] : path + "/" + args[0]);
            case "getKey": {
                if (path.isEmpty()) {
                    return null;
                }
                List<String> segments = segments(path);
                return segments.get(segments.size() - 1);
            }
            case "getRef":
                return ref(path);
            case "runTransaction":
                runTransaction(path, (Transaction.Handler) args[0]);
                return null;
            case "setValue":
                return setValue(path, args);
            case "removeValue":
                return setValue(path, new Object[]{null, args.length > 0 ? args[args.length - 1] : null});
            case "addListenerForSingleValueEvent":
                ((ValueEventListener) args[0]).onDataChange(snapshot(path));
                return null;
            case "toString":
                return "FakeDatabaseReference(" + path + ")";
            default:
                throw new UnsupportedOperationException(
                        "FakeRealtimeDatabase no simula DatabaseReference." + method + " -- agregarlo si el servidor lo empieza a usar");
        }
    }

    private Object setValue(String path, Object[] args) {
        Object value = args.length > 0 ? args[0] : null;
        synchronized (this) {
            write(segments(path), resolveServerValues(toPlain(value)));
        }
        Object last = args.length > 0 ? args[args.length - 1] : null;
        if (last instanceof DatabaseReference.CompletionListener listener) {
            listener.onComplete(null, ref(path));
        }
        return null;
    }

    private void runTransaction(String path, Transaction.Handler handler) {
        DatabaseError forcedError;
        Node current;
        synchronized (this) {
            forcedError = failingTransactions.get(path);
            current = NodeUtilities.NodeFromJSON(read(segments(path)));
        }
        if (forcedError != null) {
            handler.onComplete(forcedError, false, snapshot(path));
            return;
        }

        MutableData mutableData = InternalHelpers.createMutableData(current);
        Transaction.Result result = handler.doTransaction(mutableData);
        boolean committed = result.isSuccess();
        if (committed) {
            synchronized (this) {
                write(segments(path), resolveServerValues(result.getNode().getValue()));
            }
        }
        handler.onComplete(null, committed, snapshot(path));
    }

    private DataSnapshot snapshot(String path) {
        Node node;
        synchronized (this) {
            node = NodeUtilities.NodeFromJSON(read(segments(path)));
        }
        return InternalHelpers.createDataSnapshot(ref(path), IndexedNode.from(node));
    }

    // ---- Árbol de datos ----

    @SuppressWarnings("unchecked")
    private Object read(List<String> segments) {
        Object current = root;
        for (String segment : segments) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<String, Object>) current).get(segment);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private void write(List<String> segments, Object value) {
        if (segments.isEmpty()) {
            root.clear();
            if (value instanceof Map) {
                root.putAll((Map<String, Object>) value);
            }
            return;
        }
        Map<String, Object> parent = root;
        List<Map<String, Object>> chain = new ArrayList<>();
        for (int i = 0; i < segments.size() - 1; i++) {
            chain.add(parent);
            Object next = parent.get(segments.get(i));
            if (!(next instanceof Map)) {
                if (value == null) {
                    return;
                }
                next = new LinkedHashMap<String, Object>();
                parent.put(segments.get(i), next);
            }
            parent = (Map<String, Object>) next;
        }
        String last = segments.get(segments.size() - 1);
        if (value == null) {
            parent.remove(last);
            // Igual que Firebase: un nodo que se queda sin hijos deja de existir.
            for (int i = chain.size() - 1; i >= 0; i--) {
                Map<String, Object> holder = chain.get(i);
                Object child = holder.get(segments.get(i));
                if (child instanceof Map && ((Map<?, ?>) child).isEmpty()) {
                    holder.remove(segments.get(i));
                }
            }
        } else {
            parent.put(last, value);
        }
    }

    // ServerValue.TIMESTAMP llega como {".sv": "timestamp"}.
    @SuppressWarnings("unchecked")
    private static Object resolveServerValues(Object value) {
        if (value instanceof Map<?, ?> map) {
            if (map.size() == 1 && "timestamp".equals(map.get(".sv"))) {
                return FIXED_SERVER_TIME;
            }
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object child = resolveServerValues(entry.getValue());
                if (child != null) {
                    resolved.put(entry.getKey().toString(), child);
                }
            }
            return resolved.isEmpty() ? null : resolved;
        }
        return value;
    }

    // Normaliza a los tipos que guarda Firebase (Map/List/String/Long/Double/
    // Boolean): pasa por NodeFromJSON igual que el SDK real.
    private static Object toPlain(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map && map.size() == 1 && map.containsKey(".sv")) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> plain = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object child = toPlain(entry.getValue());
                if (child != null) {
                    plain.put(entry.getKey().toString(), child);
                }
            }
            return plain.isEmpty() ? null : plain;
        }
        return NodeUtilities.NodeFromJSON(value).getValue();
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(entry.getKey().toString(), deepCopy(entry.getValue()));
            }
            return copy;
        }
        return value;
    }

    private static String normalize(String path) {
        String trimmed = path == null ? "" : path.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static List<String> segments(String path) {
        String normalized = normalize(path);
        return normalized.isEmpty() ? List.of() : List.of(normalized.split("/"));
    }
}
