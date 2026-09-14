package ph.epr.api.identitytenancy.internal;

import java.util.Locale;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ph.epr.api.identitytenancy.PlatformRole;
import ph.epr.api.identitytenancy.PlatformUser;
import ph.epr.api.identitytenancy.PlatformUserDirectory;

@Repository
class JdbcPlatformUserDirectory implements PlatformUserDirectory {

	private final JdbcClient jdbcClient;

	JdbcPlatformUserDirectory(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Override
	public Optional<PlatformUser> findByUsername(String username) {
		return jdbcClient.sql("""
			SELECT id, username, display_name, password_hash, role, enabled
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
				resultSet.getBoolean("enabled")
			))
			.optional();
	}
}

