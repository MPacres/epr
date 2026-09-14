package ph.epr.api.identitytenancy.internal;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
class LocalSuperadminSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(LocalSuperadminSeeder.class);

	private final JdbcClient jdbcClient;
	private final PasswordEncoder passwordEncoder;
	private final String username;
	private final String password;
	private final String displayName;

	LocalSuperadminSeeder(
		JdbcClient jdbcClient,
		PasswordEncoder passwordEncoder,
		@Value("${epr.local-auth.superadmin.username}") String username,
		@Value("${epr.local-auth.superadmin.password}") String password,
		@Value("${epr.local-auth.superadmin.display-name}") String displayName
	) {
		this.jdbcClient = jdbcClient;
		this.passwordEncoder = passwordEncoder;
		this.username = username.strip().toLowerCase(Locale.ROOT);
		this.password = password;
		this.displayName = displayName.strip();
	}

	@Override
	public void run(ApplicationArguments arguments) {
		var now = OffsetDateTime.now(ZoneOffset.UTC);
		jdbcClient.sql("""
			INSERT INTO platform_user_account (
				id, username, display_name, password_hash, role, enabled, created_at, updated_at
			) VALUES (
				:id, :username, :displayName, :passwordHash, 'SUPERADMIN', TRUE, :now, :now
			)
			ON CONFLICT (username) DO UPDATE SET
				display_name = EXCLUDED.display_name,
				password_hash = EXCLUDED.password_hash,
				role = EXCLUDED.role,
				enabled = TRUE,
				updated_at = EXCLUDED.updated_at
			""")
			.param("id", UUID.randomUUID())
			.param("username", username)
			.param("displayName", displayName)
			.param("passwordHash", passwordEncoder.encode(password))
			.param("now", now)
			.update();

		log.info("Local development superadmin account is ready for username '{}'.", username);
	}
}
