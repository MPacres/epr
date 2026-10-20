package ph.epr.api.identitytenancy.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.epr.api.identitytenancy.ClinicalChartAuthorization;
import ph.epr.api.identitytenancy.ClinicalChartAuthorization.Action;
import ph.epr.api.identitytenancy.ClinicalChartAuthorization.SourceKind;

@Service
class JdbcClinicalChartAuthorization implements ClinicalChartAuthorization {
    private final JdbcClient jdbc;
    JdbcClinicalChartAuthorization(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Keep the control-plane RLS scope inside a control-plane transaction. */
    @Override
    @Transactional
    public void require(UUID actor, UUID practice, UUID site, SourceKind kind, Action action) {
        ClinicalChartAuthorization.super.require(actor, practice, site, kind, action);
    }

    @Override
    @Transactional
    public List<Capability> capabilities(UUID actor, UUID practice, UUID site) {
        jdbc.sql("SELECT set_config('epr.practice_id', :practice, true)")
            .param("practice", practice.toString()).query(String.class).single();
        return jdbc.sql("""
            SELECT grant_row.source_kind, grant_row.can_read, grant_row.can_propose,
                   grant_row.can_write, grant_row.can_review
            FROM practice_clinical_chart_grant grant_row
            JOIN practice_clinical_chart_capability capability
              USING (practice_id, source_kind)
            JOIN practice_clinician_site_grant site_grant
              USING (practice_id, user_id, site_id)
            JOIN practice_membership membership USING (practice_id, user_id)
            JOIN platform_user_account account ON account.id = grant_row.user_id
            JOIN practice_clinical_capability clinical USING (practice_id)
            JOIN practice_tenant_registry tenant USING (practice_id)
            WHERE grant_row.practice_id = :practice AND grant_row.user_id = :actor
              AND grant_row.site_id = :site AND capability.enabled
              AND site_grant.enabled AND membership.enabled AND account.enabled
              AND NOT account.password_reset_required
              AND account.role IN ('CLINICIAN', 'PRACTICE_STAFF')
              AND clinical.enabled AND clinical.synthetic_only
              AND tenant.provisioning_status = 'ACTIVE' AND tenant.service_status = 'ENABLED'
              AND tenant.schema_version = '15'
              AND EXISTS (
                  SELECT 1 FROM practice_role_assignment role
                  WHERE role.practice_id = grant_row.practice_id
                    AND role.user_id = grant_row.user_id
                    AND role.enabled AND role.practice_role = 'CLINICIAN'
              )
              AND NOT EXISTS (
                  SELECT 1 FROM clinical_schema_rollout rollout
                  WHERE rollout.practice_id = grant_row.practice_id AND rollout.state <> 'READY'
              )
            ORDER BY grant_row.source_kind
            """).param("practice", practice).param("actor", actor).param("site", site)
            .query((row, number) -> new Capability(SourceKind.valueOf(row.getString(1)),
                row.getBoolean(2), row.getBoolean(3), row.getBoolean(4), row.getBoolean(5))).list();
    }
}
