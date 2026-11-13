package com.stackcorp.astra;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

class ModuleStructureTests {

	private final ApplicationModules modules = ApplicationModules.of(StackBackendApplication.class);

	private static final JavaClasses PRODUCTION = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.importPackages("com.stackcorp");

	@Test
	void modulesDependOnlyOnDeclaredPublicApis() {
		modules.verify();
	}

	// 모듈 안 패키지 사이에도 순환이 없다(docs/c4.md 5.1절).
	@Test
	void packagesAreFreeOfCycles() {
		slices().matching("com.stackcorp.astra.(**)").should().beFreeOfCycles().check(PRODUCTION);
	}

	// S3는 files만 부른다(ADR-0007). integration.delivery는 CloudFront cookie 서명 utility만 쓴다.
	@Test
	void onlyStorageAndDeliveryUseTheAwsSdk() {
		noClasses().that()
			.resideOutsideOfPackages("com.stackcorp.astra.files.storage..", "com.stackcorp.astra.integration.delivery..")
			.should()
			.dependOnClassesThat()
			.resideInAPackage("software.amazon.awssdk..")
			.check(PRODUCTION);
	}

	// 외부 network 호출은 integration의 adapter만 한다.
	@Test
	void onlyIntegrationOpensNetworkConnections() {
		noClasses().that()
			.resideInAPackage("com.stackcorp.astra..")
			.and()
			.resideOutsideOfPackage("com.stackcorp.astra.integration..")
			.should()
			.dependOnClassesThat()
			.resideInAnyPackage("java.net.http..", "org.springframework.web.client..",
					"org.springframework.web.reactive.function.client..")
			.orShould()
			.dependOnClassesThat()
			.haveFullyQualifiedName("java.net.Socket")
			.orShould()
			.dependOnClassesThat()
			.haveFullyQualifiedName("java.net.HttpURLConnection")
			.check(PRODUCTION);
	}

	// converter와 transcoder process는 앱 코드를 쓰지 않고, 앱도 둘을 HTTP로만 부른다.
	@Test
	void sidecarProcessesShareNoCodeWithTheApp() {
		noClasses().that()
			.resideInAnyPackage("com.stackcorp.converter..", "com.stackcorp.transcoder..")
			.should()
			.dependOnClassesThat()
			.resideInAPackage("com.stackcorp.astra..")
			.check(PRODUCTION);
		noClasses().that()
			.resideInAPackage("com.stackcorp.astra..")
			.should()
			.dependOnClassesThat()
			.resideInAnyPackage("com.stackcorp.converter..", "com.stackcorp.transcoder..")
			.check(PRODUCTION);
	}

	// build/spring-modulith-docs/ 에 C4 component diagram(PlantUML)을 쓴다.
	@Test
	void writesC4ComponentDiagrams() {
		new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
	}

}
