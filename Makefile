# Convenience targets. Maven remains authoritative — nothing here hides build behaviour.

JAVA_HOME ?= $(shell /usr/libexec/java_home -v 25 2>/dev/null || echo $$JAVA_HOME)
MVN := ./mvnw

.DEFAULT_GOAL := help

.PHONY: help
help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

.PHONY: build
build: ## Compile and run the full test suite
	$(MVN) clean verify

.PHONY: test
test: ## Run tests only
	$(MVN) test

.PHONY: run
run: ## Start Vortex on 127.0.0.1:7717
	$(MVN) -pl vortex-app spring-boot:run

.PHONY: demo
demo: ## Start the sample service on :8080
	$(MVN) -pl vortex-demo-service spring-boot:run

.PHONY: doctor
doctor: ## Check that this machine has what Vortex needs
	$(MVN) -q -pl vortex-app -am package -DskipTests
	@java -jar vortex-app/target/vortex.jar doctor

.PHONY: package
package: ## Build the executable jar
	$(MVN) -pl vortex-app -am package -DskipTests

.PHONY: native
native: ## Attempt a GraalVM native build (unverified — see docs/02-architecture/architecture.adoc)
	@echo "Native compilation has never been run successfully. Expect to find problems."
	@echo "JAVA_HOME must point at GraalVM 25. Currently: $(JAVA_HOME)"
	$(MVN) -Pnative -pl vortex-app native:compile

.PHONY: docs
docs: ## Render docs/**/*.adoc to build/docs/ with Asciidoctor
	./scripts/docs-build.sh

.PHONY: clean
clean: ## Remove build output
	$(MVN) clean
	rm -rf build/docs
