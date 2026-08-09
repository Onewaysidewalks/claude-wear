.DEFAULT_GOAL := help
SHELL := /bin/bash

.PHONY: help
help: ## Show this help
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[1m%-16s\033[0m %s\n", $$1, $$2}'

.PHONY: install
install: ## Install the bridge's dependencies
	cd bridge && npm ci

.PHONY: protocol
protocol: ## Regenerate the TypeScript and Kotlin views of the wire protocol
	node protocol/codegen/generate.mjs

.PHONY: protocol-check
protocol-check: ## Fail if either generated protocol file is stale
	node protocol/codegen/generate.mjs --check

.PHONY: bridge
bridge: ## Lint, typecheck and test the bridge
	cd bridge && npm run lint && npm run typecheck && npm test

.PHONY: android
android: ## Unit-test, lint and assemble the watch app
	cd wear && ./gradlew :protocol:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

.PHONY: screenshots
screenshots: ## Re-record the watch screenshots after an intended UI change
	cd wear && ./gradlew :app:recordRoborazziDebug
	@echo "recorded — review wear/app/src/test/screenshots/ before committing"

.PHONY: screenshots-check
screenshots-check: ## Fail if any screen no longer matches its committed screenshot
	cd wear && ./gradlew :app:verifyRoborazziDebug

.PHONY: contract
contract: protocol-check ## Run the protocol contract tests on both sides
	cd bridge && npx vitest run test/protocol-golden.test.ts
	cd wear && ./gradlew :protocol:test

.PHONY: e2e
e2e: ## Full loop: fake bridge + Wear emulator + the real app
	./scripts/e2e.sh

.PHONY: dev
dev: ## Run the bridge with a fake agent, no API key and no network
	cd bridge && npm run dev

.PHONY: cli
cli: ## Drive a running bridge from a terminal. ARGS='--pair 12345678 --new ~/code/thing'
	cd bridge && npm run --silent cli -- $(ARGS)

.PHONY: ci
ci: protocol-check bridge android screenshots-check ## Everything CI runs except the emulator job

.PHONY: clean
clean: ## Remove build output
	rm -rf bridge/dist bridge/coverage .e2e
	cd wear && ./gradlew clean || true
