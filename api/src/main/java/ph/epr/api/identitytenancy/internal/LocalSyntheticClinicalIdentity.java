package ph.epr.api.identitytenancy.internal;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.epr.api.identitytenancy.SyntheticClinicalIdentity;

@Service
@Profile("local")
@ConditionalOnProperty(name = "epr.clinical.demo-seed", havingValue = "true")
class LocalSyntheticClinicalIdentity implements SyntheticClinicalIdentity {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final String clinicianName;
    @org.springframework.beans.factory.annotation.Value("${epr.interventions.enabled:false}") private boolean interventionsEnabled;
    LocalSyntheticClinicalIdentity(JdbcClient jdbc, PasswordEncoder passwords,
        @org.springframework.beans.factory.annotation.Value("${epr.clinical.demo-clinician-name:Dr. Isabel Reyes}") String clinicianName) {
        this.jdbc = jdbc; this.passwords = passwords; this.clinicianName = clinicianName;
    }
    @Transactional public void prepare(UUID userId, String username, String password) {
        jdbc.sql("""
            INSERT INTO platform_user_account(id, username, display_name, password_hash, role, enabled, created_at, updated_at)
            VALUES (:id, :username, :displayName, :password, 'CLINICIAN', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (id) DO NOTHING
            """).param("id", userId).param("username", username).param("displayName", clinicianName).param("password", passwords.encode(password)).update();
        if (!jdbc.sql("SELECT EXISTS(SELECT 1 FROM platform_user_account WHERE id = :id AND username = :username AND role = 'CLINICIAN')")
            .param("id", userId).param("username", username).query(Boolean.class).single()) throw new IllegalStateException("Synthetic identity collision");
    }
    @Transactional public void grant(UUID user, UUID practice, UUID site, UUID grantingUser) {
        jdbc.sql("SELECT set_config('epr.platform_operations', 'true', true)").query(String.class).single();
        jdbc.sql("""
            INSERT INTO practice_role_assignment(practice_id, user_id, practice_role, enabled, created_at, updated_at)
            VALUES (:practice, :user, 'CLINICIAN', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING
            """).param("practice", practice).param("user", user).update();
        jdbc.sql("INSERT INTO practice_clinical_capability(practice_id, enabled) VALUES (:practice, true) ON CONFLICT DO NOTHING")
            .param("practice", practice).update();
        jdbc.sql("""
            INSERT INTO practice_clinician_site_grant(practice_id, user_id, site_id, enabled, reproductive_access, template_approver, reason, granted_by, patient_directory_read, patient_detail_read, patient_register, patient_demographic_edit)
            VALUES (:practice, :user, :site, true, true, true, 'Opt-in local synthetic OB-GYN development fixture', :grantor, true, true, true, true) ON CONFLICT DO NOTHING
            """).param("practice", practice).param("user", user).param("site", site).param("grantor", grantingUser).update();
        if(interventionsEnabled) for(var kind:java.util.List.of("PRESCRIPTION","LAB_ORDER","IMAGING_ORDER","REFERRAL","FOLLOW_UP")) {
            jdbc.sql("INSERT INTO practice_intervention_capability(practice_id,kind,preparation_enabled,authorization_enabled) VALUES(:p,:k,true,true) ON CONFLICT DO NOTHING").param("p",practice).param("k",kind).update();
            jdbc.sql("INSERT INTO practice_intervention_grant(practice_id,user_id,site_id,kind,can_read,can_prepare,can_authorize,reason) VALUES(:p,:u,:s,:k,true,true,true,'Explicit opt-in synthetic intervention fixture') ON CONFLICT DO NOTHING").param("p",practice).param("u",user).param("s",site).param("k",kind).update();
        }
        for (var kind : java.util.List.of("ALLERGY", "MEDICATION_USE", "PROBLEM")) {
            jdbc.sql("INSERT INTO practice_clinical_chart_capability(practice_id,source_kind,enabled) VALUES(:p,:k,true) ON CONFLICT (practice_id,source_kind) DO UPDATE SET enabled=true")
                .param("p",practice).param("k",kind).update();
            jdbc.sql("""
                INSERT INTO practice_clinical_chart_grant(practice_id,user_id,site_id,source_kind,can_read,can_propose,can_write,can_review,reason)
                VALUES(:p,:u,:s,:k,true,true,true,true,'Explicit opt-in synthetic clinical-list fixture')
                ON CONFLICT (practice_id,user_id,site_id,source_kind) DO NOTHING
                """).param("p",practice).param("u",user).param("s",site).param("k",kind).update();
        }
    }
}
