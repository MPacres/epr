package ph.epr.api.identitytenancy.internal;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.epr.api.identitytenancy.*;

@Service
class JdbcClinicalAuthorization implements ClinicalAuthorization {
    private final JdbcClient jdbc;
    private final PracticeProvisioningRegistry registry;
    JdbcClinicalAuthorization(JdbcClient jdbc, PracticeProvisioningRegistry registry) {
        this.jdbc = jdbc; this.registry = registry;
    }

    @Override
    @Transactional
    public AuthorizedClinicalScope authorize(AuthenticatedActor actor, UUID practiceId, UUID siteId, boolean approval) {
        if (actor == null || practiceId == null || siteId == null) throw new ClinicalAccessException("CLINICAL_ACCESS_DENIED", 403);
        jdbc.sql("SELECT set_config('epr.practice_id', :practice, true)")
            .param("practice", practiceId.toString()).query(String.class).single();
        var grant = jdbc.sql("""
            SELECT u.id, g.reproductive_access
            FROM platform_user_account u
            JOIN practice_membership m ON m.user_id = u.id AND m.practice_id = :practice
            JOIN practice_role_assignment r ON r.user_id = m.user_id AND r.practice_id = m.practice_id
            JOIN practice_clinician_site_grant g ON g.user_id = m.user_id AND g.practice_id = m.practice_id
            JOIN practice_clinical_capability c ON c.practice_id = m.practice_id
            WHERE u.username = :username AND u.enabled AND NOT u.password_reset_required
                AND u.role IN ('CLINICIAN', 'PRACTICE_STAFF') AND m.enabled AND r.enabled AND r.practice_role = 'CLINICIAN'
                AND g.site_id = :site AND g.enabled AND c.enabled AND c.synthetic_only
                AND (NOT :approval OR g.template_approver)
                AND NOT EXISTS(SELECT 1 FROM clinical_schema_rollout rollout WHERE rollout.practice_id = m.practice_id AND rollout.state <> 'READY')
            """).param("practice", practiceId).param("site", siteId).param("username", actor.username())
            .param("approval", approval).query((rs, n) -> new Grant(rs.getObject(1, UUID.class), rs.getBoolean(2)))
            .optional().orElseThrow(() -> new ClinicalAccessException("CLINICAL_ACCESS_DENIED", 403));
        var tenant = registry.findByPracticeId(practiceId)
            .filter(PracticeTenant::isRoutable)
            .orElseThrow(() -> new ClinicalAccessException("TENANT_UNAVAILABLE", 503));
        return new AuthorizedClinicalScope(grant.id(), practiceId, siteId, grant.reproductive(), tenant);
    }
    @Override
    @Transactional
    public void authorizePatient(AuthenticatedActor actor, UUID practice, UUID site, PatientOperation operation) {
        var scope = authorize(actor, practice, site, false);
        var column = switch(operation) {
            case DIRECTORY_READ -> "patient_directory_read";
            case DETAIL_READ -> "patient_detail_read";
            case REGISTER -> "patient_register";
            case DEMOGRAPHIC_EDIT -> "patient_demographic_edit";
        };
        var allowed = jdbc.sql("SELECT " + column + " FROM practice_clinician_site_grant WHERE practice_id=:practice AND user_id=:actor AND site_id=:site AND enabled")
            .param("practice",practice).param("actor",scope.actorId()).param("site",site).query(Boolean.class).optional().orElse(false);
        if (!allowed) throw new ClinicalAccessException("PATIENT_OPERATION_DENIED",403);
    }
    private record Grant(UUID id, boolean reproductive) {}

    @Override
    @Transactional
    public java.util.List<ScopeSummary> available(AuthenticatedActor actor) {
        jdbc.sql("SELECT set_config('epr.platform_operations', 'true', true)").query(String.class).single();
        return jdbc.sql("""
            SELECT p.practice_id, g.site_id, p.display_name, u.display_name
            FROM platform_user_account u
            JOIN practice_membership m ON m.user_id = u.id
            JOIN practice_role_assignment r ON r.user_id = m.user_id AND r.practice_id = m.practice_id
            JOIN practice_clinician_site_grant g ON g.user_id = m.user_id AND g.practice_id = m.practice_id
            JOIN practice_clinical_capability c ON c.practice_id = m.practice_id
            JOIN practice_tenant_registry p ON p.practice_id = m.practice_id
            WHERE u.username = :username AND u.enabled AND NOT u.password_reset_required
                AND u.role IN ('CLINICIAN', 'PRACTICE_STAFF') AND m.enabled AND r.enabled AND r.practice_role = 'CLINICIAN'
                AND g.enabled AND c.enabled AND c.synthetic_only AND p.provisioning_status = 'ACTIVE'
                AND NOT EXISTS(SELECT 1 FROM clinical_schema_rollout rollout WHERE rollout.practice_id = p.practice_id AND rollout.state <> 'READY')
                AND p.service_status = 'ENABLED' AND p.schema_version = '11'
            ORDER BY p.display_name, g.site_id
            """).param("username", actor.username()).query((rs, n) -> new ScopeSummary(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4))).list();
    }
}
