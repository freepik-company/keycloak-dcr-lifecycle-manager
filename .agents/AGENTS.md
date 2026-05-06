# Project Agents Documentation

Welcome to the `keycloak-dcr-lifecycle-manager` repository.

## 🤖 Agent Guidelines

All agentic documentation, context, and structural decisions MUST live inside the `.agents/` directory and MUST be written in English.

- **`.agents/AGENTS.md`**: This file. The entry point for agents.
- **`.agents/DESIGN_DECISIONS.md`**: Contains the business logic, the architectural strategy, and all technical decisions made regarding how we handle DCR linking and garbage collection.

Always read `.agents/DESIGN_DECISIONS.md` before making architectural changes.

## 🛠️ Essential Commands

We use a Dockerized Maven environment via the `Makefile` to avoid local Java/Maven version conflicts.

- `make compile`: Compiles the Java project.
- `make package` (or `make build`): Compiles and packages the SPI into a JAR file located in `target/`.
- `make run`: Builds the JAR and starts a local Keycloak instance via `docker-compose.yml` (on port `8081`) for testing.
- `make stop`: Stops the local Keycloak container.
- `make clean`: Cleans the `target/` directory.
- `python3 scripts/test_spi.py`: Runs a Python script that tests the DCR creation, the tagging, and the login linking/cleanup process via the Keycloak REST API.

## 🏗️ Structure

- `src/main/java/com/achetronic/keycloak/dcrlifecycle`: Contains the core SPI classes.
- `src/main/resources/META-INF/services/`: Keycloak SPI registration file.
- `docker-compose.yml` & `Dockerfile`: Local testing environment definitions.