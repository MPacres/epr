package ph.epr.api.platformoperations.application;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ph.epr.api.identitytenancy.*;
import ph.epr.api.platformoperations.SyntheticClinicalPractice;
import ph.epr.api.platformoperations.domain.*;

@Service
@Profile("local")
@ConditionalOnProperty(name = "epr.clinical.demo-seed", havingValue = "true")
class LocalSyntheticClinicalPractice implements SyntheticClinicalPractice {
    private final TenantProvisioningService provisioning;
    private final PlatformUserDirectory users;
    private final SyntheticClinicalIdentity identities;
    private final String adminUsername;
    private final String practiceName;
    private final String siteName;
    private final String clinicianName;
    private final TenantSchemaUpgradeService upgrades;
    LocalSyntheticClinicalPractice(TenantProvisioningService provisioning, PlatformUserDirectory users, SyntheticClinicalIdentity identities, TenantSchemaUpgradeService upgrades,
        @Value("${epr.local-auth.superadmin.username:superadmin}") String adminUsername,
        @Value("${epr.clinical.demo-practice-name:Client Demo Practice}") String practiceName,
        @Value("${epr.clinical.demo-site-name:Main Clinic}") String siteName,
        @Value("${epr.clinical.demo-clinician-name:Dr. Isabel Reyes}") String clinicianName) {
        this.provisioning = provisioning; this.users = users; this.identities = identities; this.adminUsername = adminUsername; this.upgrades = upgrades;
        this.practiceName = practiceName; this.siteName = siteName; this.clinicianName = clinicianName;
    }
    public void prepare(UUID clinician, UUID practice, UUID site, String username) {
        var admin = users.findByUsername(adminUsername).orElseThrow();
        var result = provisioning.provision(admin, new TenantCreationRequest(practice, "synthetic-obgyn", practiceName,
            clinicianName, username, new InitialTenantSite(site, siteName, "", "", "PH", "1300000000", "1300000000", "1380300000"),
            UUID.fromString("c6ac9aeb-3196-4a76-b9df-ff090eac77d1")));
        if (!result.tenant().isRoutable()) throw new IllegalStateException("Synthetic tenant provisioning is unavailable");
        if (!ClinicalReadiness.REQUIRED_SCHEMA_VERSION.equals(result.tenant().schemaVersion())) upgrades.upgrade(admin, practice, UUID.nameUUIDFromBytes((practice + "/clinical-schema-19").getBytes(java.nio.charset.StandardCharsets.UTF_8)), result.tenant().version(), "Upgrade opt-in synthetic workspace to schema 19");
        identities.grant(clinician, practice, site, admin.id());
    }
}
