# One-word entry points for the demo. Works with Docker (default) or Podman:
#   make up COMPOSE="podman compose"

COMPOSE ?= docker compose

.PHONY: up down demo-reset check test-db logs phone

## Build and start MySQL + Go API + React dashboard
up:
	$(COMPOSE) up -d --build

## Stop the stack (data volume is kept)
down:
	$(COMPOSE) down

## Restore the clean demo database (schema.sql + seed.sql) and restart the API
demo-reset:
	COMPOSE="$(COMPOSE)" ./scripts/demo-reset.sh

## Run every automated check (Go, MySQL, Android, React) and restore the seed
check:
	./scripts/check-all.sh

## MySQL test suite only (installs pytest into .venv, uses the container's mysql client)
test-db:
	COMPOSE="$(COMPOSE)" ./scripts/test-db.sh

## Follow the API log
logs:
	$(COMPOSE) logs -f api-go

## USB phone: forward the phone's 127.0.0.1:8080 to this computer's API
phone:
	adb reverse tcp:8080 tcp:8080
