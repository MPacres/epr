package ph.epr.api.patientregistry.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Synthetic development adapter. Production requires managed keys, rotation and recovery evidence. */
@Component
class LocalPatientProtection {
    private final byte[] key;
    LocalPatientProtection(@Value("${epr.clinical.patient-encryption-key:}") String encoded) {
        key = encoded.isBlank() ? new byte[0] : Base64.getDecoder().decode(encoded);
        if (key.length != 0 && key.length != 32) throw new IllegalArgumentException("Patient key must contain 32 bytes");
    }
    String protect(UUID practice, UUID patient, String value) {
        try {
            var iv = new byte[12]; new SecureRandom().nextBytes(iv);
            var cipher = cipher(Cipher.ENCRYPT_MODE, iv, practice, patient);
            return Base64.getEncoder().encodeToString(iv) + "." + Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("Patient protection unavailable"); }
    }
    String reveal(UUID practice, UUID patient, String value) {
        try {
            var parts = value.split("\\.");
            return new String(cipher(Cipher.DECRYPT_MODE, Base64.getDecoder().decode(parts[0]), practice, patient)
                .doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("Patient protection unavailable"); }
    }
    private Cipher cipher(int mode, byte[] iv, UUID practice, UUID patient) throws Exception {
        if (key.length != 32) throw new IllegalStateException("Patient key unavailable");
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD((practice + "/" + patient).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
