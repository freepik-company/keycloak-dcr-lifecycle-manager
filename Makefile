.PHONY: all clean compile build test package check run stop

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