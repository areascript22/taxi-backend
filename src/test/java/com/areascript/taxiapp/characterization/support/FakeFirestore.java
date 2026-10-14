package com.areascript.taxiapp.characterization.support;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldPath;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.SetOptions;
import com.google.cloud.firestore.WriteResult;
import org.mockito.invocation.InvocationOnMock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

// Firestore en memoria para los tests de caracterización: un mapa
// "coleccion/doc[/subcoleccion/doc...]" -> datos.
//
// Solo simula las operaciones que hoy usa el servidor (get/set/update/delete
// de documentos, add en subcolecciones, whereEqualTo, orderBy(documentId) +
// limit + startAfter). Cualquier otra lanza UnsupportedOperationException
// para que un test nuevo no pase "por casualidad" contra un método sin simular.
public final class FakeFirestore {

    // TreeMap: el orden por path coincide con el orden por id de documento
    // dentro de una colección, que es lo que usa la paginación del panel admin.
    private final TreeMap<String, Map<String, Object>> documents = new TreeMap<>();
    private final Map<String, RuntimeException> failingReads = new LinkedHashMap<>();
    private final AtomicInteger autoIds = new AtomicInteger();

    public void install(Firestore firestore) {
        doAnswer(invocation -> collection(invocation.getArgument(0))).when(firestore).collection(anyString());
    }

    // ---- API para los tests ----

    public synchronized void put(String documentPath, Map<String, Object> data) {
        documents.put(documentPath, new LinkedHashMap<>(data));
    }

    public synchronized Map<String, Object> get(String documentPath) {
        Map<String, Object> data = documents.get(documentPath);
        return data == null ? null : new LinkedHashMap<>(data);
    }

    public synchronized boolean exists(String documentPath) {
        return documents.containsKey(documentPath);
    }

    // Borra un documento (no hace nada si no existe). Lo usa el arnés para que
    // borrar a una persona de la base de usuarios simulada también quite sus
    // documentos de perfil, como lo hace la cascada de Postgres.
    public synchronized void delete(String documentPath) {
        documents.remove(documentPath);
    }

    // Documentos directos de una colección (sin los de sus subcolecciones).
    public synchronized List<String> documentIds(String collectionPath) {
        List<String> ids = new ArrayList<>();
        String prefix = collectionPath + "/";
        for (String path : documents.keySet()) {
            if (path.startsWith(prefix) && !path.substring(prefix.length()).contains("/")) {
                ids.add(path.substring(prefix.length()));
            }
        }
        return ids;
    }

    public synchronized void failReadsOf(String documentPath, RuntimeException error) {
        failingReads.put(documentPath, error);
    }

    // ---- Colecciones / queries ----

    private CollectionReference collection(String path) {
        QueryState state = new QueryState(path, null, null, false, null, null);
        return mock(CollectionReference.class, invocation -> handleQuery(state, invocation));
    }

    private Query query(QueryState state) {
        return mock(Query.class, invocation -> handleQuery(state, invocation));
    }

    private Object handleQuery(QueryState state, InvocationOnMock invocation) {
        String method = invocation.getMethod().getName();
        Object[] args = invocation.getArguments();
        switch (method) {
            case "document":
                return document(state.collectionPath + "/" + args[0]);
            case "whereEqualTo":
                if (!(args[0] instanceof String field)) {
                    throw new UnsupportedOperationException("whereEqualTo solo se simula con un nombre de campo String");
                }
                return query(state.withFilter(field, args[1]));
            case "orderBy":
                if (!FieldPath.documentId().equals(args[0])) {
                    throw new UnsupportedOperationException("orderBy solo se simula con FieldPath.documentId()");
                }
                return query(state.orderedById());
            case "limit":
                return query(state.withLimit((Integer) args[0]));
            case "startAfter": {
                Object cursor = args[0] instanceof Object[] varargs ? varargs[0] : args[0];
                return query(state.withStartAfter(cursor.toString()));
            }
            case "get":
                return ApiFutures.immediateFuture(runQuery(state));
            case "add":
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) args[0];
                String id = "auto-" + autoIds.incrementAndGet();
                String docPath = state.collectionPath + "/" + id;
                synchronized (this) {
                    documents.put(docPath, new LinkedHashMap<>(data));
                }
                return ApiFutures.immediateFuture(document(docPath));
            case "getId": {
                String path = state.collectionPath;
                return path.substring(path.lastIndexOf('/') + 1);
            }
            case "getPath":
                return state.collectionPath;
            case "toString":
                return "FakeQuery(" + state.collectionPath + ")";
            default:
                throw new UnsupportedOperationException(
                        "FakeFirestore no simula Query/CollectionReference." + method + " -- agregarlo si el servidor lo empieza a usar");
        }
    }

    private QuerySnapshot runQuery(QueryState state) {
        List<QueryDocumentSnapshot> results = new ArrayList<>();
        synchronized (this) {
            for (String id : documentIds(state.collectionPath)) {
                if (state.startAfter != null && id.compareTo(state.startAfter) <= 0) {
                    continue;
                }
                Map<String, Object> data = documents.get(state.collectionPath + "/" + id);
                if (state.filterField != null && !Objects.equals(data.get(state.filterField), state.filterValue)) {
                    continue;
                }
                results.add(snapshot(state.collectionPath + "/" + id, data, QueryDocumentSnapshot.class));
                if (state.limit != null && results.size() >= state.limit) {
                    break;
                }
            }
        }
        List<QueryDocumentSnapshot> frozen = List.copyOf(results);
        return mock(QuerySnapshot.class, invocation -> switch (invocation.getMethod().getName()) {
            case "getDocuments" -> frozen;
            case "size" -> frozen.size();
            case "isEmpty" -> frozen.isEmpty();
            case "iterator" -> frozen.iterator();
            default -> throw new UnsupportedOperationException("FakeFirestore no simula QuerySnapshot." + invocation.getMethod().getName());
        });
    }

    // ---- Documentos ----

    private DocumentReference document(String path) {
        return mock(DocumentReference.class, invocation -> handleDocument(path, invocation));
    }

    @SuppressWarnings("unchecked")
    private Object handleDocument(String path, InvocationOnMock invocation) {
        String method = invocation.getMethod().getName();
        Object[] args = invocation.getArguments();
        switch (method) {
            case "get": {
                RuntimeException failure;
                Map<String, Object> data;
                synchronized (this) {
                    failure = failingReads.get(path);
                    data = documents.get(path);
                }
                if (failure != null) {
                    return ApiFutures.immediateFailedFuture(failure);
                }
                return ApiFutures.immediateFuture(snapshot(path, data, DocumentSnapshot.class));
            }
            case "set": {
                Map<String, Object> data = (Map<String, Object>) args[0];
                boolean merge = args.length > 1 && args[1] instanceof SetOptions;
                synchronized (this) {
                    Map<String, Object> target = merge && documents.containsKey(path)
                            ? documents.get(path)
                            : new LinkedHashMap<>();
                    applyUpdates(target, data);
                    documents.put(path, target);
                }
                return writeResult();
            }
            case "update": {
                Map<String, Object> updates = new LinkedHashMap<>();
                if (args[0] instanceof Map<?, ?> map) {
                    updates.putAll((Map<String, Object>) map);
                } else {
                    updates.put((String) args[0], args[1]);
                    if (args.length > 2 && args[2] instanceof Object[] more) {
                        for (int i = 0; i + 1 < more.length; i += 2) {
                            updates.put((String) more[i], more[i + 1]);
                        }
                    }
                }
                synchronized (this) {
                    Map<String, Object> target = documents.get(path);
                    if (target == null) {
                        // Igual que Firestore: update sobre un documento inexistente falla.
                        return ApiFutures.immediateFailedFuture(new IllegalStateException("NOT_FOUND: " + path));
                    }
                    applyUpdates(target, updates);
                }
                return writeResult();
            }
            case "delete":
                synchronized (this) {
                    documents.remove(path);
                }
                return writeResult();
            case "collection":
                return collection(path + "/" + args[0]);
            case "getId":
                return path.substring(path.lastIndexOf('/') + 1);
            case "getPath":
                return path;
            case "toString":
                return "FakeDocumentReference(" + path + ")";
            default:
                throw new UnsupportedOperationException(
                        "FakeFirestore no simula DocumentReference." + method + " -- agregarlo si el servidor lo empieza a usar");
        }
    }

    private static void applyUpdates(Map<String, Object> target, Map<String, Object> updates) {
        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            if (isDeleteSentinel(entry.getValue())) {
                target.remove(entry.getKey());
            } else {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private static boolean isDeleteSentinel(Object value) {
        return value instanceof FieldValue && value.equals(FieldValue.delete());
    }

    private <T extends DocumentSnapshot> T snapshot(String path, Map<String, Object> rawData, Class<T> type) {
        Map<String, Object> data = rawData == null ? null : new LinkedHashMap<>(rawData);
        String id = path.substring(path.lastIndexOf('/') + 1);
        return mock(type, invocation -> switch (invocation.getMethod().getName()) {
            case "exists" -> data != null;
            case "getData" -> data == null ? null : new LinkedHashMap<>(data);
            case "getId" -> id;
            case "getReference" -> document(path);
            case "getString" -> data == null ? null : (String) data.get((String) invocation.getArgument(0));
            case "get" -> data == null ? null : data.get(invocation.getArgument(0).toString());
            case "contains" -> data != null && data.containsKey(invocation.getArgument(0).toString());
            case "toString" -> "FakeDocumentSnapshot(" + path + ")";
            default -> throw new UnsupportedOperationException(
                    "FakeFirestore no simula DocumentSnapshot." + invocation.getMethod().getName());
        });
    }

    private static ApiFuture<WriteResult> writeResult() {
        return ApiFutures.immediateFuture(mock(WriteResult.class));
    }

    private record QueryState(
            String collectionPath,
            String filterField,
            Object filterValue,
            boolean orderById,
            Integer limit,
            String startAfter
    ) {
        QueryState withFilter(String field, Object value) {
            return new QueryState(collectionPath, field, value, orderById, limit, startAfter);
        }

        QueryState orderedById() {
            return new QueryState(collectionPath, filterField, filterValue, true, limit, startAfter);
        }

        QueryState withLimit(int newLimit) {
            return new QueryState(collectionPath, filterField, filterValue, orderById, newLimit, startAfter);
        }

        QueryState withStartAfter(String cursor) {
            return new QueryState(collectionPath, filterField, filterValue, orderById, limit, cursor);
        }
    }
}
