#!/usr/bin/env python3
"""Behaviour tests for deploy/deploy.sh using stubbed aws, docker, curl and sleep.

Runs with the stdlib only. Needs bash and jq on PATH. Invoke directly:
`python3 deploy/scripts/tests/test_deploy_script.py -v`
"""
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

DEPLOY_SCRIPT = Path(__file__).resolve().parents[2] / "deploy.sh"
NEW_TAG = "a" * 40
OLD_TAG = "b" * 40
REPO = "632154834220.dkr.ecr.ap-northeast-2.amazonaws.com/sairo-be"

AWS_STUB = """#!/usr/bin/env bash
case "$1" in
  ecr) echo token ;;
  sts) echo 111122223333 ;;
  secretsmanager)
    while [ $# -gt 0 ]; do
      if [ "$1" = "--secret-id" ]; then secret="$2"; fi
      shift
    done
    cat "${STUB_DIR}/secret-$(echo "${secret}" | tr '/' '_').json"
    ;;
esac
"""

DOCKER_STUB = """#!/usr/bin/env bash
echo "IMAGE_TAG=${IMAGE_TAG:-} $*" >> "${STUB_DIR}/docker.log"
if [ "$1" = "login" ]; then cat > /dev/null; exit 0; fi
case "$*" in
  *"run --rm migrate"*) exit "$(cat "${STUB_DIR}/migrate-exit" 2>/dev/null || echo 0)" ;;
  *"up -d api-alt"*)
    echo "${IMAGE_TAG}" > "${STUB_DIR}/current-tag"
    echo "${IMAGE_TAG}" > "${STUB_DIR}/running-api-alt" ;;
  *"up -d api"*)
    echo "${IMAGE_TAG}" > "${STUB_DIR}/current-tag"
    echo "${IMAGE_TAG}" > "${STUB_DIR}/running-api" ;;
  *" stop "*)
    for word in "$@"; do
      case "${word}" in api|api-alt) rm -f "${STUB_DIR}/running-${word}" ;; esac
    done ;;
  "image ls "*) cat "${STUB_DIR}/images" 2>/dev/null ;;
  "ps -a "*) cat "${STUB_DIR}/in-use" 2>/dev/null ;;
  "info "*) exit 1 ;;
  "rmi "*)
    if [ -f "${STUB_DIR}/rmi-fails" ]; then exit 1; fi
    if grep -qxF "$2" "${STUB_DIR}/in-use" 2>/dev/null; then exit 1; fi
    echo "$2" >> "${STUB_DIR}/removed" ;;
  "image prune"*) echo prune >> "${STUB_DIR}/removed" ;;
esac
exit 0
"""

CURL_STUB = """#!/usr/bin/env bash
url=""
for arg in "$@"; do
  case "${arg}" in http*) url="${arg}" ;; esac
done
case "${url}" in
  *:8099/*)
    printf '%s' "$(cat "${STUB_DIR}/maintenance-code" 2>/dev/null || echo 503)"
    exit 0 ;;
esac
count=$(cat "${STUB_DIR}/curl-count" 2>/dev/null || echo 0)
echo $((count + 1)) > "${STUB_DIR}/curl-count"
if grep -qx "$((count + 1))" "${STUB_DIR}/curl-fail-calls" 2>/dev/null; then exit 1; fi
case "${url}" in
  *:8081/*) slot=running-api-alt ;;
  *) slot=running-api ;;
esac
current=$(cat "${STUB_DIR}/${slot}" 2>/dev/null || echo none)
grep -qx "${current}" "${STUB_DIR}/healthy-tags" 2>/dev/null
"""

# `nginx -t` fails while the file named by the stub's `nginx-reject` token
# appears anywhere under the nginx directory.
NGINX_STUB = """#!/usr/bin/env bash
case "$1" in
  -t)
    if [ -f "${STUB_DIR}/nginx-reject" ] \\
      && grep -rqF "$(cat "${STUB_DIR}/nginx-reject")" "${SAIRO_NGINX_DIR}"; then
      echo "nginx: configuration file test failed" >&2
      exit 1
    fi
    echo "NGINX test" >> "${STUB_DIR}/docker.log" ;;
  -s) echo "NGINX reload" >> "${STUB_DIR}/docker.log" ;;
esac
exit 0
"""

SYSTEMCTL_STUB = """#!/usr/bin/env bash
echo "NGINX reload" >> "${STUB_DIR}/docker.log"
exit 0
"""

DF_STUB = """#!/usr/bin/env bash
if [ -f "${STUB_DIR}/removed" ] && [ -f "${STUB_DIR}/df-free-mb-after-prune" ]; then
  free=$(cat "${STUB_DIR}/df-free-mb-after-prune")
else
  free=$(cat "${STUB_DIR}/df-free-mb" 2>/dev/null || echo 999999)
fi
echo "Filesystem 1024-blocks Used Available Capacity Mounted"
echo "x 0 0 $((free * 1024)) 0% /"
"""

SLEEP_STUB = """#!/usr/bin/env bash
exit 0
"""

DB_SECRET = {
    "host": "db.invalid",
    "port": 5432,
    "dbname": "sairo",
    "username": "user",
    "password": "pw",
}

APP_SECRETS = {
    "restApiKey": "k",
    "clientSecret": "k",
    "hmacSecret": "k",
    "adminKey": "k",
    "appId": 1,
    "authTicketSecret": "k",
    "authRateLimitHmacSecret": "k",
    "authKakaoKeyHmacSecret": "k",
    "authUnsubscribeSecret": "k",
    "authKakaoKeyEncryptionSecret": "k",
    "billingKeyEncryptionSecret": "k",
}


@unittest.skipUnless(
    shutil.which("bash") and shutil.which("jq"), "bash and jq are required"
)
class DeployScriptTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="deploy-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.stub = self.tmp / "stub"
        self.bin = self.tmp / "bin"
        self.deploy_dir = self.tmp / "deploy"
        self.nginx_dir = self.tmp / "nginx"
        for directory in (self.stub, self.bin, self.deploy_dir, self.nginx_dir):
            directory.mkdir()
        (self.nginx_dir / "conf.d").mkdir()
        for name, body in (
            ("aws", AWS_STUB),
            ("docker", DOCKER_STUB),
            ("curl", CURL_STUB),
            ("sleep", SLEEP_STUB),
            ("df", DF_STUB),
            ("nginx", NGINX_STUB),
            ("systemctl", SYSTEMCTL_STUB),
        ):
            path = self.bin / name
            path.write_text(body, encoding="utf-8", newline="\n")
            path.chmod(0o755)
        self.write_secret("sairo_db_app", DB_SECRET)
        self.write_secret("sairo_db_migrator", DB_SECRET)
        self.write_secret("sairo_be_app-secrets", APP_SECRETS)

    def write_secret(self, name, value):
        (self.stub / f"secret-{name}.json").write_text(
            json.dumps(value), encoding="utf-8"
        )

    def set_healthy(self, *tags):
        (self.stub / "healthy-tags").write_text("\n".join(tags) + "\n", encoding="utf-8")

    def run_deploy(self, tag=NEW_TAG, extra_env=None):
        env = dict(os.environ)
        env.update(
            {
                "PATH": f"{self.bin}{os.pathsep}{env['PATH']}",
                "STUB_DIR": self.stub.as_posix(),
                "SAIRO_DEPLOY_DIR": self.deploy_dir.as_posix(),
                "SAIRO_NGINX_DIR": self.nginx_dir.as_posix(),
                "SAIRO_MEMINFO_FILE": (self.stub / "meminfo").as_posix(),
            }
        )
        env.update(extra_env or {})
        return subprocess.run(
            [os.environ.get("DEPLOY_TEST_BASH", "bash"), DEPLOY_SCRIPT.as_posix(), tag],
            env=env,
            capture_output=True,
            text=True,
            timeout=300,
        )

    def docker_log(self):
        path = self.stub / "docker.log"
        return path.read_text(encoding="utf-8") if path.exists() else ""

    def curl_count(self):
        path = self.stub / "curl-count"
        return int(path.read_text(encoding="utf-8")) if path.exists() else 0

    def read(self, name):
        return (self.deploy_dir / name).read_text(encoding="utf-8").strip()

    def test_healthy_deploy_records_last_good_and_verified_marker(self):
        self.set_healthy(NEW_TAG)

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.read("last-good-tag"), NEW_TAG)
        self.assertEqual(self.read("last-good-verified"), NEW_TAG)

    def test_unverified_previous_tag_is_not_used_for_rollback(self):
        (self.deploy_dir / "last-good-tag").write_text(OLD_TAG + "\n", encoding="utf-8")
        self.set_healthy(OLD_TAG)

        result = self.run_deploy()

        self.assertEqual(result.returncode, 1)
        self.assertNotIn(f"IMAGE_TAG={OLD_TAG} compose", self.docker_log())
        self.assertIn("no verified rollback target", result.stderr)
        self.assertEqual(self.read("last-good-tag"), OLD_TAG)
        self.assertFalse((self.deploy_dir / "last-good-verified").exists())

    def test_first_deploy_without_any_previous_tag_reports_failure_and_leaves_api_running(self):
        self.set_healthy()

        result = self.run_deploy()

        self.assertEqual(result.returncode, 1)
        self.assertIn("no verified rollback target", result.stderr)
        self.assertIn("logs --tail 80 api", self.docker_log())
        self.assertNotIn("stop api", self.docker_log())

    def test_verified_previous_tag_is_rolled_back_to(self):
        (self.deploy_dir / "last-good-tag").write_text(OLD_TAG + "\n", encoding="utf-8")
        (self.deploy_dir / "last-good-verified").write_text(OLD_TAG + "\n", encoding="utf-8")
        self.set_healthy(OLD_TAG)

        result = self.run_deploy()

        self.assertEqual(result.returncode, 1)
        self.assertIn(f"rolling back to {OLD_TAG}", result.stdout)
        self.assertIn(f"rollback to {OLD_TAG} is healthy", result.stdout)
        self.assertEqual((self.stub / "current-tag").read_text(encoding="utf-8").strip(), OLD_TAG)

    def test_verified_marker_for_a_different_tag_does_not_authorize_rollback(self):
        (self.deploy_dir / "last-good-tag").write_text(OLD_TAG + "\n", encoding="utf-8")
        (self.deploy_dir / "last-good-verified").write_text("c" * 40 + "\n", encoding="utf-8")
        self.set_healthy(OLD_TAG)

        result = self.run_deploy()

        self.assertEqual(result.returncode, 1)
        self.assertNotIn("rolling back", result.stdout)

    def test_default_health_wait_allows_sixty_attempts(self):
        self.set_healthy()

        self.run_deploy()

        self.assertEqual(self.curl_count(), 60)

    def test_health_wait_can_be_set_by_environment(self):
        self.set_healthy()

        self.run_deploy(extra_env={"HEALTH_WAIT_SECONDS": "30"})

        self.assertEqual(self.curl_count(), 10)

    def test_health_wait_can_be_set_by_secret_field(self):
        self.set_healthy()
        self.write_secret("sairo_be_app-secrets", {**APP_SECRETS, "healthWaitSeconds": 45})

        self.run_deploy()

        self.assertEqual(self.curl_count(), 15)

    def test_out_of_range_health_wait_is_rejected_before_deploying(self):
        for value in ("5", "901", "abc"):
            with self.subTest(value=value):
                result = self.run_deploy(extra_env={"HEALTH_WAIT_SECONDS": value})

                self.assertEqual(result.returncode, 1)
                self.assertIn("health wait must be an integer", result.stderr)
                self.assertNotIn("up -d api", self.docker_log())

    def read_env_lines(self):
        return (self.deploy_dir / "env" / "app.env").read_text(encoding="utf-8").splitlines()

    def test_toss_fields_are_delivered_to_app_env(self):
        self.set_healthy(NEW_TAG)
        self.write_secret(
            "sairo_be_app-secrets",
            {**APP_SECRETS, "tossSecretKey": "test_sk_fakeKey123", "tossWebhookSecret": "fake-hook"},
        )

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        lines = self.read_env_lines()
        self.assertIn("TOSS_SECRET_KEY=test_sk_fakeKey123", lines)
        self.assertIn("TOSS_WEBHOOK_SECRET=fake-hook", lines)
        self.assertNotIn("test_sk_fakeKey123", result.stdout + result.stderr)

    def test_missing_toss_fields_become_empty_values(self):
        self.set_healthy(NEW_TAG)

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        lines = self.read_env_lines()
        self.assertIn("TOSS_SECRET_KEY=", lines)
        self.assertIn("TOSS_WEBHOOK_SECRET=", lines)

    def test_live_prefixed_toss_key_is_accepted(self):
        self.set_healthy(NEW_TAG)
        self.write_secret(
            "sairo_be_app-secrets", {**APP_SECRETS, "tossSecretKey": "live_sk_fakeKey123"}
        )

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)

    def test_toss_key_with_wrong_prefix_is_rejected_without_echoing_the_value(self):
        for value in ("test_gsk_fakeKey123", "fakeKey123", "test_sk_", "test_sk_fake!Key"):
            with self.subTest(value=value):
                self.write_secret(
                    "sairo_be_app-secrets", {**APP_SECRETS, "tossSecretKey": value}
                )

                result = self.run_deploy()

                self.assertEqual(result.returncode, 1)
                self.assertIn("tossSecretKey must start with", result.stderr)
                self.assertNotIn("fake", result.stdout + result.stderr)
                self.assertNotIn("up -d api", self.docker_log())

    def test_multiline_toss_webhook_secret_is_rejected(self):
        self.write_secret(
            "sairo_be_app-secrets", {**APP_SECRETS, "tossWebhookSecret": "a\nb"}
        )

        result = self.run_deploy()

        self.assertEqual(result.returncode, 1)
        self.assertIn("tossWebhookSecret must be a single line", result.stderr)

    def test_edge_token_is_delivered_to_app_env(self):
        self.set_healthy(NEW_TAG)
        token = "a" * 64
        self.write_secret("sairo_be_app-secrets", {**APP_SECRETS, "edgeToken": token})

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(f"SAIRO_CLIENT_IP_EDGE_TOKEN={token}", self.read_env_lines())

    def test_missing_edge_token_is_not_written(self):
        self.set_healthy(NEW_TAG)

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(
            any(line.startswith("SAIRO_CLIENT_IP_EDGE_TOKEN") for line in self.read_env_lines())
        )

    def test_short_edge_token_is_rejected_before_deploying(self):
        self.write_secret("sairo_be_app-secrets", {**APP_SECRETS, "edgeToken": "short"})

        result = self.run_deploy()

        self.assertEqual(result.returncode, 1)
        self.assertIn("edgeToken must be", result.stderr)

    def test_lease_period_scan_cron_is_delivered_to_app_env(self):
        self.set_healthy(NEW_TAG)
        self.write_secret(
            "sairo_be_app-secrets", {**APP_SECRETS, "leasePeriodScanCron": "0 */10 * * * *"}
        )

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("SAIRO_LEASE_PERIOD_SCAN_CRON=0 */10 * * * *", self.read_env_lines())

    def test_missing_lease_period_scan_cron_is_not_written(self):
        self.set_healthy(NEW_TAG)

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(
            any(line.startswith("SAIRO_LEASE_PERIOD_SCAN_CRON") for line in self.read_env_lines())
        )

    def test_invalid_lease_period_scan_cron_is_rejected_before_deploying(self):
        for value in (
            "0 0 0 * *",
            "0 0 0 * * * *",
            "0 0 0 * * $(id)",
            "0 0 0 * * *;x",
            "a b c d e f",
        ):
            with self.subTest(value=value):
                self.write_secret(
                    "sairo_be_app-secrets", {**APP_SECRETS, "leasePeriodScanCron": value}
                )

                result = self.run_deploy()

                self.assertEqual(result.returncode, 1)
                self.assertIn("leasePeriodScanCron must be", result.stderr)
                self.assertNotIn("up -d api", self.docker_log())

    def read_redis_env(self):
        return (self.deploy_dir / "env" / "redis.env").read_text(encoding="utf-8").splitlines()

    def test_redis_password_is_delivered_to_app_and_redis_env(self):
        self.set_healthy(NEW_TAG)
        password = "Ab1_-" * 8
        self.write_secret("sairo_be_app-secrets", {**APP_SECRETS, "redisPassword": password})

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(f"REDIS_PASSWORD={password}", self.read_env_lines())
        self.assertIn(f"SPRING_DATA_REDIS_PASSWORD={password}", self.read_env_lines())
        self.assertEqual(self.read_redis_env(), [f"REDIS_PASSWORD={password}"])
        self.assertNotIn(password, result.stdout + result.stderr)
        if os.name == "posix":
            mode = (self.deploy_dir / "env" / "redis.env").stat().st_mode & 0o777
            self.assertEqual(mode, 0o600)

    def test_missing_redis_password_writes_nothing_and_clears_old_value(self):
        self.set_healthy(NEW_TAG)
        (self.deploy_dir / "env").mkdir()
        (self.deploy_dir / "env" / "redis.env").write_text("REDIS_PASSWORD=stale\n", encoding="utf-8")

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.read_redis_env(), [])
        self.assertFalse(
            any("REDIS_PASSWORD" in line for line in self.read_env_lines())
        )

    def test_weak_or_unsafe_redis_password_is_rejected_without_echo(self):
        for value in ("short", "a" * 31, "a" * 31 + " ", "a" * 31 + "$", "a" * 31 + "\n"):
            with self.subTest(value=value):
                self.write_secret(
                    "sairo_be_app-secrets", {**APP_SECRETS, "redisPassword": value}
                )

                result = self.run_deploy()

                self.assertEqual(result.returncode, 1)
                self.assertIn("redisPassword must be", result.stderr)
                self.assertNotIn("up -d api", self.docker_log())

    def test_migration_failure_leaves_api_untouched(self):
        (self.stub / "migrate-exit").write_text("1", encoding="utf-8")

        result = self.run_deploy()

        self.assertEqual(result.returncode, 1)
        self.assertNotIn("up -d api", self.docker_log())

    def set_images(self, *tags):
        (self.stub / "images").write_bytes(("\n".join(tags) + "\n").encode())

    def set_in_use(self, *tags):
        (self.stub / "in-use").write_bytes(
            ("\n".join(f"{REPO}:{tag}" for tag in tags) + "\n").encode()
        )

    def removed(self):
        path = self.stub / "removed"
        return path.read_text(encoding="utf-8").split() if path.exists() else []

    def removed_images(self):
        return [item for item in self.removed() if item != "prune"]

    def test_successful_deploy_keeps_new_last_good_and_previous_images_only(self):
        (self.deploy_dir / "last-good-tag").write_bytes((OLD_TAG + "\n").encode())
        self.set_healthy(NEW_TAG)
        extra = ["d" * 40, "e" * 40]
        self.set_images(NEW_TAG, OLD_TAG, *extra, "latest")
        self.set_in_use(NEW_TAG)

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            sorted(self.removed_images()), sorted(f"{REPO}:{tag}" for tag in extra)
        )

    def test_image_used_by_a_container_is_never_removed(self):
        self.set_healthy(NEW_TAG)
        busy = "d" * 40
        self.set_images(NEW_TAG, busy)
        self.set_in_use(NEW_TAG, busy)

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.removed_images(), [])

    def test_failed_removal_does_not_fail_a_healthy_deploy(self):
        self.set_healthy(NEW_TAG)
        self.set_images(NEW_TAG, "d" * 40)
        self.set_in_use()
        (self.stub / "rmi-fails").write_text("1", encoding="utf-8")

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.read("last-good-tag"), NEW_TAG)

    def test_failed_deploy_does_not_prune(self):
        (self.deploy_dir / "last-good-tag").write_bytes((OLD_TAG + "\n").encode())
        self.set_healthy()
        self.set_images(NEW_TAG, OLD_TAG, "d" * 40)

        result = self.run_deploy()

        self.assertEqual(result.returncode, 1)
        self.assertEqual(self.removed(), [])

    def test_enough_free_disk_skips_pre_pull_prune(self):
        self.set_healthy()
        self.set_images("d" * 40)
        (self.stub / "df-free-mb").write_text("100000", encoding="utf-8")

        self.run_deploy()

        self.assertEqual(self.removed(), [])

    def test_low_disk_prunes_before_pulling_and_keeps_last_good(self):
        (self.deploy_dir / "last-good-tag").write_bytes((OLD_TAG + "\n").encode())
        self.set_healthy(NEW_TAG)
        stale = "d" * 40
        self.set_images(OLD_TAG, stale)
        self.set_in_use(OLD_TAG)
        (self.stub / "df-free-mb").write_text("500", encoding="utf-8")
        (self.stub / "df-free-mb-after-prune").write_text("9000", encoding="utf-8")

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        log = self.docker_log()
        self.assertLess(log.index("rmi"), log.index("pull migrate"))
        self.assertIn(f"{REPO}:{stale}", self.removed_images())
        self.assertNotIn(f"{REPO}:{OLD_TAG}", self.removed_images())

    def test_still_low_disk_after_prune_aborts_without_touching_api(self):
        self.set_images("d" * 40)
        (self.stub / "df-free-mb").write_text("500", encoding="utf-8")

        result = self.run_deploy()

        self.assertEqual(result.returncode, 1)
        self.assertIn("still below", result.stderr)
        log = self.docker_log()
        self.assertNotIn("pull", log)
        self.assertNotIn("up -d api", log)
        self.assertNotIn("stop api", log)

    def test_invalid_min_free_disk_is_rejected(self):
        result = self.run_deploy(extra_env={"MIN_FREE_DISK_MB": "lots"})

        self.assertEqual(result.returncode, 1)
        self.assertIn("MIN_FREE_DISK_MB", result.stderr)

    # ---- zero-downtime deploy: nginx upstream and blue/green ----

    APP_CONF = (
        "server {\n"
        "  listen 443 ssl;\n"
        "  location /api/ {\n"
        "    proxy_set_header Host $host;\n"
        "    proxy_pass http://127.0.0.1:8080;\n"
        "  }\n"
        "  location / {\n"
        "    proxy_pass http://127.0.0.1:3000;\n"
        "  }\n"
        "}\n"
    )

    def write_app_conf(self, text=None):
        path = self.nginx_dir / "app.conf"
        path.write_bytes((text or self.APP_CONF).encode())
        return path

    def upstream_conf(self):
        return self.nginx_dir / "conf.d" / "sairo-api-upstream.conf"

    def upstream_port(self):
        for line in self.upstream_conf().read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line.startswith("server 127.0.0.1:") and "max_fails=0" in line:
                return line.split(":")[1].split()[0]
        return None

    def set_memory(self, available_mb):
        (self.stub / "meminfo").write_text(
            f"MemTotal: 2000000 kB\nMemAvailable: {available_mb * 1024} kB\n", encoding="utf-8"
        )

    def prepare_managed_nginx(self, port="8080", running=None, memory_mb=4000):
        """nginx already switched to the managed upstream, as after the first deploy."""
        self.write_app_conf(self.APP_CONF.replace("127.0.0.1:8080", "sairo_api"))
        text = (
            "upstream sairo_api {\n"
            f"    server 127.0.0.1:{port} max_fails=0;\n"
            "    server 127.0.0.1:8099 backup;\n"
            "}\n"
        )
        self.upstream_conf().write_text(text, encoding="utf-8")
        service = "api-alt" if port == "8081" else "api"
        (self.stub / f"running-{service}").write_text((running or OLD_TAG) + "\n", encoding="utf-8")
        self.set_memory(memory_mb)

    def docker_lines(self):
        return self.docker_log().splitlines()

    def index_of(self, needle, start=0):
        for index, line in enumerate(self.docker_lines()):
            if index >= start and needle in line:
                return index
        self.fail(f"{needle!r} not found in docker log:\n{self.docker_log()}")

    def started(self):
        return [
            line.rsplit(" ", 1)[1]
            for line in self.docker_lines()
            if " up -d " in line and not line.startswith("NGINX")
        ]

    def stopped(self):
        return [
            line.split(" stop ", 1)[1].split()
            for line in self.docker_lines()
            if " stop " in line
        ]

    def reloads(self):
        return [line for line in self.docker_lines() if line == "NGINX reload"]

    def test_first_deploy_points_nginx_at_the_managed_upstream_and_keeps_other_proxies(self):
        self.write_app_conf()
        self.set_healthy(NEW_TAG)

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        text = (self.nginx_dir / "app.conf").read_text(encoding="utf-8")
        self.assertIn("proxy_pass http://sairo_api;", text)
        self.assertIn("proxy_pass http://127.0.0.1:3000;", text)
        self.assertNotIn("127.0.0.1:8080", text)
        self.assertEqual(self.upstream_port(), "8080")
        # bootstrap, maintenance answer on for the in-place recreate, routing restored
        self.assertEqual(len(self.reloads()), 3)
        manifests = list((self.deploy_dir / "nginx-backup").glob("*/manifest"))
        self.assertEqual(len(manifests), 1)
        saved = manifests[0].parent / "0"
        self.assertEqual(saved.read_bytes(), self.APP_CONF.encode())

    def test_upstream_conf_has_backup_with_json_503_and_retry_after(self):
        self.write_app_conf()
        self.set_healthy(NEW_TAG)

        self.run_deploy()

        text = self.upstream_conf().read_text(encoding="utf-8")
        self.assertIn("server 127.0.0.1:8080 max_fails=0;", text)
        self.assertIn("server 127.0.0.1:8099 backup;", text)
        self.assertIn("listen 127.0.0.1:8099;", text)
        self.assertIn("default_type application/json;", text)
        self.assertIn("add_header Retry-After 5 always;", text)
        self.assertIn("return 503 ", text)
        self.assertIn('"code":"SERVICE_UNAVAILABLE"', text)

    def test_bootstrap_is_idempotent(self):
        self.write_app_conf()
        self.set_healthy(NEW_TAG)
        self.run_deploy()
        first = (self.nginx_dir / "app.conf").read_bytes()
        reloads = len(self.reloads())

        self.run_deploy()

        self.assertEqual((self.nginx_dir / "app.conf").read_bytes(), first)
        self.assertEqual(len(list((self.deploy_dir / "nginx-backup").glob("*/manifest"))), 1)
        # No bootstrap again; only the maintenance on/off pair of the in-place recreate.
        self.assertEqual(len(self.reloads()), reloads + 2)

    def test_rejected_nginx_config_is_restored_and_deploy_continues_in_place(self):
        self.write_app_conf()
        (self.stub / "nginx-reject").write_text("sairo_api", encoding="utf-8")
        self.set_healthy(NEW_TAG)

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.nginx_dir / "app.conf").read_bytes(), self.APP_CONF.encode())
        self.assertFalse(self.upstream_conf().exists())
        self.assertIn("restoring the previous configuration", result.stderr)
        self.assertEqual(self.started(), ["api"])

    def test_failed_probe_of_the_maintenance_listener_restores_nginx(self):
        self.write_app_conf()
        (self.stub / "maintenance-code").write_text("000", encoding="utf-8")
        self.set_healthy(NEW_TAG)

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.nginx_dir / "app.conf").read_bytes(), self.APP_CONF.encode())
        self.assertFalse(self.upstream_conf().exists())

    def test_nginx_proxy_without_explicit_host_header_is_left_alone(self):
        original = self.APP_CONF.replace("    proxy_set_header Host $host;\n", "")
        self.write_app_conf(original)
        self.set_healthy(NEW_TAG)

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.nginx_dir / "app.conf").read_bytes(), original.encode())
        self.assertFalse(self.upstream_conf().exists())
        self.assertEqual(self.reloads(), [])

    def test_no_matching_nginx_proxy_leaves_nginx_untouched(self):
        self.set_healthy(NEW_TAG)

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(self.upstream_conf().exists())
        self.assertEqual(self.reloads(), [])
        self.assertEqual(self.started(), ["api"])

    def test_migration_failure_does_not_touch_nginx(self):
        self.write_app_conf()
        (self.stub / "migrate-exit").write_text("1", encoding="utf-8")

        result = self.run_deploy()

        self.assertEqual(result.returncode, 1)
        self.assertEqual((self.nginx_dir / "app.conf").read_bytes(), self.APP_CONF.encode())
        self.assertFalse(self.upstream_conf().exists())

    def test_blue_green_starts_idle_slot_switches_nginx_then_stops_old_slot(self):
        self.prepare_managed_nginx(port="8080")
        self.set_healthy(NEW_TAG, OLD_TAG)
        (self.deploy_dir / "last-good-tag").write_text(OLD_TAG + "\n", encoding="utf-8")
        (self.deploy_dir / "last-good-verified").write_text(OLD_TAG + "\n", encoding="utf-8")

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.started(), ["api-alt"])
        self.assertEqual(self.upstream_port(), "8081")
        self.assertEqual(self.stopped(), [["api"]])
        up = self.index_of("up -d api-alt")
        reload_index = self.index_of("NGINX reload", up)
        stop = self.index_of(" stop api", reload_index)
        self.assertLess(up, reload_index)
        self.assertLess(reload_index, stop)
        self.assertEqual(self.read("last-good-tag"), NEW_TAG)
        self.assertEqual(self.read("last-good-verified"), NEW_TAG)

    def test_blue_green_waits_for_both_health_and_readiness_of_the_new_slot_port(self):
        self.prepare_managed_nginx(port="8080")
        self.set_healthy(NEW_TAG)

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        # health + readiness on each of three consecutive checks
        self.assertEqual(self.curl_count(), 6)

    def test_a_probe_that_drops_out_again_restarts_the_stability_count(self):
        self.prepare_managed_nginx(port="8080")
        self.set_healthy(NEW_TAG)
        # Call 5 is the health probe of the third check; it fails once.
        (self.stub / "curl-fail-calls").write_text("5\n", encoding="utf-8")

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        # two passing checks (4 calls), one failed health probe, three more passing checks (6 calls)
        self.assertEqual(self.curl_count(), 11)

    def test_never_stable_probe_is_not_promoted(self):
        self.prepare_managed_nginx(port="8080")
        (self.deploy_dir / "last-good-tag").write_text(OLD_TAG + "\n", encoding="utf-8")
        (self.deploy_dir / "last-good-verified").write_text(OLD_TAG + "\n", encoding="utf-8")
        self.set_healthy(NEW_TAG, OLD_TAG)
        # Every second call fails, so no check pair ever passes three times in a row.
        calls = "\n".join(str(n) for n in range(2, 400, 2)) + "\n"
        (self.stub / "curl-fail-calls").write_text(calls, encoding="utf-8")

        result = self.run_deploy()

        self.assertEqual(result.returncode, 1)
        self.assertEqual(self.upstream_port(), "8080")
        self.assertEqual(self.read("last-good-tag"), OLD_TAG)

    def test_next_blue_green_deploy_flips_back_to_the_primary_slot(self):
        self.prepare_managed_nginx(port="8081", running=OLD_TAG)
        self.set_healthy(NEW_TAG)

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.started(), ["api"])
        self.assertEqual(self.upstream_port(), "8080")
        self.assertEqual(self.stopped(), [["api-alt"]])

    def test_unready_new_slot_never_touches_nginx_or_the_live_slot(self):
        self.prepare_managed_nginx(port="8080")
        (self.deploy_dir / "last-good-tag").write_text(OLD_TAG + "\n", encoding="utf-8")
        (self.deploy_dir / "last-good-verified").write_text(OLD_TAG + "\n", encoding="utf-8")
        self.set_healthy(OLD_TAG)
        conf_before = self.upstream_conf().read_bytes()

        result = self.run_deploy()

        self.assertEqual(result.returncode, 1)
        self.assertEqual(self.upstream_conf().read_bytes(), conf_before)
        self.assertEqual(self.reloads(), [])
        self.assertEqual(self.stopped(), [["api-alt"]])
        self.assertEqual(self.started(), ["api-alt"])
        self.assertNotIn("rolling back", result.stdout)
        self.assertEqual(self.read("last-good-tag"), OLD_TAG)
        self.assertEqual(
            (self.stub / "running-api").read_text(encoding="utf-8").strip(), OLD_TAG
        )
        self.assertIn("keeps serving the previous version", result.stderr)

    def test_unready_new_slot_does_not_prune(self):
        self.prepare_managed_nginx(port="8080")
        self.set_healthy(OLD_TAG)
        self.set_images(NEW_TAG, OLD_TAG, "d" * 40)

        self.run_deploy()

        self.assertEqual(self.removed(), [])

    def test_rejected_switch_restores_nginx_and_keeps_the_live_slot(self):
        self.prepare_managed_nginx(port="8080")
        (self.deploy_dir / "last-good-tag").write_text(OLD_TAG + "\n", encoding="utf-8")
        self.set_healthy(NEW_TAG, OLD_TAG)
        (self.stub / "nginx-reject").write_text("127.0.0.1:8081", encoding="utf-8")

        result = self.run_deploy()

        self.assertEqual(result.returncode, 1)
        self.assertEqual(self.upstream_port(), "8080")
        self.assertEqual(self.stopped(), [["api-alt"]])
        self.assertEqual(self.read("last-good-tag"), OLD_TAG)
        self.assertIn("restoring the previous upstream", result.stderr)

    def test_low_memory_recreates_the_live_slot_in_place_without_switching(self):
        self.prepare_managed_nginx(port="8081", memory_mb=300)
        self.set_healthy(NEW_TAG)

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.started(), ["api-alt"])
        self.assertEqual(self.stopped(), [])
        self.assertEqual(self.upstream_port(), "8081")
        self.assertIn("in-place", result.stdout)
        self.assertIn("300MB available", result.stdout)
        # Maintenance answer on before the slot is recreated, routing restored after.
        up = self.index_of("up -d api-alt")
        self.assertEqual(len(self.reloads()), 2)
        self.assertLess(self.index_of("NGINX reload"), up)
        self.assertGreater(self.index_of("NGINX reload", up), up)
        self.assertNotIn(" down;", self.upstream_conf().read_text(encoding="utf-8"))

    def test_in_place_marks_the_live_slot_down_while_it_is_recreated(self):
        self.prepare_managed_nginx(port="8080", memory_mb=300)
        self.set_healthy(NEW_TAG)
        seen = self.stub / "conf-during-up"
        docker = self.bin / "docker"
        original = docker.read_text(encoding="utf-8")
        docker.write_text(
            original.replace(
                '*"up -d api"*)',
                '*"up -d api"*)\n    cp "${SAIRO_NGINX_DIR}/conf.d/sairo-api-upstream.conf" "${STUB_DIR}/conf-during-up"',
                1,
            ),
            encoding="utf-8",
        )

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("server 127.0.0.1:8080 max_fails=0 down;", seen.read_text(encoding="utf-8"))
        self.assertEqual(self.upstream_port(), "8080")

    def test_in_place_aborts_before_recreating_live_slot_if_maintenance_switch_fails(self):
        self.prepare_managed_nginx(port="8080", memory_mb=300)
        (self.deploy_dir / "last-good-tag").write_text(OLD_TAG + "\n", encoding="utf-8")
        (self.stub / "nginx-reject").write_text(" down;", encoding="utf-8")
        self.set_healthy(NEW_TAG)

        result = self.run_deploy()

        self.assertEqual(result.returncode, 1)
        self.assertIn("maintenance", result.stderr)
        self.assertEqual(self.started(), [])
        self.assertEqual(self.upstream_port(), "8080")
        self.assertNotIn(" down;", self.upstream_conf().read_text(encoding="utf-8"))
        self.assertEqual(self.read("last-good-tag"), OLD_TAG)

    def test_failed_in_place_deploy_without_rollback_target_restores_routing(self):
        self.prepare_managed_nginx(port="8080", memory_mb=300)

        result = self.run_deploy()

        self.assertEqual(result.returncode, 1)
        conf = self.upstream_conf().read_text(encoding="utf-8")
        self.assertNotIn(" down;", conf)
        self.assertEqual(self.upstream_port(), "8080")

    def test_unreadable_memory_falls_back_to_in_place(self):
        self.prepare_managed_nginx(port="8080")
        (self.stub / "meminfo").unlink()
        self.set_healthy(NEW_TAG)

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.started(), ["api"])
        self.assertEqual(self.stopped(), [])

    def test_memory_threshold_can_be_tuned_by_environment(self):
        self.prepare_managed_nginx(port="8080", memory_mb=500)
        self.set_healthy(NEW_TAG)

        result = self.run_deploy(extra_env={"OVERLAP_MIN_AVAILABLE_MB": "400"})

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.started(), ["api-alt"])

    def test_in_place_mode_can_be_forced(self):
        self.prepare_managed_nginx(port="8080")
        self.set_healthy(NEW_TAG)

        result = self.run_deploy(extra_env={"DEPLOY_MODE": "in-place"})

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.started(), ["api"])
        self.assertEqual(self.stopped(), [])

    def test_disabled_marker_forces_in_place_and_skips_bootstrap(self):
        self.write_app_conf()
        (self.deploy_dir / "zero-downtime-disabled").write_text("", encoding="utf-8")
        self.set_memory(4000)
        self.set_healthy(NEW_TAG)

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.nginx_dir / "app.conf").read_bytes(), self.APP_CONF.encode())
        self.assertEqual(self.started(), ["api"])

    def test_invalid_deploy_mode_is_rejected_before_deploying(self):
        result = self.run_deploy(extra_env={"DEPLOY_MODE": "fast"})

        self.assertEqual(result.returncode, 1)
        self.assertIn("DEPLOY_MODE", result.stderr)
        self.assertNotIn("up -d", self.docker_log())

    def test_in_place_failure_still_rolls_back_to_the_verified_tag_on_the_live_slot(self):
        self.prepare_managed_nginx(port="8081", memory_mb=300)
        (self.deploy_dir / "last-good-tag").write_text(OLD_TAG + "\n", encoding="utf-8")
        (self.deploy_dir / "last-good-verified").write_text(OLD_TAG + "\n", encoding="utf-8")
        self.set_healthy(OLD_TAG)

        result = self.run_deploy()

        self.assertEqual(result.returncode, 1)
        self.assertEqual(self.started(), ["api-alt", "api-alt"])
        self.assertIn(f"rollback to {OLD_TAG} is healthy", result.stdout)

    def test_blue_green_prune_keeps_new_previous_and_running_images(self):
        self.prepare_managed_nginx(port="8080")
        (self.deploy_dir / "last-good-tag").write_text(OLD_TAG + "\n", encoding="utf-8")
        self.set_healthy(NEW_TAG, OLD_TAG)
        stale = "d" * 40
        self.set_images(NEW_TAG, OLD_TAG, stale)
        self.set_in_use(NEW_TAG, OLD_TAG)

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.removed_images(), [f"{REPO}:{stale}"])

    def test_api_disabled_marker_stops_both_slots(self):
        (self.deploy_dir / "api-deploy-disabled").write_text("", encoding="utf-8")

        result = self.run_deploy()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.stopped(), [["api", "api-alt"]])
        self.assertNotIn("up -d", self.docker_log())


COMPOSE_FILE = DEPLOY_SCRIPT.parent / "docker-compose.prod.yml"


def compose_available():
    if not shutil.which("docker"):
        return False
    try:
        return (
            subprocess.run(
                ["docker", "compose", "version"], capture_output=True, timeout=60
            ).returncode
            == 0
        )
    except (OSError, subprocess.TimeoutExpired):
        return False


@unittest.skipUnless(compose_available(), "docker with the compose plugin is required")
class ApiSlotComposeTests(unittest.TestCase):
    def rendered(self):
        tmp = Path(tempfile.mkdtemp(prefix="slot-compose-"))
        self.addCleanup(shutil.rmtree, tmp, ignore_errors=True)
        shutil.copy(COMPOSE_FILE, tmp / "docker-compose.prod.yml")
        (tmp / "env").mkdir()
        for name in ("app.env", "migrate.env"):
            (tmp / "env" / name).write_text("", encoding="utf-8")
        env = dict(os.environ)
        env.update({"ECR_REPOSITORY_URI": "example.invalid/sairo-be", "IMAGE_TAG": NEW_TAG})
        result = subprocess.run(
            [
                "docker", "compose",
                "-f", (tmp / "docker-compose.prod.yml").as_posix(),
                "--project-directory", tmp.as_posix(),
                "config", "--format", "json",
            ],
            env=env,
            capture_output=True,
            text=True,
            timeout=120,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(result.stdout)["services"]

    def test_both_slots_run_the_same_image_on_distinct_loopback_ports(self):
        services = self.rendered()

        self.assertEqual(services["api"]["image"], services["api-alt"]["image"])
        self.assertEqual(services["api"]["image"], f"example.invalid/sairo-be:{NEW_TAG}")
        ports = {
            name: [(p["host_ip"], int(p["published"]), p["target"]) for p in services[name]["ports"]]
            for name in ("api", "api-alt")
        }
        self.assertEqual(ports["api"], [("127.0.0.1", 8080, 8080)])
        self.assertEqual(ports["api-alt"], [("127.0.0.1", 8081, 8080)])

    def test_slots_share_runtime_settings_and_allow_graceful_shutdown(self):
        services = self.rendered()

        for name in ("api", "api-alt"):
            with self.subTest(service=name):
                service = services[name]
                self.assertEqual(service["environment"]["SPRING_PROFILES_ACTIVE"], "prod")
                self.assertEqual(service["restart"], "unless-stopped")
                self.assertEqual(service["stop_grace_period"], "40s")
                self.assertIn("redis", service["depends_on"])
                self.assertEqual(service["volumes"][0]["source"], "bootstrap-data")


if __name__ == "__main__":
    unittest.main()
