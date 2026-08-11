#!/usr/bin/env bash
# Fixed deploy script installed on EC2 at /opt/sairo/deploy.sh
# Invoked via SSM Run Command with a single argument: the ECR image tag (commit SHA).
# Do not add `set -x` - secrets pass through shell variables in this script.
set -euo pipefail

for command in aws jq docker curl; do
  command -v "${command}" >/dev/null 2>&1 || {
    echo "[deploy] required command not found: ${command}" >&2
    exit 1
  }
done

IMAGE_TAG="${1:?Usage: deploy.sh <IMAGE_TAG>}"

if [[ ! "${IMAGE_TAG}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "[deploy] IMAGE_TAG must be a 40-character lowercase Git commit SHA" >&2
  exit 1
fi

AWS_REGION="ap-northeast-2"
ECR_REGISTRY="632154834220.dkr.ecr.ap-northeast-2.amazonaws.com"
ECR_REPOSITORY_URI="${ECR_REGISTRY}/sairo-be"
COMPOSE_DIR="/opt/sairo"
COMPOSE_FILE="${COMPOSE_DIR}/docker-compose.prod.yml"
ENV_DIR="${COMPOSE_DIR}/env"
ENV_FILE="${ENV_DIR}/app.env"
MIGRATE_ENV_FILE="${ENV_DIR}/migrate.env"
LAST_GOOD_FILE="${COMPOSE_DIR}/last-good-tag"
HEALTH_URL="http://127.0.0.1:8080/actuator/health"

mkdir -p "${ENV_DIR}"
chmod 700 "${ENV_DIR}"

echo "[deploy] logging in to ECR"
aws ecr get-login-password --region "${AWS_REGION}" \
  | docker login --username AWS --password-stdin "${ECR_REGISTRY}"

echo "[deploy] fetching secrets from Secrets Manager"
APP_SECRET_JSON=$(aws secretsmanager get-secret-value --region "${AWS_REGION}" \
  --secret-id sairo/db/app --query SecretString --output text)
MIGRATOR_SECRET_JSON=$(aws secretsmanager get-secret-value --region "${AWS_REGION}" \
  --secret-id sairo/db/migrator --query SecretString --output text)

validate_secret_shape() {
  local json="$1"
  local label="$2"
  jq -e '
    (.host | type == "string" and length > 0) and
    (
      .port |
      (type == "number" and . > 0) or
      (type == "string" and test("^[0-9]+$"))
    ) and
    (.dbname | type == "string" and length > 0) and
    (.username | type == "string" and length > 0) and
    (.password | type == "string" and length > 0)
  ' >/dev/null <<<"${json}" || {
    echo "[deploy] invalid ${label} secret structure" >&2
    exit 1
  }
}

validate_secret_shape "${APP_SECRET_JSON}" "app"
validate_secret_shape "${MIGRATOR_SECRET_JSON}" "migrator"

for key in host port dbname; do
  app_value=$(jq -r --arg key "${key}" '.[$key]' <<<"${APP_SECRET_JSON}")
  migrator_value=$(jq -r --arg key "${key}" '.[$key]' <<<"${MIGRATOR_SECRET_JSON}")

  if [[ "${app_value}" != "${migrator_value}" ]]; then
    echo "[deploy] app and migrator secrets reference different ${key} values" >&2
    exit 1
  fi
done

DB_HOST=$(jq -r .host <<<"${APP_SECRET_JSON}")
DB_PORT=$(jq -r .port <<<"${APP_SECRET_JSON}")
DB_NAME=$(jq -r .dbname <<<"${APP_SECRET_JSON}")
DB_APP_USERNAME=$(jq -r .username <<<"${APP_SECRET_JSON}")
DB_APP_PASSWORD=$(jq -r .password <<<"${APP_SECRET_JSON}")
DB_MIGRATOR_USERNAME=$(jq -r .username <<<"${MIGRATOR_SECRET_JSON}")
DB_MIGRATOR_PASSWORD=$(jq -r .password <<<"${MIGRATOR_SECRET_JSON}")

umask 077
{
  echo "DB_HOST=${DB_HOST}"
  echo "DB_PORT=${DB_PORT}"
  echo "DB_NAME=${DB_NAME}"
  echo "DB_APP_USERNAME=${DB_APP_USERNAME}"
  echo "DB_APP_PASSWORD=${DB_APP_PASSWORD}"
} > "${ENV_FILE}"
chmod 600 "${ENV_FILE}"

umask 077
{
  echo "DB_HOST=${DB_HOST}"
  echo "DB_PORT=${DB_PORT}"
  echo "DB_NAME=${DB_NAME}"
  echo "DB_MIGRATOR_USERNAME=${DB_MIGRATOR_USERNAME}"
  echo "DB_MIGRATOR_PASSWORD=${DB_MIGRATOR_PASSWORD}"
} > "${MIGRATE_ENV_FILE}"
chmod 600 "${MIGRATE_ENV_FILE}"

unset APP_SECRET_JSON MIGRATOR_SECRET_JSON DB_APP_PASSWORD DB_MIGRATOR_PASSWORD

PREVIOUS_TAG=""
if [ -f "${LAST_GOOD_FILE}" ]; then
  PREVIOUS_TAG=$(cat "${LAST_GOOD_FILE}")
fi

run_migration() {
  local tag="$1"
  echo "[deploy] pulling migrate image ${tag}"
  IMAGE_TAG="${tag}" ECR_REPOSITORY_URI="${ECR_REPOSITORY_URI}" \
    docker compose -f "${COMPOSE_FILE}" --project-directory "${COMPOSE_DIR}" pull migrate
  echo "[deploy] running migration for ${tag}"
  IMAGE_TAG="${tag}" ECR_REPOSITORY_URI="${ECR_REPOSITORY_URI}" \
    docker compose -f "${COMPOSE_FILE}" --project-directory "${COMPOSE_DIR}" run --rm migrate
}

deploy_tag() {
  local tag="$1"
  echo "[deploy] pulling ${tag}"
  IMAGE_TAG="${tag}" ECR_REPOSITORY_URI="${ECR_REPOSITORY_URI}" \
    docker compose -f "${COMPOSE_FILE}" --project-directory "${COMPOSE_DIR}" pull api
  echo "[deploy] starting ${tag}"
  IMAGE_TAG="${tag}" ECR_REPOSITORY_URI="${ECR_REPOSITORY_URI}" \
    docker compose -f "${COMPOSE_FILE}" --project-directory "${COMPOSE_DIR}" up -d api
}

wait_for_healthy() {
  for _ in $(seq 1 20); do
    if curl -fsS "${HEALTH_URL}" >/dev/null 2>&1; then
      return 0
    fi
    sleep 3
  done
  return 1
}

if ! run_migration "${IMAGE_TAG}"; then
  echo "[deploy] migration failed for ${IMAGE_TAG}, leaving current api container untouched" >&2
  exit 1
fi

deploy_tag "${IMAGE_TAG}"

echo "[deploy] waiting for health check"
if wait_for_healthy; then
  echo "[deploy] healthy, recording ${IMAGE_TAG} as last-good"
  echo "${IMAGE_TAG}" > "${LAST_GOOD_FILE}"
  exit 0
fi

echo "[deploy] health check failed for ${IMAGE_TAG}"
if [ -n "${PREVIOUS_TAG}" ] && [ "${PREVIOUS_TAG}" != "${IMAGE_TAG}" ]; then
  echo "[deploy] rolling back to ${PREVIOUS_TAG}"
  deploy_tag "${PREVIOUS_TAG}"

  echo "[deploy] waiting for health check after rollback"
  if wait_for_healthy; then
    echo "[deploy] rollback to ${PREVIOUS_TAG} is healthy"
  else
    echo "[deploy] rollback failed health check" >&2
  fi
else
  echo "[deploy] no previous good tag available to roll back to"
fi
exit 1
