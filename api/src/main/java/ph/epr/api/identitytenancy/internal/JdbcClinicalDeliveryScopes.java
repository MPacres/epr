package ph.epr.api.identitytenancy.internal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.simple.JdbcClient;
import ph.epr.api.identitytenancy.ClinicalDeliveryScopes;
@Service
class JdbcClinicalDeliveryScopes implements ClinicalDeliveryScopes {
 private final JdbcClient jdbc;JdbcClinicalDeliveryScopes(JdbcClient jdbc){this.jdbc=jdbc;}
 @Transactional public List<DeliveryScope> enabled(){
  jdbc.sql("SELECT set_config('epr.platform_operations','true',true)").query(String.class).single();
  return jdbc.sql("""
   SELECT DISTINCT u.username,g.practice_id,g.site_id FROM practice_clinician_site_grant g
   JOIN platform_user_account u ON u.id=g.user_id JOIN practice_membership m USING(practice_id,user_id)
   JOIN practice_clinical_capability c ON c.practice_id=g.practice_id
   JOIN practice_tenant_registry t ON t.practice_id=g.practice_id
   WHERE g.enabled AND m.enabled AND u.enabled AND NOT u.password_reset_required AND c.enabled AND c.synthetic_only
    AND t.service_status='ENABLED' AND t.provisioning_status='ACTIVE' AND t.schema_version=:schema
    AND EXISTS(SELECT 1 FROM practice_role_assignment role WHERE role.practice_id=g.practice_id AND role.user_id=g.user_id AND role.enabled AND role.practice_role='CLINICIAN')
    AND NOT EXISTS(SELECT 1 FROM clinical_schema_rollout rollout WHERE rollout.practice_id=g.practice_id AND rollout.state<>'READY')
   ORDER BY g.practice_id,g.site_id,u.username LIMIT 1000
   """).param("schema", ph.epr.api.identitytenancy.ClinicalReadiness.REQUIRED_SCHEMA_VERSION)
   .query((r,n)->new DeliveryScope(r.getString(1),r.getObject(2,UUID.class),r.getObject(3,UUID.class))).list();
 }
}
