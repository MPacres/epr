package ph.epr.api.platformoperations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class TenantCreationRequestTests {

	@Test
	void createsStableServerSideNamesAndRequestFingerprint() {
		var practiceId = UUID.fromString("8fb6b6e2-0e49-4879-80d7-e48ef5ab1259");
		var administratorId = UUID.fromString("2a570acc-39a0-4b50-91bc-8b6ca2df0f2d");
		var request = new TenantCreationRequest(
			practiceId,
			"makati-family-clinic",
			"Makati Family Clinic",
			administratorId,
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
			administratorId,
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
			UUID.randomUUID(),
			UUID.randomUUID()
		);
	}
}
