.PHONY: all clean compile build test package check run stop integration-test

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

integration-test: run
	@echo "Waiting for Keycloak to start on port 8081..."
	@timeout 60 bash -c 'until curl -s http://localhost:8081 > /dev/null; do sleep 2; done'
	python3 scripts/test_spi.py
	docker compose down

run: build
	docker compose up -d

stop:
	docker compose down