#!/usr/bin/env sh
set -eu

docker compose -f docker-compose.yml -f docker-compose.prod.yml --profile prod stop frontend-prod backend-prod mongodb-prod
