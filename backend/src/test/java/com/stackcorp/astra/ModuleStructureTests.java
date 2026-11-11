package com.stackcorp.astra;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModuleStructureTests {

	private final ApplicationModules modules = ApplicationModules.of(StackBackendApplication.class);

	@Test
	void modulesDependOnlyOnDeclaredPublicApis() {
		modules.verify();
	}

	// build/spring-modulith-docs/ 에 C4 component diagram(PlantUML)을 쓴다.
	@Test
	void writesC4ComponentDiagrams() {
		new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
	}

}
