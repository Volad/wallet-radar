#!/usr/bin/env sh
set -eu

docker compose -f docker-compose.yml -f docker-compose.prod.yml --profile prod up -d mongodb-prod backend-prod frontend-prod
