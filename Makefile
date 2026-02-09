SHELL := /bin/sh

.PHONY: start stop restart logs ps build pull clean shell db deploy setup  gogo

start: setup
	@docker compose up -d

stop:
	docker compose down

restart:
	docker compose restart

logs:
	docker compose logs -f --tail=200

ps:
	docker compose ps

pull:
	docker compose pull

build:
	docker compose build

shell:
	docker compose exec liferay /bin/bash

gogo:
	@echo "Connecting to Gogo shell (Ctrl+C to exit)..."
	@docker compose exec liferay telnet localhost 11311

db:
	docker compose exec postgres psql -U $${POSTGRES_USER:-liferay} -d $${POSTGRES_DB:-liferay}

deploy:
	@(cd liferay && ./gradlew dockerDeploy -Pliferay.workspace.environment=dockerenv)

setup:
	@cp -n .env.example .env || true
	@echo "Created .env from .env.example (if it did not exist)."

clean:
	docker compose down -v
