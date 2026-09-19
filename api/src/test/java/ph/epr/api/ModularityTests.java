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
        "practiceadministration",
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
	void allowsOnlyDeclaredClinicalDependencyDirections() {
        var expected = java.util.Map.of(
            "platformoperations", Set.of("identitytenancy"),
            "prescribing", Set.of("encountertemplates", "platformoperations", "identitytenancy", "documentscommunication", "clinicalchart"),
            "ordersresults", Set.of("encountertemplates", "platformoperations", "identitytenancy", "documentscommunication", "carecoordination"),
            "practiceadministration", Set.of("identitytenancy", "platformoperations"),
            "carecoordination", Set.of("encountertemplates", "platformoperations", "identitytenancy", "documentscommunication"),
            "documentscommunication", Set.of("identitytenancy", "platformoperations"),
            "schedulingqueue", Set.of("identitytenancy", "platformoperations", "patientregistry", "encountertemplates"),
            "patientregistry", Set.of("platformoperations", "identitytenancy"),
            "clinicalchart", Set.of("patientregistry", "platformoperations", "identitytenancy"),
            "encountertemplates", Set.of("documentscommunication", "clinicalchart", "patientregistry", "platformoperations", "identitytenancy")
        );
        for (var module : modules) {
            var actual = module.getAllowedDependencies(modules).stream()
                .map(dependency -> dependency.getTargetModule().getIdentifier().toString())
                .collect(java.util.stream.Collectors.toSet());
            assertThat(actual).containsExactlyInAnyOrderElementsOf(expected.getOrDefault(module.getIdentifier().toString(), Set.of()));
        }
	}
}
