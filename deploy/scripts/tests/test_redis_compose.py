#!/usr/bin/env python3
"""Runs the real redis service from deploy/docker-compose.prod.yml with and without a password.

Needs a reachable Docker daemon and the Compose plugin; skipped otherwise. Only the redis
service is started. Invoke directly: `python3 deploy/scripts/tests/test_redis_compose.py -v`
"""
import json
import os
import shutil
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

COMPOSE_FILE = Path(__file__).resolve().parents[2] / "docker-compose.prod.yml"
PASSWORD = "compose-test-redis-password-0123456789"


def docker_available():
    if not shutil.which("docker"):
        return False
    try:
        return (
            subprocess.run(
                ["docker", "compose", "version"], capture_output=True, timeout=60
            ).returncode
            == 0
            and subprocess.run(["docker", "info"], capture_output=True, timeout=60).returncode
            == 0
        )
    except (OSError, subprocess.TimeoutExpired):
        return False


@unittest.skipUnless(docker_available(), "docker with the compose plugin is required")
class RedisComposeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="redis-compose-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        shutil.copy(COMPOSE_FILE, self.tmp / "docker-compose.prod.yml")
        (self.tmp / "env").mkdir()
        for name in ("app.env", "migrate.env"):
            (self.tmp / "env" / name).write_text("", encoding="utf-8")
        self.project = f"redistest{os.getpid()}"
        self.addCleanup(self.compose, "down", "-v", "--remove-orphans", check=False)

    def compose(self, *args, check=True):
        env = dict(os.environ)
        env.update({"ECR_REPOSITORY_URI": "example.invalid/sairo-be", "IMAGE_TAG": "0" * 40})
        return subprocess.run(
            [
                "docker", "compose", "-p", self.project,
                "-f", (self.tmp / "docker-compose.prod.yml").as_posix(),
                "--project-directory", self.tmp.as_posix(),
                *args,
            ],
            env=env,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=300,
            check=check,
        )

    def redis_cli(self, *args, env=()):
        exec_env = [item for pair in (("-e", e) for e in env) for item in pair]
        return self.compose("exec", "-T", *exec_env, "redis", "redis-cli", *args, check=False)

    def wait_healthy(self):
        for _ in range(40):
            ps = self.compose("ps", "redis", "--format", "json").stdout.strip().splitlines()
            states = [json.loads(line).get("Health") for line in ps if line.strip()]
            if "healthy" in states:
                return
            time.sleep(1)
        self.fail("redis did not become healthy: " + self.compose("logs", "redis").stdout)

    def test_config_is_valid_and_redis_is_not_published_on_the_host(self):
        rendered = json.loads(self.compose("config", "--format", "json").stdout)
        self.assertNotIn("ports", rendered["services"]["redis"])
        self.assertNotIn("ports", rendered["services"]["scanner"])
        self.assertEqual(
            rendered["services"]["api"]["ports"][0]["host_ip"], "127.0.0.1"
        )

    def test_password_enables_authentication_and_healthcheck_passes(self):
        (self.tmp / "env" / "redis.env").write_text(
            f"REDIS_PASSWORD={PASSWORD}\n", encoding="utf-8"
        )

        self.compose("up", "-d", "--no-deps", "redis")
        self.wait_healthy()

        self.assertIn("NOAUTH", self.redis_cli("ping").stdout)
        self.assertEqual(
            self.redis_cli("ping", env=(f"REDISCLI_AUTH={PASSWORD}",)).stdout.strip(), "PONG"
        )
        wrong = self.redis_cli("ping", env=("REDISCLI_AUTH=" + "x" * 40,))
        self.assertIn("WRONGPASS", wrong.stdout + wrong.stderr)
        self.assertNotIn("PONG", wrong.stdout)
        # Persistence settings from the compose command must survive the entrypoint wrapper.
        self.assertIn(
            "yes",
            self.redis_cli("config", "get", "appendonly", env=(f"REDISCLI_AUTH={PASSWORD}",)).stdout,
        )
        self.assertIn(
            "134217728",
            self.redis_cli("config", "get", "maxmemory", env=(f"REDISCLI_AUTH={PASSWORD}",)).stdout,
        )
        inspect = subprocess.run(
            ["docker", "inspect", self.compose("ps", "-q", "redis").stdout.strip()],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=True,
        ).stdout
        self.assertEqual(json.loads(inspect)[0]["HostConfig"]["PortBindings"] or {}, {})
        user = self.compose("exec", "-T", "redis", "sh", "-c", "ps -o user,args | grep '[r]edis-server'", check=False)
        self.assertTrue(user.stdout.split()[0] == "redis", user.stdout)
    def test_without_password_behaviour_is_unchanged_and_healthcheck_passes(self):
        self.compose("up", "-d", "--no-deps", "redis")
        self.wait_healthy()

        self.assertEqual(self.redis_cli("ping").stdout.strip(), "PONG")


if __name__ == "__main__":
    unittest.main()
