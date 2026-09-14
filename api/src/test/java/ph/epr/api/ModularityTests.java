package ph.epr.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

	private static final Set<String> EXPECTED_MODULES = Set.of(
		"carecoordination",
		"clinicalchart",
		"documentscommunication",
		"encountertemplates",
		"identitytenancy",
		"ordersresults",
		"patientregistry",
		"platformoperations",
		"prescribing",
		"schedulingqueue"
	);

	private final ApplicationModules modules = ApplicationModules.of(BackendApiApplication.class);

	@Test
	void verifiesStrictApplicationModuleBoundaries() {
		modules.verify();
	}

	@Test
	void declaresEveryPlannedInitialBusinessModule() {
		var moduleNames = modules.stream()
			.map(module -> module.getIdentifier().toString())
			.collect(java.util.stream.Collectors.toSet());

		assertThat(moduleNames).containsExactlyInAnyOrderElementsOf(EXPECTED_MODULES);
	}

	@Test
	void keepsEveryBusinessModuleClosed() {
		assertThat(modules.stream()).allMatch(module -> !module.isOpen());
	}

	@Test
	void allowsOnlyTheExplicitTenantProvisioningDependency() {
		assertThat(modules.stream()
			.filter(module -> !module.getIdentifier().toString().equals("platformoperations")))
			.allMatch(module -> module.getAllowedDependencies(modules).isEmpty());

		var platformOperations = modules.getModuleByName("platformoperations").orElseThrow();
		assertThat(platformOperations.getAllowedDependencies(modules).stream()
			.map(dependency -> dependency.getTargetModule().getIdentifier().toString()))
			.containsExactly("identitytenancy");
	}
}
