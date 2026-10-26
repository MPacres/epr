package ph.epr.api.identitytenancy.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.epr.api.identitytenancy.ClinicalDocumentIdentity;

@Service
class JdbcClinicalDocumentIdentity implements ClinicalDocumentIdentity {
    private final JdbcClient jdbc;

    JdbcClinicalDocumentIdentity(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> clinicianName(UUID practiceId, UUID userId) {
        jdbc.sql("SELECT set_config('epr.practice_id', :practice, true)")
            .param("practice", practiceId.toString()).query(String.class).single();
        return jdbc.sql("""
            SELECT u.display_name FROM platform_user_account u
            JOIN practice_membership m ON m.user_id = u.id
            WHERE m.practice_id = :practice AND u.id = :user
            """).param("practice", practiceId).param("user", userId)
            .query(String.class).optional();
    }
}
