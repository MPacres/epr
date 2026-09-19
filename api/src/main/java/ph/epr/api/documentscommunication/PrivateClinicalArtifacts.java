package ph.epr.api.documentscommunication;

import java.util.UUID;
import ph.epr.api.platformoperations.TenantTransactions.Context;

public interface PrivateClinicalArtifacts {
    Artifact stage(Context context, UUID artifactId, byte[] bytes);
    /** Filesystem/object staging after an authorized candidate has been captured; no database lock is held. */
    Artifact stageDetached(UUID practiceId, UUID artifactId, byte[] bytes);
    void attach(Context context, Artifact artifact);
    /** Bounded local cleanup; attached candidates and archives are always retained. */
    int cleanupUnattached(Context context);
    byte[] readVerified(Context context, UUID artifactId, String expectedHash);
    void archive(Context context, UUID artifactId, String expectedHash);
    static String sha256(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    record Artifact(UUID artifactId, String sha256, long byteLength) {}
}
