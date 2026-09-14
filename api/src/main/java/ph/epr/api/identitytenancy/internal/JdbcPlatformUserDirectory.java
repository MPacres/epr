package ph.epr.api.identitytenancy.internal;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import ph.epr.api.identitytenancy.PracticeAdministrationConflictException;
import ph.epr.api.identitytenancy.PlatformRole;
import ph.epr.api.identitytenancy.PlatformUser;
import ph.epr.api.identitytenancy.PlatformUserDirectory;
import ph.epr.api.identitytenancy.PracticeAdministratorSetup;
import ph.epr.api.identitytenancy.PracticeAdministratorSetup.SetupStatus;
import ph.epr.api.identitytenancy.PracticeProvisioningConflictException;
import ph.epr.api.identitytenancy.TemporaryPasswordIssue;

@Repository
class JdbcPlatformUserDirectory implements PlatformUserDirectory {
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
	private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
	private static final String DIGITS = "23456789";
	private static final String SYMBOLS = "!@#$%*+-_?";
	private static final String PASSWORD_CHARACTERS = UPPER + LOWER + DIGITS + SYMBOLS;

	private final JdbcClient jdbcClient;
	private final PasswordEncoder passwordEncoder;

	JdbcPlatformUserDirectory(JdbcClient jdbcClient, PasswordEncoder passwordEncoder) {
		this.jdbcClient = jdbcClient;
		this.passwordEncoder = passwordEncoder;
	}

	@Override
	public Optional<PlatformUser> findByUsername(String username) {
		return jdbcClient.sql("""
			SELECT id, username, display_name, password_hash, role, enabled, password_reset_required
			FROM platform_user_account
			WHERE username = :username
			""")
			.param("username", username.strip().toLowerCase(Locale.ROOT))
			.query((resultSet, rowNumber) -> new PlatformUser(
				resultSet.getObject("id", java.util.UUID.class),
				resultSet.getString("username"),
				resultSet.getString("display_name"),
				resultSet.getString("password_hash"),
				PlatformRole.valueOf(resultSet.getString("role")),
				resultSet.getBoolean("enabled"),
				resultSet.getBoolean("password_reset_required")
			))
			.optional();
	}

	@Override
	@Transactional
	public PracticeAdministratorSetup preparePracticeAdministrator(
		UUID practiceId,
		String displayName,
		String email,
		UUID requestedByUserId,
		OffsetDateTime now
	) {
		var normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
		var normalizedName = displayName.strip();
		enablePlatformOperationsContext();
		jdbcClient.sql("SELECT pg_advisory_xact_lock(hashtextextended(:email, 0))")
			.param("email", normalizedEmail)
			.query((resultSet, rowNumber) -> Boolean.TRUE)
			.single();

		var existingSetup = findSetup(practiceId);
		if (existingSetup.isPresent()) {
			var setup = existingSetup.get();
			if (!setup.email().equals(normalizedEmail) || !setup.displayName().equalsIgnoreCase(normalizedName)) {
				throw new PracticeProvisioningConflictException(
					"This Practice already has a different initial administrator setup."
				);
			}
			return setup;
		}

		var existingUser = findByUsername(normalizedEmail);
		var temporaryPassword = existingUser.isEmpty() ? generateTemporaryPassword() : null;
		var user = existingUser.orElseGet(() -> createTemporaryPasswordUser(
			normalizedName, normalizedEmail, temporaryPassword, now
		));
		if (existingUser.isPresent()) {
			assertExistingPracticeUser(user, normalizedName);
		}
		var status = existingUser.isPresent() ? SetupStatus.EXISTING_ACCOUNT : SetupStatus.TEMPORARY_PASSWORD_ISSUED;

		jdbcClient.sql("""
			INSERT INTO practice_administrator_setup (
				id, practice_id, user_id, email, display_name, setup_status,
				requested_by_user_id, created_at, updated_at
			) VALUES (
				:id, :practiceId, :userId, :email, :displayName, :status,
				:requestedBy, :now, :now
			)
			""")
			.param("id", UUID.randomUUID())
			.param("practiceId", practiceId)
			.param("userId", user.id())
			.param("email", normalizedEmail)
			.param("displayName", user.displayName())
			.param("status", status.name())
			.param("requestedBy", requestedByUserId)
			.param("now", now)
			.update();
		return new PracticeAdministratorSetup(
			user.id(), user.displayName(), normalizedEmail, status, temporaryPassword
		);
	}

	@Override
	@Transactional
	public TemporaryPasswordIssue issueTemporaryPassword(
		UUID practiceId,
		long expectedVersion,
		UUID idempotencyKey,
		UUID requestedByUserId,
		String reason,
		OffsetDateTime now
	) {
		enablePlatformOperationsContext();
		var existingOperation = jdbcClient.sql("""
			SELECT practice_id, event_type
			FROM practice_administration_event
			WHERE idempotency_key = :idempotencyKey
			""")
			.param("idempotencyKey", idempotencyKey)
			.query((resultSet, rowNumber) -> new ExistingOperation(
				resultSet.getObject("practice_id", UUID.class), resultSet.getString("event_type")
			))
			.optional();
		if (existingOperation.isPresent()) {
			if (!existingOperation.get().practiceId().equals(practiceId)
				|| !existingOperation.get().eventType().equals("ADMIN_TEMPORARY_PASSWORD_ISSUED")) {
				throw new PracticeAdministrationConflictException(
					"The idempotency key was already used for a different Practice management action."
				);
			}
			return new TemporaryPasswordIssue(null, false);
		}

		var setup = findSetupForUpdate(practiceId)
			.orElseThrow(() -> new PracticeAdministrationConflictException(
				"The Practice administrator setup could not be found."
			));
		if (setup.setupStatus() == SetupStatus.EXISTING_ACCOUNT) {
			throw new PracticeAdministrationConflictException(
				"This Practice uses an existing administrator account and does not need a temporary password."
			);
		}
		if (setup.setupStatus() == SetupStatus.PASSWORD_SET) {
			throw new PracticeAdministrationConflictException(
				"The administrator already replaced the temporary password. Use the future account-recovery workflow instead."
			);
		}

		var currentVersion = lockPracticeVersion(practiceId);
		if (currentVersion != expectedVersion) throw stalePractice();
		var temporaryPassword = generateTemporaryPassword();
		jdbcClient.sql("""
			UPDATE platform_user_account
			SET password_hash = :passwordHash,
				enabled = TRUE,
				password_reset_required = TRUE,
				updated_at = :now
			WHERE id = :userId
			""")
			.param("passwordHash", passwordEncoder.encode(temporaryPassword))
			.param("now", now)
			.param("userId", setup.userId())
			.update();
		jdbcClient.sql("""
			UPDATE practice_administrator_setup
			SET setup_status = 'TEMPORARY_PASSWORD_ISSUED', updated_at = :now
			WHERE practice_id = :practiceId
			""")
			.param("now", now)
			.param("practiceId", practiceId)
			.update();
		incrementPracticeVersion(practiceId, expectedVersion, now);
		appendAdministrationEvent(
			practiceId, "ADMIN_TEMPORARY_PASSWORD_ISSUED", requestedByUserId, reason,
			setup.setupStatus().name(), SetupStatus.TEMPORARY_PASSWORD_ISSUED.name(),
			idempotencyKey, expectedVersion + 1, now
		);
		return new TemporaryPasswordIssue(temporaryPassword, true);
	}

	@Override
	@Transactional
	public PlatformUser completeRequiredPasswordChange(
		UUID userId,
		String newPassword,
		UUID idempotencyKey,
		OffsetDateTime now
	) {
		enablePlatformOperationsContext();
		var repeated = jdbcClient.sql("""
			SELECT actor_user_id
			FROM practice_administration_event
			WHERE idempotency_key = :idempotencyKey
				AND event_type = 'ADMIN_PASSWORD_SET'
			""")
			.param("idempotencyKey", idempotencyKey)
			.query(UUID.class)
			.optional();
		if (repeated.isPresent()) {
			if (!repeated.get().equals(userId)) {
				throw new PracticeAdministrationConflictException(
					"The idempotency key was already used by another account."
				);
			}
			return findById(userId).orElseThrow(() -> new PracticeAdministrationConflictException("Account not found."));
		}

		var account = findByIdForUpdate(userId)
			.orElseThrow(() -> new PracticeAdministrationConflictException("Account not found."));
		if (!account.enabled() || !account.passwordResetRequired()) {
			throw new PracticeAdministrationConflictException(
				"This account does not have a required temporary-password change."
			);
		}
		if (passwordEncoder.matches(newPassword, account.passwordHash())) {
			throw new IllegalArgumentException("Choose a new password that is different from the temporary password.");
		}
		var setup = jdbcClient.sql("""
			SELECT practice_id, user_id, display_name, email, setup_status
			FROM practice_administrator_setup
			WHERE user_id = :userId AND setup_status = 'TEMPORARY_PASSWORD_ISSUED'
			FOR UPDATE
			""")
			.param("userId", userId)
			.query(this::mapSetup)
			.optional()
			.orElseThrow(() -> new PracticeAdministrationConflictException(
				"The temporary-password setup record could not be found."
			));
		var currentVersion = lockPracticeVersion(setup.practiceId());
		jdbcClient.sql("""
			UPDATE platform_user_account
			SET password_hash = :passwordHash,
				password_reset_required = FALSE,
				updated_at = :now
			WHERE id = :userId
			""")
			.param("passwordHash", passwordEncoder.encode(newPassword))
			.param("now", now)
			.param("userId", userId)
			.update();
		jdbcClient.sql("""
			UPDATE practice_administrator_setup
			SET setup_status = 'PASSWORD_SET', updated_at = :now
			WHERE practice_id = :practiceId
			""")
			.param("now", now)
			.param("practiceId", setup.practiceId())
			.update();
		incrementPracticeVersion(setup.practiceId(), currentVersion, now);
		appendAdministrationEvent(
			setup.practiceId(), "ADMIN_PASSWORD_SET", userId, null,
			SetupStatus.TEMPORARY_PASSWORD_ISSUED.name(), SetupStatus.PASSWORD_SET.name(),
			idempotencyKey, currentVersion + 1, now
		);
		return findById(userId).orElseThrow(() -> new PracticeAdministrationConflictException("Account not found."));
	}

	private PlatformUser createTemporaryPasswordUser(
		String displayName,
		String email,
		String temporaryPassword,
		OffsetDateTime now
	) {
		var user = new PlatformUser(
			UUID.randomUUID(),
			email,
			displayName,
			passwordEncoder.encode(temporaryPassword),
			PlatformRole.PRACTICE_STAFF,
			true,
			true
		);
		jdbcClient.sql("""
			INSERT INTO platform_user_account (
				id, username, display_name, password_hash, role, enabled,
				password_reset_required, created_at, updated_at
			) VALUES (
				:id, :username, :displayName, :passwordHash, 'PRACTICE_STAFF', TRUE,
				TRUE, :now, :now
			)
			""")
			.param("id", user.id())
			.param("username", user.username())
			.param("displayName", user.displayName())
			.param("passwordHash", user.passwordHash())
			.param("now", now)
			.update();
		return user;
	}

	private void assertExistingPracticeUser(PlatformUser user, String requestedDisplayName) {
		if (!user.enabled() || user.passwordResetRequired()
			|| (user.role() != PlatformRole.CLINICIAN && user.role() != PlatformRole.PRACTICE_STAFF)) {
			throw new PracticeProvisioningConflictException(
				"The administrator email belongs to an account that cannot receive Practice administration."
			);
		}
		if (!user.displayName().equalsIgnoreCase(requestedDisplayName)) {
			throw new PracticeProvisioningConflictException(
				"The administrator email belongs to an existing account under a different name. Verify the identity and try again."
			);
		}
	}

	private Optional<PracticeAdministratorSetup> findSetup(UUID practiceId) {
		return jdbcClient.sql("""
			SELECT practice_id, user_id, display_name, email, setup_status
			FROM practice_administrator_setup
			WHERE practice_id = :practiceId
			FOR UPDATE
			""")
			.param("practiceId", practiceId)
			.query((resultSet, rowNumber) -> mapSetup(resultSet, rowNumber).setup().withoutTemporaryPassword())
			.optional();
	}

	private Optional<AdministratorSetupRecord> findSetupForUpdate(UUID practiceId) {
		return jdbcClient.sql("""
			SELECT practice_id, user_id, display_name, email, setup_status
			FROM practice_administrator_setup
			WHERE practice_id = :practiceId
			FOR UPDATE
			""")
			.param("practiceId", practiceId)
			.query(this::mapSetup)
			.optional();
	}

	private Optional<PlatformUser> findById(UUID userId) {
		return jdbcClient.sql("""
			SELECT id, username, display_name, password_hash, role, enabled, password_reset_required
			FROM platform_user_account
			WHERE id = :userId
			""")
			.param("userId", userId)
			.query(this::mapUser)
			.optional();
	}

	private Optional<PlatformUser> findByIdForUpdate(UUID userId) {
		return jdbcClient.sql("""
			SELECT id, username, display_name, password_hash, role, enabled, password_reset_required
			FROM platform_user_account
			WHERE id = :userId
			FOR UPDATE
			""")
			.param("userId", userId)
			.query(this::mapUser)
			.optional();
	}

	private PlatformUser mapUser(java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
		return new PlatformUser(
			resultSet.getObject("id", UUID.class),
			resultSet.getString("username"),
			resultSet.getString("display_name"),
			resultSet.getString("password_hash"),
			PlatformRole.valueOf(resultSet.getString("role")),
			resultSet.getBoolean("enabled"),
			resultSet.getBoolean("password_reset_required")
		);
	}

	private AdministratorSetupRecord mapSetup(java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
		return new AdministratorSetupRecord(
			resultSet.getObject("practice_id", UUID.class),
			new PracticeAdministratorSetup(
				resultSet.getObject("user_id", UUID.class),
				resultSet.getString("display_name"),
				resultSet.getString("email"),
				SetupStatus.valueOf(resultSet.getString("setup_status")),
				null
			)
		);
	}

	private long lockPracticeVersion(UUID practiceId) {
		return jdbcClient.sql("""
			SELECT version
			FROM practice_tenant_registry
			WHERE practice_id = :practiceId
			FOR UPDATE
			""")
			.param("practiceId", practiceId)
			.query(Long.class)
			.optional()
			.orElseThrow(() -> new PracticeAdministrationConflictException("Practice not found."));
	}

	private void incrementPracticeVersion(UUID practiceId, long expectedVersion, OffsetDateTime now) {
		var updated = jdbcClient.sql("""
			UPDATE practice_tenant_registry
			SET updated_at = :now, version = version + 1
			WHERE practice_id = :practiceId AND version = :expectedVersion
			""")
			.param("now", now)
			.param("practiceId", practiceId)
			.param("expectedVersion", expectedVersion)
			.update();
		if (updated != 1) throw stalePractice();
	}

	private void appendAdministrationEvent(
		UUID practiceId,
		String eventType,
		UUID actorUserId,
		String reason,
		String previousValue,
		String nextValue,
		UUID idempotencyKey,
		long resultingVersion,
		OffsetDateTime occurredAt
	) {
		jdbcClient.sql("""
			INSERT INTO practice_administration_event (
				id, practice_id, event_type, actor_user_id, reason,
				previous_value, next_value, idempotency_key, resulting_version, occurred_at
			) VALUES (
				:id, :practiceId, :eventType, :actorUserId, :reason,
				:previousValue, :nextValue, :idempotencyKey, :resultingVersion, :occurredAt
			)
			""")
			.param("id", UUID.randomUUID())
			.param("practiceId", practiceId)
			.param("eventType", eventType)
			.param("actorUserId", actorUserId)
			.param("reason", reason, java.sql.Types.VARCHAR)
			.param("previousValue", previousValue)
			.param("nextValue", nextValue)
			.param("idempotencyKey", idempotencyKey)
			.param("resultingVersion", resultingVersion)
			.param("occurredAt", occurredAt)
			.update();
	}

	private PracticeAdministrationConflictException stalePractice() {
		return new PracticeAdministrationConflictException(
			"This Practice changed after it was loaded. Refresh it before trying again."
		);
	}

	private String generateTemporaryPassword() {
		var characters = new char[20];
		characters[0] = randomCharacter(UPPER);
		characters[1] = randomCharacter(LOWER);
		characters[2] = randomCharacter(DIGITS);
		characters[3] = randomCharacter(SYMBOLS);
		for (var index = 4; index < characters.length; index++) {
			characters[index] = randomCharacter(PASSWORD_CHARACTERS);
		}
		for (var index = characters.length - 1; index > 0; index--) {
			var other = RANDOM.nextInt(index + 1);
			var value = characters[index];
			characters[index] = characters[other];
			characters[other] = value;
		}
		return new String(characters);
	}

	private char randomCharacter(String characters) {
		return characters.charAt(RANDOM.nextInt(characters.length()));
	}

	private void enablePlatformOperationsContext() {
		jdbcClient.sql("SELECT set_config('epr.platform_operations', 'true', true)")
			.query(String.class)
			.single();
	}

	private record ExistingOperation(UUID practiceId, String eventType) {
	}

	private record AdministratorSetupRecord(UUID practiceId, PracticeAdministratorSetup setup) {
		UUID userId() {
			return setup.userId();
		}

		SetupStatus setupStatus() {
			return setup.setupStatus();
		}
	}
}
