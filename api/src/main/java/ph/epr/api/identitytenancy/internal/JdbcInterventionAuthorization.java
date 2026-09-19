package ph.epr.api.identitytenancy.internal;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.epr.api.identitytenancy.*;
@Service
class JdbcInterventionAuthorization implements InterventionAuthorization {
    private final JdbcClient jdbc;
    private final boolean newCommandsEnabled;
    JdbcInterventionAuthorization(JdbcClient jdbc,@org.springframework.beans.factory.annotation.Value("${epr.interventions.enabled:false}") boolean enabled) { this.jdbc=jdbc; this.newCommandsEnabled=enabled; }
    @Override @Transactional public void require(UUID actor,UUID practice,UUID site,String kind,Action action) {
        InterventionAuthorization.super.require(actor,practice,site,kind,action);
    }
    @Transactional public List<Capability> capabilities(UUID actor, UUID practice, UUID site) {
        jdbc.sql("SELECT set_config('epr.practice_id',:p,true)").param("p",practice.toString()).query(String.class).single();
        return jdbc.sql("""
            SELECT i.kind,i.can_read,i.can_prepare AND c.preparation_enabled AS preparation,
                i.can_authorize AND c.authorization_enabled AS authorization, i.can_authorize AS manage_existing
            FROM practice_intervention_grant i
            JOIN practice_intervention_capability c USING(practice_id,kind)
            JOIN practice_clinician_site_grant g USING(practice_id,user_id,site_id)
            JOIN practice_membership m USING(practice_id,user_id)
            JOIN platform_user_account u ON u.id=i.user_id
            JOIN practice_clinical_capability clinical ON clinical.practice_id=i.practice_id
            JOIN practice_tenant_registry tenant ON tenant.practice_id=i.practice_id
            WHERE i.practice_id=:p AND i.user_id=:a AND i.site_id=:s AND g.enabled AND m.enabled
                AND u.enabled AND NOT u.password_reset_required AND u.role IN ('CLINICIAN','PRACTICE_STAFF')
                AND clinical.enabled AND clinical.synthetic_only AND tenant.service_status='ENABLED'
                AND EXISTS(SELECT 1 FROM practice_role_assignment role WHERE role.practice_id=i.practice_id
                    AND role.user_id=i.user_id AND role.enabled AND role.practice_role='CLINICIAN')
            ORDER BY i.kind
            """).param("p",practice).param("a",actor).param("s",site)
            .query((rs,n)->new Capability(rs.getString(1),rs.getBoolean(2),newCommandsEnabled&&rs.getBoolean(3),newCommandsEnabled&&rs.getBoolean(4),rs.getBoolean(5),
                newCommandsEnabled&&rs.getBoolean(4)?null:"Synthetic authorization is not enabled for this action.")).list();
    }
    @Transactional public List<OwnerChoice> eligibleOwners(UUID actor,UUID practice,UUID site,String kind) {
        require(actor,practice,site,kind,Action.CONTINUE_EXISTING);
        var candidates=jdbc.sql("SELECT DISTINCT u.id,u.display_name FROM platform_user_account u JOIN practice_clinician_site_grant g ON g.user_id=u.id WHERE g.practice_id=:p AND g.site_id=:s AND g.enabled AND u.enabled")
            .param("p",practice).param("s",site).query((r,n)->new OwnerChoice(r.getObject(1,UUID.class),r.getString(2))).list();
        return candidates.stream().filter(x->!x.userId().equals(actor)&&capabilities(x.userId(),practice,site).stream().anyMatch(c->c.kind().equals(kind)&&c.read()&&c.manageExisting())).toList();
    }
}
