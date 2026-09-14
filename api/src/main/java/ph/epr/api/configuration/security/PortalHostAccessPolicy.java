package ph.epr.api.configuration.security;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import ph.epr.api.identitytenancy.PlatformRole;

@Component
class PortalHostAccessPolicy {

	private final String clinicalHostname;
	private final String supportHostname;

	PortalHostAccessPolicy(
		@Value("${epr.portal.clinical-hostname:epr.test}") String clinicalHostname,
		@Value("${epr.portal.support-hostname:support.epr.test}") String supportHostname
	) {
		this.clinicalHostname = normalize(clinicalHostname);
		this.supportHostname = normalize(supportHostname);
	}

	boolean permits(String requestHostname, PlatformRole role) {
		var hostname = normalize(requestHostname);
		if (hostname.equals(supportHostname)) {
			return role == PlatformRole.SUPERADMIN || role == PlatformRole.PROVIDER_SUPPORT;
		}
		if (hostname.equals(clinicalHostname)) {
			return role == PlatformRole.PRACTICE_STAFF || role == PlatformRole.CLINICIAN;
		}
		return true;
	}

	String denialMessage(String requestHostname) {
		return normalize(requestHostname).equals(supportHostname)
			? "This account cannot use provider support. Sign in at " + clinicalHostname + " instead."
			: "This account cannot use the physician portal. Sign in at " + supportHostname + " instead.";
	}

	private static String normalize(String hostname) {
		return hostname.strip().toLowerCase(Locale.ROOT);
	}
}
