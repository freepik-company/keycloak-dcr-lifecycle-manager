.PHONY: all clean compile build test package check run stop integration-test phase2-test race-test all-tests

# Variables
MVN = docker run --rm -v "$$(pwd)":/usr/src/mymaven -w /usr/src/mymaven maven:3.9.6-eclipse-temurin-21 mvn
JAR_FILE = target/keycloak-dcr-lifecycle-manager.jar

all: clean build

clean:
	$(MVN) clean

compile:
	$(MVN) compile

test:
	$(MVN) test

build:
	$(MVN) clean package -DskipTests

package: build

check:
	$(MVN) verify

run: build
	docker compose up -d

stop:
	docker compose down

# Standard integration test: Phase 1 (DCR registration + login marking)
integration-test: run
	@echo "Waiting for Keycloak to start on port 8081..."
	@timeout 60 bash -c 'until curl -s http://localhost:8081 > /dev/null; do sleep 2; done'
	python3 scripts/test_spi.py
	docker compose down

# Phase 2 end-to-end test: forces aggressive cleanup interval (1 minute) and
# zero grace period to verify the scheduled task in real Keycloak.
phase2-test: build
	docker compose down
	DCR_LIFECYCLE_CLEANUP_INTERVAL_MINUTES=1 DCR_LIFECYCLE_GRACE_PERIOD_HOURS=0 docker compose up -d
	@echo "Waiting for Keycloak to start on port 8081..."
	@timeout 60 bash -c 'until curl -s http://localhost:8081 > /dev/null; do sleep 2; done'
	python3 scripts/test_phase2.py
	docker compose down

# Race-condition test: launches simultaneous logins to verify the LOGIN hot path
# is non-destructive.
race-test: run
	@echo "Waiting for Keycloak to start on port 8081..."
	@timeout 60 bash -c 'until curl -s http://localhost:8081 > /dev/null; do sleep 2; done'
	python3 scripts/test_race.py
	docker compose down

# Run every kind of test in sequence
all-tests: test integration-test race-test phase2-test
