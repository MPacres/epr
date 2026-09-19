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
    private final TenantSchemaUpgradeService upgrades;
    LocalSyntheticClinicalPractice(TenantProvisioningService provisioning, PlatformUserDirectory users, SyntheticClinicalIdentity identities, TenantSchemaUpgradeService upgrades,
        @Value("${epr.local-auth.superadmin.username:superadmin}") String adminUsername) {
        this.provisioning = provisioning; this.users = users; this.identities = identities; this.adminUsername = adminUsername; this.upgrades = upgrades;
    }
    public void prepare(UUID clinician, UUID practice, UUID site, String username) {
        var admin = users.findByUsername(adminUsername).orElseThrow();
        var result = provisioning.provision(admin, new TenantCreationRequest(practice, "synthetic-obgyn", "Synthetic Women's Health Practice",
            "Dr. Isabel Reyes · synthetic", username, new InitialTenantSite(site, "Synthetic Makati Clinic", "", "", "PH", "1300000000", "1300000000", "1380300000"),
            UUID.fromString("c6ac9aeb-3196-4a76-b9df-ff090eac77d1")));
        if (!result.tenant().isRoutable()) throw new IllegalStateException("Synthetic tenant provisioning is unavailable");
        if (!"11".equals(result.tenant().schemaVersion())) upgrades.upgrade(admin, practice, UUID.nameUUIDFromBytes((practice + "/clinical-schema-11").getBytes(java.nio.charset.StandardCharsets.UTF_8)), result.tenant().version(), "Upgrade opt-in synthetic OB-GYN workspace to schema 11");
        identities.grant(clinician, practice, site, admin.id());
    }
}
