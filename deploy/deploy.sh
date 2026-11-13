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
API_DISABLED_FILE="${COMPOSE_DIR}/api-deploy-disabled"
HEALTH_URL="http://127.0.0.1:8080/actuator/health"

mkdir -p "${ENV_DIR}"
chmod 700 "${ENV_DIR}"

echo "[deploy] logging in to ECR"
aws ecr get-login-password --region "${AWS_REGION}" \
  | docker login --username AWS --password-stdin "${ECR_REGISTRY}"

KAKAO_REDIRECT_URI_PROD="https://app.sairo.agency/api/auth/kakao/callback"
KAKAO_STEP_UP_REDIRECT_URI="https://app.sairo.agency/api/auth/step-up/kakao/callback"
KAKAO_LINK_REDIRECT_URI="https://app.sairo.agency/settings/login-methods/kakao-callback"
KAKAO_OPERATOR_REDIRECT_URI="https://app.sairo.agency/api/ops/auth/kakao/callback"
KAKAO_OPERATOR_INVITATION_REDIRECT_URI="https://app.sairo.agency/api/ops/auth/invitations/kakao/callback"
SAIRO_MAIL_DOMAIN="sairo.agency"
SAIRO_MAIL_FROM="noreply@${SAIRO_MAIL_DOMAIN}"
SAIRO_STORAGE_BUCKET="sairo-prod-objects-$(aws sts get-caller-identity --query Account --output text)-${AWS_REGION}"

echo "[deploy] fetching secrets from Secrets Manager"
APP_SECRET_JSON=$(aws secretsmanager get-secret-value --region "${AWS_REGION}" \
  --secret-id sairo/db/app --query SecretString --output text)
MIGRATOR_SECRET_JSON=$(aws secretsmanager get-secret-value --region "${AWS_REGION}" \
  --secret-id sairo/db/migrator --query SecretString --output text)
APP_SECRETS_JSON=$(aws secretsmanager get-secret-value --region "${AWS_REGION}" \
  --secret-id sairo/be/app-secrets --query SecretString --output text)

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

validate_app_secrets_shape() {
  local json="$1"
  jq -e '
    (.restApiKey | type == "string" and length > 0) and
    (.clientSecret | type == "string" and length > 0) and
    (.hmacSecret | type == "string" and length > 0) and
    (.adminKey | type == "string" and length > 0) and
    (.appId | (type == "string" and length > 0) or (type == "number" and . > 0)) and
    (.authTicketSecret | type == "string" and length > 0) and
    (.authRateLimitHmacSecret | type == "string" and length > 0) and
    (.authKakaoKeyHmacSecret | type == "string" and length > 0) and
    (.authUnsubscribeSecret | type == "string" and length > 0) and
    (.authKakaoKeyEncryptionSecret | type == "string" and length > 0) and
    (.billingKeyEncryptionSecret | type == "string" and length > 0)
  ' >/dev/null <<<"${json}" || {
    echo "[deploy] invalid sairo/be/app-secrets structure" >&2
    exit 1
  }
}

validate_app_secrets_shape "${APP_SECRETS_JSON}"

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

KAKAO_REST_API_KEY=$(jq -r .restApiKey <<<"${APP_SECRETS_JSON}")
KAKAO_CLIENT_SECRET=$(jq -r .clientSecret <<<"${APP_SECRETS_JSON}")
KAKAO_ADMIN_KEY=$(jq -r .adminKey <<<"${APP_SECRETS_JSON}")
KAKAO_APP_ID=$(jq -r .appId <<<"${APP_SECRETS_JSON}")
PUBLIC_LINK_HMAC_SECRET=$(jq -r .hmacSecret <<<"${APP_SECRETS_JSON}")
AUTH_TICKET_SECRET=$(jq -r .authTicketSecret <<<"${APP_SECRETS_JSON}")
AUTH_RATE_LIMIT_HMAC_SECRET=$(jq -r .authRateLimitHmacSecret <<<"${APP_SECRETS_JSON}")
AUTH_KAKAO_KEY_HMAC_SECRET=$(jq -r .authKakaoKeyHmacSecret <<<"${APP_SECRETS_JSON}")
AUTH_UNSUBSCRIBE_SECRET=$(jq -r .authUnsubscribeSecret <<<"${APP_SECRETS_JSON}")
AUTH_KAKAO_KEY_ENCRYPTION_SECRET=$(jq -r .authKakaoKeyEncryptionSecret <<<"${APP_SECRETS_JSON}")
BILLING_KEY_ENCRYPTION_SECRET=$(jq -r .billingKeyEncryptionSecret <<<"${APP_SECRETS_JSON}")
SAIRO_MAIL_PROVIDER=$(jq -r '.mailProvider // "ses"' <<<"${APP_SECRETS_JSON}")
SAIRO_SMS_PROVIDER=$(jq -r '.smsProvider // "aws"' <<<"${APP_SECRETS_JSON}")
RESEND_API_KEY=$(jq -r '.resendApiKey // ""' <<<"${APP_SECRETS_JSON}")
SOLAPI_API_KEY=$(jq -r '.solapiApiKey // ""' <<<"${APP_SECRETS_JSON}")
SOLAPI_API_SECRET=$(jq -r '.solapiApiSecret // ""' <<<"${APP_SECRETS_JSON}")
SOLAPI_SENDER_NUMBER=$(jq -r '.solapiSenderNumber // ""' <<<"${APP_SECRETS_JSON}")
SAIRO_BATCH_ENABLED=$(jq -r 'if .batchEnabled == null then "" else (.batchEnabled | tostring) end' <<<"${APP_SECRETS_JSON}")
if [[ -n "${SAIRO_BATCH_ENABLED}" && "${SAIRO_BATCH_ENABLED}" != "true" && "${SAIRO_BATCH_ENABLED}" != "false" ]]; then
  echo "[deploy] batchEnabled must be true or false" >&2
  exit 1
fi

COMPANY_ENV_LINES=()
for pair in \
  companyContactPhone:COMPANY_CONTACT_PHONE \
  companyEcommerceNotice:COMPANY_ECOMMERCE_NOTICE \
  companySupportEmail:COMPANY_SUPPORT_EMAIL \
  companyPrivacyContact:COMPANY_PRIVACY_CONTACT \
  companyPrivacyRequestPath:COMPANY_PRIVACY_REQUEST_PATH; do
  company_field="${pair%%:*}"
  company_name="${pair##*:}"
  company_value=$(jq -r --arg field "${company_field}" '.[$field] // "" | tostring' <<<"${APP_SECRETS_JSON}")
  if [[ "${company_value}" == *$'\n'* || "${company_value}" == *$'\r'* ]]; then
    echo "[deploy] ${company_field} must be a single line" >&2
    exit 1
  fi
  if [[ -n "${company_value}" ]]; then
    COMPANY_ENV_LINES+=("${company_name}=${company_value}")
  fi
done

umask 077
{
  echo "DB_HOST=${DB_HOST}"
  echo "DB_PORT=${DB_PORT}"
  echo "DB_NAME=${DB_NAME}"
  echo "DB_APP_USERNAME=${DB_APP_USERNAME}"
  echo "DB_APP_PASSWORD=${DB_APP_PASSWORD}"
  echo "KAKAO_REST_API_KEY=${KAKAO_REST_API_KEY}"
  echo "KAKAO_CLIENT_SECRET=${KAKAO_CLIENT_SECRET}"
  echo "KAKAO_ADMIN_KEY=${KAKAO_ADMIN_KEY}"
  echo "KAKAO_APP_ID=${KAKAO_APP_ID}"
  echo "KAKAO_REDIRECT_URI_PROD=${KAKAO_REDIRECT_URI_PROD}"
  echo "KAKAO_STEP_UP_REDIRECT_URI=${KAKAO_STEP_UP_REDIRECT_URI}"
  echo "KAKAO_LINK_REDIRECT_URI=${KAKAO_LINK_REDIRECT_URI}"
  echo "KAKAO_OPERATOR_REDIRECT_URI=${KAKAO_OPERATOR_REDIRECT_URI}"
  echo "KAKAO_OPERATOR_INVITATION_REDIRECT_URI=${KAKAO_OPERATOR_INVITATION_REDIRECT_URI}"
  echo "PUBLIC_LINK_HMAC_SECRET=${PUBLIC_LINK_HMAC_SECRET}"
  echo "AUTH_TICKET_SECRET=${AUTH_TICKET_SECRET}"
  echo "AUTH_RATE_LIMIT_HMAC_SECRET=${AUTH_RATE_LIMIT_HMAC_SECRET}"
  echo "AUTH_KAKAO_KEY_HMAC_SECRET=${AUTH_KAKAO_KEY_HMAC_SECRET}"
  echo "AUTH_UNSUBSCRIBE_SECRET=${AUTH_UNSUBSCRIBE_SECRET}"
  echo "AUTH_KAKAO_KEY_ENCRYPTION_SECRET=${AUTH_KAKAO_KEY_ENCRYPTION_SECRET}"
  echo "BILLING_KEY_ENCRYPTION_SECRET=${BILLING_KEY_ENCRYPTION_SECRET}"
  echo "SAIRO_MAIL_PROVIDER=${SAIRO_MAIL_PROVIDER}"
  echo "SAIRO_SMS_PROVIDER=${SAIRO_SMS_PROVIDER}"
  echo "RESEND_API_KEY=${RESEND_API_KEY}"
  echo "SOLAPI_API_KEY=${SOLAPI_API_KEY}"
  echo "SOLAPI_API_SECRET=${SOLAPI_API_SECRET}"
  echo "SOLAPI_SENDER_NUMBER=${SOLAPI_SENDER_NUMBER}"
  echo "SAIRO_MAIL_FROM=${SAIRO_MAIL_FROM}"
  echo "SAIRO_STORAGE_BUCKET=${SAIRO_STORAGE_BUCKET}"
  if [[ -n "${SAIRO_BATCH_ENABLED}" ]]; then
    echo "SAIRO_BATCH_ENABLED=${SAIRO_BATCH_ENABLED}"
  fi
  if [[ ${#COMPANY_ENV_LINES[@]} -gt 0 ]]; then
    printf '%s\n' "${COMPANY_ENV_LINES[@]}"
  fi
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

unset APP_SECRET_JSON MIGRATOR_SECRET_JSON APP_SECRETS_JSON DB_APP_PASSWORD DB_MIGRATOR_PASSWORD \
  KAKAO_REST_API_KEY KAKAO_CLIENT_SECRET KAKAO_ADMIN_KEY KAKAO_APP_ID PUBLIC_LINK_HMAC_SECRET \
  AUTH_TICKET_SECRET AUTH_RATE_LIMIT_HMAC_SECRET AUTH_KAKAO_KEY_HMAC_SECRET \
  AUTH_UNSUBSCRIBE_SECRET AUTH_KAKAO_KEY_ENCRYPTION_SECRET BILLING_KEY_ENCRYPTION_SECRET \
  RESEND_API_KEY SOLAPI_API_KEY SOLAPI_API_SECRET SOLAPI_SENDER_NUMBER COMPANY_ENV_LINES

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

if [ -f "${API_DISABLED_FILE}" ]; then
  echo "[deploy] migration applied, api deployment is disabled by ${API_DISABLED_FILE}"
  IMAGE_TAG="${IMAGE_TAG}" ECR_REPOSITORY_URI="${ECR_REPOSITORY_URI}" \
    docker compose -f "${COMPOSE_FILE}" --project-directory "${COMPOSE_DIR}" stop api
  echo "[deploy] api stopped, remove ${API_DISABLED_FILE} and redeploy to resume api deployment"
  exit 0
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
