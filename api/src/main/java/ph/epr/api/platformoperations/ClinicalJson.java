package ph.epr.api.platformoperations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import ph.epr.api.identitytenancy.ClinicalAccessException;

/** Version 1 canonical encoding: sorted object keys, array order retained, UTF-8 SHA-256. */
public final class ClinicalJson {
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private ClinicalJson() {}
    public static String write(Object value) { return JSON.writeValueAsString(sort(JSON.convertValue(value, Object.class))); }
    public static <T> T read(String value, Class<T> type) {
        try { return JSON.readValue(value, type); }
        catch (RuntimeException e) { throw new ClinicalAccessException("INVALID_CLINICAL_PAYLOAD", 422); }
    }
    public static String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(write(value).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static Object sort(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((key, item) -> sorted.put(key.toString(), sort(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(ClinicalJson::sort).toList();
        return value;
    }
}
