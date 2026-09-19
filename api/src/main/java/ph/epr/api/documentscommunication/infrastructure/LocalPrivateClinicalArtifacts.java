package ph.epr.api.documentscommunication.infrastructure;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import ph.epr.api.documentscommunication.PrivateClinicalArtifacts;
import ph.epr.api.identitytenancy.ClinicalAccessException;
import ph.epr.api.platformoperations.TenantTransactions.Context;

/** Opt-in synthetic development adapter. Production private/versioned S3 remains a release gate. */
@Service
@Profile("local")
public class LocalPrivateClinicalArtifacts implements PrivateClinicalArtifacts {
    private final Path root;
    public LocalPrivateClinicalArtifacts(@Value("${epr.clinical.artifact-directory:${user.home}/.local/share/epr/synthetic-artifacts}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }
    public Artifact stage(Context c, UUID id, byte[] bytes) {
        var artifact=stageDetached(c.practiceId(),id,bytes);attach(c,artifact);return artifact;
    }
    public void attach(Context c,Artifact artifact) {
        artifactBoundary(c);
        c.jdbc().sql("INSERT INTO private_clinical_artifact(practice_id,artifact_id,sha256,byte_length,state) VALUES(:p,:id,:hash,:length,'STAGED') ON CONFLICT DO NOTHING")
            .param("p",c.practiceId()).param("id",artifact.artifactId()).param("hash",artifact.sha256()).param("length",artifact.byteLength()).update();
        readVerified(c,artifact.artifactId(),artifact.sha256());
    }
    public Artifact stageDetached(UUID practice, UUID id, byte[] bytes) {
        if (practice==null || id==null || bytes==null || bytes.length == 0 || bytes.length > 2_000_000) throw new ClinicalAccessException("ARTIFACT_SIZE_LIMIT",422);
        var hash = hash(bytes);
        try {
            var directory = directory(practice);
            var file = directory.resolve(id + ".html");
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(file) || !hash(Files.readAllBytes(file)).equals(hash)) throw new ClinicalAccessException("ARTIFACT_INTEGRITY_FAILURE", 503);
            } else {
                var temporary = Files.createTempFile(directory, ".staging-", ".partial", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    var buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                try {
                    try { Files.createLink(file, temporary); }
                    catch (FileAlreadyExistsException raced) { if (!hash(Files.readAllBytes(file)).equals(hash)) throw new ClinicalAccessException("ARTIFACT_INTEGRITY_FAILURE",503); }
                }
                finally { Files.deleteIfExists(temporary); }
                try (var directoryChannel = FileChannel.open(directory, StandardOpenOption.READ)) { directoryChannel.force(true); }
            }
            if (!hash(Files.readAllBytes(file)).equals(hash)) throw new ClinicalAccessException("ARTIFACT_INTEGRITY_FAILURE", 503);
            return new Artifact(id, hash, bytes.length);
        } catch (IOException e) { throw new ClinicalAccessException("ARTIFACT_STORAGE_UNAVAILABLE", 503); }
    }
    public byte[] readVerified(Context c, UUID id, String expectedHash) {
        var metadata = c.jdbc().sql("SELECT sha256, byte_length FROM private_clinical_artifact WHERE practice_id = :practice AND artifact_id = :id")
            .param("practice", c.practiceId()).param("id", id).query((rs, n) -> new Artifact(id, rs.getString(1), rs.getLong(2)))
            .optional().orElseThrow(() -> new ClinicalAccessException("ARTIFACT_UNAVAILABLE", 503));
        if (!metadata.sha256().equals(expectedHash)) throw new ClinicalAccessException("ARTIFACT_INTEGRITY_FAILURE", 503);
        try {
            var path = directory(c.practiceId()).resolve(id + ".html");
            if (Files.isSymbolicLink(path) || Files.size(path) != metadata.byteLength()) throw new ClinicalAccessException("ARTIFACT_INTEGRITY_FAILURE", 503);
            var bytes = Files.readAllBytes(path);
            if (!hash(bytes).equals(expectedHash)) throw new ClinicalAccessException("ARTIFACT_INTEGRITY_FAILURE", 503);
            return bytes;
        } catch (IOException e) { throw new ClinicalAccessException("ARTIFACT_UNAVAILABLE", 503); }
    }
    public void archive(Context c, UUID id, String hash) {
        readVerified(c, id, hash);
        c.jdbc().sql("UPDATE private_clinical_artifact SET state = 'ARCHIVED' WHERE practice_id = :practice AND artifact_id = :id AND sha256 = :hash")
            .param("practice", c.practiceId()).param("id", id).param("hash", hash).update();
    }
    private void artifactBoundary(Context c) {
        c.jdbc().sql("SELECT pg_advisory_xact_lock(hashtextextended(:k,0))").param("k",c.practiceId()+"/artifact-metadata").query(Object.class).single();
    }
    public int cleanupUnattached(Context c) {
        artifactBoundary(c);
        int removed=0;
        var cutoff=java.time.Instant.now().minus(java.time.Duration.ofDays(7));
        try(var files=Files.list(directory(c.practiceId()))) {
            for(var file:files.sorted().limit(1000).toList()) {
                if(removed>=100)break;
                if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!Files.getLastModifiedTime(file).toInstant().isBefore(cutoff))continue;
                var name=file.getFileName().toString();
                if(name.startsWith(".staging-")&&name.endsWith(".partial")){Files.deleteIfExists(file);removed++;continue;}
                if(!name.endsWith(".html"))continue;
                UUID id;try{id=UUID.fromString(name.substring(0,name.length()-5));}catch(IllegalArgumentException unrelated){continue;}
                var attached=c.jdbc().sql("SELECT EXISTS(SELECT 1 FROM private_clinical_artifact WHERE practice_id=:p AND artifact_id=:id)").param("p",c.practiceId()).param("id",id).query(Boolean.class).single();
                if(!attached){Files.deleteIfExists(file);removed++;}
            }
            return removed;
        }catch(IOException failure){throw new ClinicalAccessException("ARTIFACT_CLEANUP_UNAVAILABLE",503);}
    }
    private Path directory(UUID practice) throws IOException {
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("Unsafe artifact root");
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        var directory = root.resolve(practice.toString());
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IOException("Unsafe artifact directory");
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        return directory;
    }
    private String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
