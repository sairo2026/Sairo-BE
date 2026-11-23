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
COMPOSE_DIR="${SAIRO_DEPLOY_DIR:-/opt/sairo}"
COMPOSE_FILE="${COMPOSE_DIR}/docker-compose.prod.yml"
ENV_DIR="${COMPOSE_DIR}/env"
ENV_FILE="${ENV_DIR}/app.env"
MIGRATE_ENV_FILE="${ENV_DIR}/migrate.env"
LAST_GOOD_FILE="${COMPOSE_DIR}/last-good-tag"
VERIFIED_GOOD_FILE="${COMPOSE_DIR}/last-good-verified"
API_DISABLED_FILE="${COMPOSE_DIR}/api-deploy-disabled"

# Zero-downtime layout. The api runs in one of two compose slots with the same
# image: `api` on host port 8080 and `api-alt` on 8081. nginx reaches the live
# slot through the `sairo_api` upstream defined in NGINX_UPSTREAM_CONF; the
# upstream also lists a backup answering 503 with a JSON body and Retry-After,
# so even a gap with no live slot is a clean, retryable 503 instead of a 502.
#
# Blue/green (start the idle slot, switch nginx, stop the old slot) runs two
# JVMs at once for about a minute, so it is used only when the host has enough
# available memory. Both versions then run against the same database for that
# time, which requires every migration to be backward compatible (expand
# first, contract in a later release): the old version must keep working
# against the schema the new version's migration produced. The migration step
# still runs before anything is switched. Otherwise the live slot is recreated
# in place, as before, with the backup 503 covering the gap.
SLOT_PRIMARY_SERVICE="api"
SLOT_PRIMARY_PORT=8080
SLOT_ALT_SERVICE="api-alt"
SLOT_ALT_PORT=8081
MAINTENANCE_PORT=8099
NGINX_DIR="${SAIRO_NGINX_DIR:-/etc/nginx}"
NGINX_UPSTREAM_CONF="${NGINX_DIR}/conf.d/sairo-api-upstream.conf"
NGINX_BACKUP_ROOT="${COMPOSE_DIR}/nginx-backup"
ZERO_DOWNTIME_DISABLED_FILE="${COMPOSE_DIR}/zero-downtime-disabled"
MEMINFO_FILE="${SAIRO_MEMINFO_FILE:-/proc/meminfo}"
DEPLOY_MODE="${DEPLOY_MODE:-auto}"
OVERLAP_MIN_AVAILABLE_MB="${OVERLAP_MIN_AVAILABLE_MB:-900}"
DRAIN_SECONDS="${DRAIN_SECONDS:-5}"
if [[ "${DEPLOY_MODE}" != "auto" && "${DEPLOY_MODE}" != "in-place" ]]; then
  echo "[deploy] DEPLOY_MODE must be auto or in-place" >&2
  exit 1
fi
if [[ ! "${OVERLAP_MIN_AVAILABLE_MB}" =~ ^[0-9]+$ || ! "${DRAIN_SECONDS}" =~ ^[0-9]+$ ]]; then
  echo "[deploy] OVERLAP_MIN_AVAILABLE_MB and DRAIN_SECONDS must be non-negative integers" >&2
  exit 1
fi

mkdir -p "${ENV_DIR}"
chmod 700 "${ENV_DIR}"

MIN_FREE_DISK_MB="${MIN_FREE_DISK_MB:-3072}"
if [[ ! "${MIN_FREE_DISK_MB}" =~ ^[0-9]+$ ]]; then
  echo "[deploy] MIN_FREE_DISK_MB must be a non-negative integer" >&2
  exit 1
fi

# Removes sairo-be images that are neither in the keep list nor referenced by
# any container. Never forces removal (no -f), so docker itself also refuses to
# delete an image a container still uses. Every failure is tolerated: pruning
# must never change the outcome of a deploy or touch a running container.
prune_old_images() {
  local keep=" $* "
  local in_use tag
  in_use=$(docker ps -a --format '{{.Image}}' 2>/dev/null || true)
  while IFS= read -r tag; do
    [[ "${tag}" =~ ^[0-9a-f]{40}$ ]] || continue
    [[ "${keep}" == *" ${tag} "* ]] && continue
    if grep -qxF "${ECR_REPOSITORY_URI}:${tag}" <<<"${in_use}"; then
      continue
    fi
    echo "[deploy] removing old image ${tag}"
    docker rmi "${ECR_REPOSITORY_URI}:${tag}" >/dev/null 2>&1 \
      || echo "[deploy] could not remove image ${tag}, skipping" >&2
  done < <(docker image ls "${ECR_REPOSITORY_URI}" --format '{{.Tag}}' 2>/dev/null || true)
  docker image prune -f >/dev/null 2>&1 || true
  return 0
}

free_disk_mb() {
  local root
  root=$(docker info --format '{{.DockerRootDir}}' 2>/dev/null || true)
  if [ -z "${root}" ] || [ ! -d "${root}" ]; then
    root="${COMPOSE_DIR}"
  fi
  df -Pk "${root}" 2>/dev/null | awk 'NR==2 {print int($4 / 1024)}'
}

ensure_free_disk() {
  local free kept_tag=""
  free=$(free_disk_mb || true)
  if [[ ! "${free}" =~ ^[0-9]+$ ]]; then
    echo "[deploy] could not determine free disk space, continuing" >&2
    return 0
  fi
  if [ "${free}" -ge "${MIN_FREE_DISK_MB}" ]; then
    return 0
  fi
  echo "[deploy] free disk ${free}MB is below ${MIN_FREE_DISK_MB}MB, pruning old images"
  if [ -f "${LAST_GOOD_FILE}" ]; then
    kept_tag=$(cat "${LAST_GOOD_FILE}")
  fi
  prune_old_images "${kept_tag}" "${IMAGE_TAG}"
  free=$(free_disk_mb || true)
  if [[ "${free}" =~ ^[0-9]+$ ]] && [ "${free}" -lt "${MIN_FREE_DISK_MB}" ]; then
    echo "[deploy] free disk ${free}MB is still below ${MIN_FREE_DISK_MB}MB, aborting before any change" >&2
    exit 1
  fi
}

ensure_free_disk

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
SAIRO_SMS_PROVIDER=$(jq -r '.smsProvider // "none"' <<<"${APP_SECRETS_JSON}")
RESEND_API_KEY=$(jq -r '.resendApiKey // ""' <<<"${APP_SECRETS_JSON}")
SOLAPI_API_KEY=$(jq -r '.solapiApiKey // ""' <<<"${APP_SECRETS_JSON}")
SOLAPI_API_SECRET=$(jq -r '.solapiApiSecret // ""' <<<"${APP_SECRETS_JSON}")
SOLAPI_SENDER_NUMBER=$(jq -r '.solapiSenderNumber // ""' <<<"${APP_SECRETS_JSON}")
TOSS_SECRET_KEY=$(jq -r '.tossSecretKey // "" | tostring' <<<"${APP_SECRETS_JSON}")
TOSS_WEBHOOK_SECRET=$(jq -r '.tossWebhookSecret // "" | tostring' <<<"${APP_SECRETS_JSON}")
if [[ -n "${TOSS_SECRET_KEY}" && ! "${TOSS_SECRET_KEY}" =~ ^(test|live)_sk_[A-Za-z0-9_-]+$ ]]; then
  echo "[deploy] tossSecretKey must start with test_sk_ or live_sk_ followed by letters, digits, underscore or hyphen" >&2
  exit 1
fi
if [[ "${TOSS_WEBHOOK_SECRET}" == *$'\n'* || "${TOSS_WEBHOOK_SECRET}" == *$'\r'* ]]; then
  echo "[deploy] tossWebhookSecret must be a single line" >&2
  exit 1
fi
SECRET_HEALTH_WAIT_SECONDS=$(jq -r '.healthWaitSeconds // "" | tostring' <<<"${APP_SECRETS_JSON}")
SAIRO_BATCH_ENABLED=$(jq -r 'if .batchEnabled == null then "" else (.batchEnabled | tostring) end' <<<"${APP_SECRETS_JSON}")
if [[ -n "${SAIRO_BATCH_ENABLED}" && "${SAIRO_BATCH_ENABLED}" != "true" && "${SAIRO_BATCH_ENABLED}" != "false" ]]; then
  echo "[deploy] batchEnabled must be true or false" >&2
  exit 1
fi
SAIRO_BILLING_CHARGING_STARTED=$(jq -r 'if .billingChargingStarted == null then "false" else (.billingChargingStarted | tostring) end' <<<"${APP_SECRETS_JSON}")
if [[ "${SAIRO_BILLING_CHARGING_STARTED}" != "true" && "${SAIRO_BILLING_CHARGING_STARTED}" != "false" ]]; then
  echo "[deploy] billingChargingStarted must be true or false" >&2
  exit 1
fi
SAIRO_LEASE_PERIOD_SCAN_CRON=$(jq -r '.leasePeriodScanCron // "" | tostring' <<<"${APP_SECRETS_JSON}")
if [[ -n "${SAIRO_LEASE_PERIOD_SCAN_CRON}" && ! "${SAIRO_LEASE_PERIOD_SCAN_CRON}" =~ ^[0-9*/,?-]+(\ [0-9*/,?-]+){5}$ ]]; then
  echo "[deploy] leasePeriodScanCron must be a six-field cron expression using digits and the characters * / , ? -" >&2
  exit 1
fi
SAIRO_CLIENT_IP_EDGE_TOKEN=$(jq -r '.edgeToken // "" | tostring' <<<"${APP_SECRETS_JSON}")
if [[ -n "${SAIRO_CLIENT_IP_EDGE_TOKEN}" && ! "${SAIRO_CLIENT_IP_EDGE_TOKEN}" =~ ^[A-Za-z0-9_-]{32,}$ ]]; then
  echo "[deploy] edgeToken must be at least 32 characters of letters, digits, underscore or hyphen" >&2
  exit 1
fi
REDIS_PASSWORD=$(jq -r '.redisPassword // "" | tostring' <<<"${APP_SECRETS_JSON}")
if [[ -n "${REDIS_PASSWORD}" && ! "${REDIS_PASSWORD}" =~ ^[A-Za-z0-9_-]{32,}$ ]]; then
  echo "[deploy] redisPassword must be at least 32 characters of letters, digits, underscore or hyphen" >&2
  exit 1
fi
SAIRO_BOOTSTRAP_OPERATOR_ENABLED=$(jq -r 'if .bootstrapOperatorEnabled == null then "" else (.bootstrapOperatorEnabled | tostring) end' <<<"${APP_SECRETS_JSON}")
SAIRO_BOOTSTRAP_OPERATOR_EMAIL=$(jq -r '.bootstrapOperatorEmail // ""' <<<"${APP_SECRETS_JSON}")
if [[ -n "${SAIRO_BOOTSTRAP_OPERATOR_ENABLED}" && "${SAIRO_BOOTSTRAP_OPERATOR_ENABLED}" != "true" && "${SAIRO_BOOTSTRAP_OPERATOR_ENABLED}" != "false" ]]; then
  echo "[deploy] bootstrapOperatorEnabled must be true or false" >&2
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
  echo "TOSS_SECRET_KEY=${TOSS_SECRET_KEY}"
  echo "TOSS_WEBHOOK_SECRET=${TOSS_WEBHOOK_SECRET}"
  echo "SAIRO_BILLING_CHARGING_STARTED=${SAIRO_BILLING_CHARGING_STARTED}"
  echo "SAIRO_MAIL_FROM=${SAIRO_MAIL_FROM}"
  echo "SAIRO_STORAGE_BUCKET=${SAIRO_STORAGE_BUCKET}"
  if [[ -n "${SAIRO_BATCH_ENABLED}" ]]; then
    echo "SAIRO_BATCH_ENABLED=${SAIRO_BATCH_ENABLED}"
  fi
  if [[ -n "${SAIRO_LEASE_PERIOD_SCAN_CRON}" ]]; then
    echo "SAIRO_LEASE_PERIOD_SCAN_CRON=${SAIRO_LEASE_PERIOD_SCAN_CRON}"
  fi
  if [[ -n "${SAIRO_CLIENT_IP_EDGE_TOKEN}" ]]; then
    echo "SAIRO_CLIENT_IP_EDGE_TOKEN=${SAIRO_CLIENT_IP_EDGE_TOKEN}"
  fi
  if [[ -n "${REDIS_PASSWORD}" ]]; then
    echo "REDIS_PASSWORD=${REDIS_PASSWORD}"
    # Same value under Spring's own property name so images built before the
    # REDIS_PASSWORD mapping existed can still authenticate during a rollback.
    echo "SPRING_DATA_REDIS_PASSWORD=${REDIS_PASSWORD}"
  fi
  if [[ -n "${SAIRO_BOOTSTRAP_OPERATOR_ENABLED}" ]]; then
    echo "SAIRO_BOOTSTRAP_OPERATOR_ENABLED=${SAIRO_BOOTSTRAP_OPERATOR_ENABLED}"
  fi
  if [[ -n "${SAIRO_BOOTSTRAP_OPERATOR_EMAIL}" ]]; then
    echo "SAIRO_BOOTSTRAP_OPERATOR_EMAIL=${SAIRO_BOOTSTRAP_OPERATOR_EMAIL}"
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

REDIS_ENV_FILE="${ENV_DIR}/redis.env"
umask 077
{
  if [[ -n "${REDIS_PASSWORD}" ]]; then
    echo "REDIS_PASSWORD=${REDIS_PASSWORD}"
  fi
} > "${REDIS_ENV_FILE}"
chmod 600 "${REDIS_ENV_FILE}"

unset REDIS_PASSWORD APP_SECRET_JSON MIGRATOR_SECRET_JSON APP_SECRETS_JSON DB_APP_PASSWORD DB_MIGRATOR_PASSWORD \
  KAKAO_REST_API_KEY KAKAO_CLIENT_SECRET KAKAO_ADMIN_KEY KAKAO_APP_ID PUBLIC_LINK_HMAC_SECRET \
  AUTH_TICKET_SECRET AUTH_RATE_LIMIT_HMAC_SECRET AUTH_KAKAO_KEY_HMAC_SECRET \
  AUTH_UNSUBSCRIBE_SECRET AUTH_KAKAO_KEY_ENCRYPTION_SECRET BILLING_KEY_ENCRYPTION_SECRET \
  RESEND_API_KEY SOLAPI_API_KEY SOLAPI_API_SECRET SOLAPI_SENDER_NUMBER \
  TOSS_SECRET_KEY TOSS_WEBHOOK_SECRET SAIRO_BILLING_CHARGING_STARTED SAIRO_CLIENT_IP_EDGE_TOKEN COMPANY_ENV_LINES

HEALTH_WAIT_SECONDS="${HEALTH_WAIT_SECONDS:-${SECRET_HEALTH_WAIT_SECONDS:-180}}"
HEALTH_INTERVAL_SECONDS=3
if [[ ! "${HEALTH_WAIT_SECONDS}" =~ ^[0-9]+$ ]] \
  || [ "${HEALTH_WAIT_SECONDS}" -lt 30 ] || [ "${HEALTH_WAIT_SECONDS}" -gt 900 ]; then
  echo "[deploy] health wait must be an integer between 30 and 900 seconds" >&2
  exit 1
fi
HEALTH_ATTEMPTS=$(( (HEALTH_WAIT_SECONDS + HEALTH_INTERVAL_SECONDS - 1) / HEALTH_INTERVAL_SECONDS ))

PREVIOUS_TAG=""
if [ -f "${LAST_GOOD_FILE}" ]; then
  PREVIOUS_TAG=$(cat "${LAST_GOOD_FILE}")
fi
ROLLBACK_TAG=""
if [ -n "${PREVIOUS_TAG}" ] && [ -f "${VERIFIED_GOOD_FILE}" ] \
  && [ "$(cat "${VERIFIED_GOOD_FILE}")" = "${PREVIOUS_TAG}" ]; then
  ROLLBACK_TAG="${PREVIOUS_TAG}"
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

compose_run() {
  IMAGE_TAG="${IMAGE_TAG}" ECR_REPOSITORY_URI="${ECR_REPOSITORY_URI}" \
    docker compose -f "${COMPOSE_FILE}" --project-directory "${COMPOSE_DIR}" "$@"
}

deploy_tag() {
  local tag="$1"
  local service="$2"
  echo "[deploy] pulling ${tag} for ${service}"
  IMAGE_TAG="${tag}" ECR_REPOSITORY_URI="${ECR_REPOSITORY_URI}" \
    docker compose -f "${COMPOSE_FILE}" --project-directory "${COMPOSE_DIR}" pull "${service}"
  echo "[deploy] starting ${tag} as ${service}"
  IMAGE_TAG="${tag}" ECR_REPOSITORY_URI="${ECR_REPOSITORY_URI}" \
    docker compose -f "${COMPOSE_FILE}" --project-directory "${COMPOSE_DIR}" up -d "${service}"
}

# Only reports healthy once the slot's own port answers both the aggregate
# health and the readiness probe.
wait_for_healthy() {
  local port="$1"
  for _ in $(seq 1 "${HEALTH_ATTEMPTS}"); do
    if curl -fsS "http://127.0.0.1:${port}/actuator/health" >/dev/null 2>&1 \
      && curl -fsS "http://127.0.0.1:${port}/actuator/health/readiness" >/dev/null 2>&1; then
      return 0
    fi
    sleep "${HEALTH_INTERVAL_SECONDS}"
  done
  return 1
}

stop_service() {
  IMAGE_TAG="${IMAGE_TAG}" ECR_REPOSITORY_URI="${ECR_REPOSITORY_URI}" \
    docker compose -f "${COMPOSE_FILE}" --project-directory "${COMPOSE_DIR}" stop "$@"
}

service_for_port() {
  if [ "$1" = "${SLOT_ALT_PORT}" ]; then
    echo "${SLOT_ALT_SERVICE}"
  else
    echo "${SLOT_PRIMARY_SERVICE}"
  fi
}

other_port() {
  if [ "$1" = "${SLOT_ALT_PORT}" ]; then
    echo "${SLOT_PRIMARY_PORT}"
  else
    echo "${SLOT_ALT_PORT}"
  fi
}

available_memory_mb() {
  awk '/^MemAvailable:/ {print int($2 / 1024)}' "${MEMINFO_FILE}" 2>/dev/null || true
}

# ---- nginx upstream management ------------------------------------------------
# Every nginx change is validated with `nginx -t` before the reload and is
# restored automatically when validation, the reload or the post-reload probe
# fails. None of these functions may fail a deploy by themselves.

nginx_reload() {
  if command -v systemctl >/dev/null 2>&1; then
    systemctl reload nginx
  else
    nginx -s reload
  fi
}

render_upstream_conf() {
  local port="$1"
  cat <<EOF
# Managed by deploy.sh from the Sairo-BE repository. Do not edit by hand.
# sairo_api points at the live api slot. The backup server below only answers
# when that slot refuses connections, so a deploy gap is a retryable 503.
upstream sairo_api {
    server 127.0.0.1:${port} max_fails=0;
    server 127.0.0.1:${MAINTENANCE_PORT} backup;
}

server {
    listen 127.0.0.1:${MAINTENANCE_PORT};
    access_log off;

    location / {
        default_type application/json;
        add_header Retry-After 5 always;
        add_header Cache-Control "no-store" always;
        return 503 '{"code":"SERVICE_UNAVAILABLE","message":"서버를 업데이트하는 중입니다. 잠시 후 다시 시도해 주세요."}';
    }
}
EOF
}

nginx_current_port() {
  sed -n 's/^[[:space:]]*server 127\.0\.0\.1:\([0-9][0-9]*\) max_fails=0;.*/\1/p' \
    "${NGINX_UPSTREAM_CONF}" 2>/dev/null | head -n 1
}

nginx_routing_ready() {
  [ -f "${NGINX_UPSTREAM_CONF}" ] \
    && grep -rqE 'proxy_pass[[:space:]]+http://sairo_api' "${NGINX_DIR}" 2>/dev/null
}

# Succeeds only when nginx accepts the config, reloads, and the backup
# listener of the freshly loaded config answers 503.
nginx_validate_and_reload() {
  nginx -t >/dev/null 2>&1 || return 1
  nginx_reload >/dev/null 2>&1 || return 1
  local code
  for _ in 1 2 3 4 5; do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 \
      "http://127.0.0.1:${MAINTENANCE_PORT}/" 2>/dev/null || true)
    if [ "${code}" = "503" ]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

nginx_switch_port() {
  local port="$1"
  local saved
  # Called inside `if`, where `set -e` does not apply: check each step.
  saved=$(mktemp "${COMPOSE_DIR}/sairo-api-upstream.conf.XXXXXX") || return 1
  if ! cp -p "${NGINX_UPSTREAM_CONF}" "${saved}"; then
    rm -f "${saved}"
    return 1
  fi
  if { render_upstream_conf "${port}" > "${NGINX_UPSTREAM_CONF}" \
    && chmod 644 "${NGINX_UPSTREAM_CONF}" \
    && nginx_validate_and_reload; }; then
    rm -f "${saved}"
    return 0
  fi
  echo "[deploy] nginx did not accept the switch to port ${port}, restoring the previous upstream" >&2
  cp -p "${saved}" "${NGINX_UPSTREAM_CONF}"
  rm -f "${saved}"
  if ! { nginx -t >/dev/null 2>&1 && nginx_reload >/dev/null 2>&1; }; then
    echo "[deploy] WARNING: nginx could not be reloaded with the restored upstream, check it manually" >&2
  fi
  return 1
}

# One-time (idempotent) switch of the existing `proxy_pass http://127.0.0.1:8080`
# lines to the managed upstream. Only touches files that match exactly, keeps
# a copy of each, and puts everything back if nginx rejects the result.
nginx_bootstrap() {
  if ! command -v nginx >/dev/null 2>&1 || [ ! -d "${NGINX_DIR}" ]; then
    echo "[deploy] nginx not found, skipping nginx upstream management"
    return 1
  fi
  if nginx_routing_ready; then
    return 0
  fi
  if [ -f "${ZERO_DOWNTIME_DISABLED_FILE}" ]; then
    echo "[deploy] ${ZERO_DOWNTIME_DISABLED_FILE} exists, nginx upstream management is off"
    return 1
  fi
  local files=() file
  while IFS= read -r file; do
    if [ -n "${file}" ] && [ "${file}" != "${NGINX_UPSTREAM_CONF}" ]; then
      files+=("${file}")
    fi
  done < <(grep -rlE 'proxy_pass[[:space:]]+http://(127\.0\.0\.1|localhost):8080' "${NGINX_DIR}" 2>/dev/null || true)
  if [ "${#files[@]}" -eq 0 ]; then
    echo "[deploy] no nginx proxy_pass to 127.0.0.1:8080 found, skipping nginx upstream management" >&2
    return 1
  fi
  for file in "${files[@]}"; do
    # The upstream name changes the default Host header, so only rewrite files
    # that set it explicitly.
    if ! grep -qE 'proxy_set_header[[:space:]]+Host[[:space:]]' "${file}"; then
      echo "[deploy] ${file} does not set the Host header explicitly, skipping nginx upstream management" >&2
      return 1
    fi
  done

  local backup_dir index=0
  backup_dir="${NGINX_BACKUP_ROOT}/$(date -u +%Y%m%dT%H%M%SZ)"
  # This function runs inside an `if`, where `set -e` does not apply, so every
  # step that matters checks its own result.
  if ! mkdir -p "${backup_dir}" || ! : > "${backup_dir}/manifest"; then
    echo "[deploy] could not create ${backup_dir}, skipping nginx upstream management" >&2
    return 1
  fi
  for file in "${files[@]}"; do
    if ! cp -p "${file}" "${backup_dir}/${index}"; then
      echo "[deploy] could not back up ${file}, skipping nginx upstream management" >&2
      return 1
    fi
    printf '%s\t%s\n' "${index}" "${file}" >> "${backup_dir}/manifest"
    index=$((index + 1))
  done

  echo "[deploy] pointing nginx at the managed sairo_api upstream, originals saved in ${backup_dir}"
  local tab=$'\t' saved_index saved_file applied=true
  for file in "${files[@]}"; do
    sed -i -E 's#(proxy_pass[[:space:]]+http://)(127\.0\.0\.1|localhost):8080#\1sairo_api#' "${file}" \
      || applied=false
  done
  if [ "${applied}" = "true" ]; then
    { render_upstream_conf "${SLOT_PRIMARY_PORT}" > "${NGINX_UPSTREAM_CONF}" \
      && chmod 644 "${NGINX_UPSTREAM_CONF}" \
      && nginx_validate_and_reload; } && return 0
  fi

  echo "[deploy] nginx rejected the upstream change, restoring the previous configuration" >&2
  while IFS="${tab}" read -r saved_index saved_file; do
    cp -p "${backup_dir}/${saved_index}" "${saved_file}"
  done < "${backup_dir}/manifest"
  rm -f "${NGINX_UPSTREAM_CONF}"
  if ! { nginx -t >/dev/null 2>&1 && nginx_reload >/dev/null 2>&1; }; then
    echo "[deploy] WARNING: nginx could not be reloaded with the restored configuration, check it manually" >&2
  fi
  return 1
}

if ! run_migration "${IMAGE_TAG}"; then
  echo "[deploy] migration failed for ${IMAGE_TAG}, leaving current api container untouched" >&2
  exit 1
fi

if [ -f "${API_DISABLED_FILE}" ]; then
  echo "[deploy] migration applied, api deployment is disabled by ${API_DISABLED_FILE}"
  stop_service "${SLOT_PRIMARY_SERVICE}" "${SLOT_ALT_SERVICE}"
  echo "[deploy] api stopped, remove ${API_DISABLED_FILE} and redeploy to resume api deployment"
  exit 0
fi

# Decide where the new version runs. nginx's upstream is the source of truth
# for which slot is live; without managed nginx the primary slot is used.
NGINX_MANAGED=false
if nginx_bootstrap; then
  NGINX_MANAGED=true
fi
ACTIVE_PORT="${SLOT_PRIMARY_PORT}"
if [ "${NGINX_MANAGED}" = "true" ]; then
  ACTIVE_PORT=$(nginx_current_port || true)
  if [ "${ACTIVE_PORT}" != "${SLOT_PRIMARY_PORT}" ] && [ "${ACTIVE_PORT}" != "${SLOT_ALT_PORT}" ]; then
    ACTIVE_PORT="${SLOT_PRIMARY_PORT}"
  fi
fi
ACTIVE_SERVICE=$(service_for_port "${ACTIVE_PORT}")

TARGET_PORT="${ACTIVE_PORT}"
USE_BLUE_GREEN=false
if [ "${NGINX_MANAGED}" != "true" ]; then
  echo "[deploy] deploy mode: in-place (nginx upstream is not managed)"
elif [ "${DEPLOY_MODE}" = "in-place" ] || [ -f "${ZERO_DOWNTIME_DISABLED_FILE}" ]; then
  echo "[deploy] deploy mode: in-place (requested)"
else
  AVAILABLE_MB=$(available_memory_mb)
  if [[ ! "${AVAILABLE_MB}" =~ ^[0-9]+$ ]]; then
    echo "[deploy] deploy mode: in-place (available memory could not be read)"
  elif [ "${AVAILABLE_MB}" -lt "${OVERLAP_MIN_AVAILABLE_MB}" ]; then
    echo "[deploy] deploy mode: in-place (${AVAILABLE_MB}MB available, ${OVERLAP_MIN_AVAILABLE_MB}MB needed to run two api instances)"
  else
    USE_BLUE_GREEN=true
    TARGET_PORT=$(other_port "${ACTIVE_PORT}")
    echo "[deploy] deploy mode: blue/green (${AVAILABLE_MB}MB available), ${ACTIVE_SERVICE} stays live until the new instance is ready"
  fi
fi
TARGET_SERVICE=$(service_for_port "${TARGET_PORT}")

deploy_tag "${IMAGE_TAG}" "${TARGET_SERVICE}"

echo "[deploy] waiting up to ${HEALTH_WAIT_SECONDS}s for health check"
if wait_for_healthy "${TARGET_PORT}"; then
  if [ "${USE_BLUE_GREEN}" = "true" ]; then
    echo "[deploy] ${TARGET_SERVICE} is ready, switching nginx to port ${TARGET_PORT}"
    if ! nginx_switch_port "${TARGET_PORT}"; then
      echo "[deploy] nginx switch failed, stopping ${TARGET_SERVICE}; ${ACTIVE_SERVICE} keeps serving the previous version" >&2
      stop_service "${TARGET_SERVICE}" || true
      exit 1
    fi
    sleep "${DRAIN_SECONDS}"
    echo "[deploy] stopping previous instance ${ACTIVE_SERVICE}"
    stop_service "${ACTIVE_SERVICE}" || echo "[deploy] could not stop ${ACTIVE_SERVICE}, continuing" >&2
  elif [ "${NGINX_MANAGED}" = "true" ] && [ "${ACTIVE_PORT}" != "${TARGET_PORT}" ]; then
    nginx_switch_port "${TARGET_PORT}" || true
  fi
  echo "[deploy] healthy, recording ${IMAGE_TAG} as last-good"
  echo "${IMAGE_TAG}" > "${LAST_GOOD_FILE}"
  echo "${IMAGE_TAG}" > "${VERIFIED_GOOD_FILE}"
  prune_old_images "${IMAGE_TAG}" "${PREVIOUS_TAG}" "${ROLLBACK_TAG}"
  exit 0
fi

echo "[deploy] health check failed for ${IMAGE_TAG}"
if [ "${USE_BLUE_GREEN}" = "true" ]; then
  # Nothing was switched, so the previous version never stopped serving and
  # there is nothing to roll back.
  echo "[deploy] ${IMAGE_TAG} never became ready, ${ACTIVE_SERVICE} keeps serving the previous version" >&2
  compose_run logs --tail 80 "${TARGET_SERVICE}" >&2 || true
  stop_service "${TARGET_SERVICE}" || true
  exit 1
fi
if [ -n "${ROLLBACK_TAG}" ] && [ "${ROLLBACK_TAG}" != "${IMAGE_TAG}" ]; then
  echo "[deploy] rolling back to ${ROLLBACK_TAG}"
  deploy_tag "${ROLLBACK_TAG}" "${TARGET_SERVICE}"

  echo "[deploy] waiting for health check after rollback"
  if wait_for_healthy "${TARGET_PORT}"; then
    echo "[deploy] rollback to ${ROLLBACK_TAG} is healthy"
  else
    echo "[deploy] rollback failed health check" >&2
  fi
else
  echo "[deploy] no verified rollback target, ${IMAGE_TAG} is left running for inspection" >&2
  echo "[deploy] the previous image was never confirmed healthy against the current schema, so no automatic rollback was attempted" >&2
  compose_run ps "${TARGET_SERVICE}" >&2 || true
  compose_run logs --tail 80 "${TARGET_SERVICE}" >&2 || true
fi
exit 1
