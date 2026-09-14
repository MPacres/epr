package ph.epr.api.platformoperations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class TenantCreationRequestTests {

	@Test
	void createsStableServerSideNamesAndRequestFingerprint() {
		var practiceId = UUID.fromString("8fb6b6e2-0e49-4879-80d7-e48ef5ab1259");
		var site = site();
		var request = new TenantCreationRequest(
			practiceId,
			"makati-family-clinic",
			"Makati Family Clinic",
			"Alex Reyes",
			"Alex.Reyes@Example.Test",
			site,
			UUID.randomUUID()
		);

		assertThat(TenantIdentifierPolicy.databaseName(practiceId))
			.isEqualTo("epr_t_8fb6b6e20e49487980d7e48ef5ab1259");
		assertThat(TenantIdentifierPolicy.runtimeRole(practiceId))
			.isEqualTo("epr_r_8fb6b6e20e49487980d7e48ef5ab1259");
		assertThat(TenantIdentifierPolicy.migrationRole(practiceId))
			.isEqualTo("epr_m_8fb6b6e20e49487980d7e48ef5ab1259");
		assertThat(request.fingerprint()).hasSize(64);
		assertThat(request.fingerprint()).isEqualTo(new TenantCreationRequest(
			practiceId,
			"makati-family-clinic",
			"Makati Family Clinic",
			"Alex Reyes",
			"alex.reyes@example.test",
			site,
			UUID.randomUUID()
		).fingerprint());
	}

	@Test
	void rejectsUnsafeOrAmbiguousTenantInput() {
		assertThatThrownBy(() -> request("Makati_Clinic", "Makati Clinic"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Practice code");
		assertThatThrownBy(() -> request("makati-clinic", "Makati\nClinic"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("printable");
	}

	private TenantCreationRequest request(String code, String displayName) {
		return new TenantCreationRequest(
			UUID.randomUUID(),
			code,
			displayName,
			"Alex Reyes",
			"alex.reyes@example.test",
			site(),
			UUID.randomUUID()
		);
	}

	private InitialTenantSite site() {
		return new InitialTenantSite(
			UUID.fromString("85a572b4-f3a4-49b0-99ba-1a819598c395"),
			"Makati Clinic",
			"Medical Arts Building",
			"+63 917 000 0000",
			"PH",
			"1300000000",
			"1300000000",
			"1380300000"
		);
	}
}
