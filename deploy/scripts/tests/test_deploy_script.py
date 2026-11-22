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
  *"up -d api"*) echo "${IMAGE_TAG}" > "${STUB_DIR}/current-tag" ;;
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
count=$(cat "${STUB_DIR}/curl-count" 2>/dev/null || echo 0)
echo $((count + 1)) > "${STUB_DIR}/curl-count"
current=$(cat "${STUB_DIR}/current-tag" 2>/dev/null || echo none)
grep -qx "${current}" "${STUB_DIR}/healthy-tags" 2>/dev/null
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
        for directory in (self.stub, self.bin, self.deploy_dir):
            directory.mkdir()
        for name, body in (
            ("aws", AWS_STUB),
            ("docker", DOCKER_STUB),
            ("curl", CURL_STUB),
            ("sleep", SLEEP_STUB),
            ("df", DF_STUB),
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


if __name__ == "__main__":
    unittest.main()
