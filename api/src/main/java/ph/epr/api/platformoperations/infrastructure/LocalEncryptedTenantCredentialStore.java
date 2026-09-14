package ph.epr.api.platformoperations.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import ph.epr.api.platformoperations.application.TenantProvisioningException;
import ph.epr.api.platformoperations.configuration.TenantProvisioningProperties;

@Component
@Profile("local")
class LocalEncryptedTenantCredentialStore implements TenantCredentialStore {

	private static final SecureRandom SECURE_RANDOM = new SecureRandom();
	private static final int NONCE_BYTES = 12;
	private static final int GCM_TAG_BITS = 128;

	private final JdbcClient jdbcClient;
	private final SecretKey encryptionKey;
	private final Clock clock;

	LocalEncryptedTenantCredentialStore(
		JdbcClient jdbcClient,
		TenantProvisioningProperties properties,
		Clock clock
	) {
		this.jdbcClient = jdbcClient;
		this.encryptionKey = readKey(properties.localCredentialEncryptionKey());
		this.clock = clock;
	}

	@Override
	@Transactional
	public TenantDatabaseCredential getOrCreate(UUID practiceId, CredentialPurpose purpose, String username) {
		jdbcClient.sql("SELECT set_config('epr.platform_operations', 'true', true)")
			.query(String.class)
			.single();
		var existing = find(practiceId, purpose);
		if (existing.isPresent()) {
			if (!existing.get().username().equals(username)) {
				throw new TenantProvisioningException(
					"TENANT_CREDENTIAL_MISMATCH",
					"Stored tenant credentials do not match the server-derived role name."
				);
			}
			return existing.get();
		}

		var secretReference = "local-control-plane://tenant/" + practiceId + "/" + purpose.name().toLowerCase();
		var password = generatePassword();
		var nonce = new byte[NONCE_BYTES];
		SECURE_RANDOM.nextBytes(nonce);
		var encrypted = encrypt(secretReference, password, nonce);
		jdbcClient.sql("""
			INSERT INTO local_tenant_database_credential (
				secret_reference, practice_id, credential_purpose, database_username,
				encrypted_password, nonce, created_at
			) VALUES (
				:reference, :practiceId, :purpose, :username, :password, :nonce, :createdAt
			)
			ON CONFLICT (practice_id, credential_purpose) DO NOTHING
			""")
			.param("reference", secretReference)
			.param("practiceId", practiceId)
			.param("purpose", purpose.name())
			.param("username", username)
			.param("password", encrypted)
			.param("nonce", nonce)
			.param("createdAt", OffsetDateTime.now(clock))
			.update();
		return find(practiceId, purpose)
			.orElseThrow(() -> new TenantProvisioningException(
				"TENANT_CREDENTIAL_WRITE_FAILED",
				"Tenant credentials could not be stored."
			));
	}

	private Optional<TenantDatabaseCredential> find(UUID practiceId, CredentialPurpose purpose) {
		return jdbcClient.sql("""
			SELECT secret_reference, database_username, encrypted_password, nonce
			FROM local_tenant_database_credential
			WHERE practice_id = :practiceId AND credential_purpose = :purpose
			""")
			.param("practiceId", practiceId)
			.param("purpose", purpose.name())
			.query((resultSet, rowNumber) -> {
				var reference = resultSet.getString("secret_reference");
				return new TenantDatabaseCredential(
					reference,
					resultSet.getString("database_username"),
					decrypt(
						reference,
						resultSet.getBytes("encrypted_password"),
						resultSet.getBytes("nonce")
					)
				);
			})
			.optional();
	}

	private byte[] encrypt(String reference, String password, byte[] nonce) {
		try {
			var cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
			cipher.updateAAD(reference.getBytes(StandardCharsets.UTF_8));
			return cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException exception) {
			throw new TenantProvisioningException(
				"TENANT_CREDENTIAL_ENCRYPTION_FAILED",
				"Tenant credentials could not be encrypted.",
				exception
			);
		}
	}

	private String decrypt(String reference, byte[] encrypted, byte[] nonce) {
		try {
			var cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
			cipher.updateAAD(reference.getBytes(StandardCharsets.UTF_8));
			return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException exception) {
			throw new TenantProvisioningException(
				"TENANT_CREDENTIAL_DECRYPTION_FAILED",
				"Tenant credentials could not be decrypted.",
				exception
			);
		}
	}

	private static SecretKey readKey(String encodedKey) {
		try {
			var bytes = Base64.getDecoder().decode(encodedKey);
			if (bytes.length != 32) {
				throw new IllegalArgumentException("Tenant credential encryption key must decode to 32 bytes.");
			}
			return new SecretKeySpec(bytes, "AES");
		}
		catch (RuntimeException exception) {
			throw new IllegalStateException(
				"EPR_TENANT_CREDENTIAL_ENCRYPTION_KEY must be a Base64-encoded 256-bit key.",
				exception
			);
		}
	}

	private static String generatePassword() {
		var bytes = new byte[36];
		SECURE_RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
