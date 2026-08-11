#!/usr/bin/env python3
"""Unit tests for deploy/scripts/security_watch.py.

Runs with the stdlib only (unittest + unittest.mock) so CI needs no extra
pip install step. Invoke directly: `python3 deploy/scripts/tests/test_security_watch.py -v`
"""
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import security_watch as sw  # noqa: E402


def write(path: Path, text: str):
    path.write_text(text, encoding="utf-8")
    return path


class NormalizeIpv4Tests(unittest.TestCase):
    def test_sorts_and_dedupes(self):
        lines = [
            "173.245.48.0/20",
            "103.21.244.0/22",
            "103.21.244.0/22",  # duplicate
            "",  # blank line ignored
            "104.16.0.0/13",
        ]
        result = sw.normalize_ipv4_lines(lines)
        self.assertEqual(
            result,
            ["103.21.244.0/22", "104.16.0.0/13", "173.245.48.0/20"],
        )

    def test_rejects_ipv6(self):
        with self.assertRaises(ValueError):
            sw.normalize_ipv4_lines(["2400:cb00::/32"])

    def test_rejects_invalid_cidr(self):
        with self.assertRaises(ValueError):
            sw.normalize_ipv4_lines(["not-an-ip"])

    def test_rejects_empty_list(self):
        with self.assertRaises(ValueError):
            sw.normalize_ipv4_lines(["", "   ", ""])


class IpDriftTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.tmpdir = Path(self.tmp.name)

    def test_no_drift_does_not_create_issue(self):
        baseline = write(self.tmpdir / "baseline.txt", "103.21.244.0/22\n104.16.0.0/13\n")
        live = write(self.tmpdir / "live.txt", "104.16.0.0/13\n103.21.244.0/22\n")
        with patch.object(sw, "create_issue") as mock_create:
            sw.check_ip_drift(live, dry_run=False, baseline_path=baseline)
        mock_create.assert_not_called()

    def test_drift_distinguishes_added_and_removed(self):
        baseline = write(self.tmpdir / "baseline.txt", "103.21.244.0/22\n104.16.0.0/13\n")
        live = write(self.tmpdir / "live.txt", "104.16.0.0/13\n203.0.113.0/24\n")
        with patch.object(sw, "create_issue") as mock_create:
            sw.check_ip_drift(live, dry_run=False, baseline_path=baseline)
        mock_create.assert_called_once()
        title, body = mock_create.call_args.args[0], mock_create.call_args.args[1]
        self.assertIn("infra: Cloudflare IPv4 대역 변경 감지", title)
        self.assertIn("[cf-ipv4:", title)
        self.assertIn("203.0.113.0/24", body)  # added
        self.assertIn("103.21.244.0/22", body)  # removed
        self.assertIn("추가된 대역: 203.0.113.0/24", body)
        self.assertIn("제거된 대역: 103.21.244.0/22", body)

    def test_missing_baseline_raises(self):
        live = write(self.tmpdir / "live.txt", "104.16.0.0/13\n")
        missing = self.tmpdir / "does-not-exist.txt"
        with self.assertRaises(FileNotFoundError):
            sw.check_ip_drift(live, dry_run=False, baseline_path=missing)


class CertExpiryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.tmpdir = Path(self.tmp.name)
        self.now = datetime(2026, 1, 1, tzinfo=timezone.utc)

    def _expiry_path(self, days_from_now: int, hours: int = 0) -> Path:
        expiry = self.now + timedelta(days=days_from_now, hours=hours)
        raw = expiry.strftime("%Y-%m-%dT%H:%M:%SZ")
        return write(self.tmpdir / "expiry.txt", raw)

    def test_beyond_all_thresholds_no_issue(self):
        # 181 days remaining: still outside the D-180 threshold.
        path = self._expiry_path(181, hours=1)
        with patch.object(sw, "create_issue") as mock_create:
            sw.check_cert_expiry(dry_run=False, cert_path=path, now=self.now)
        mock_create.assert_not_called()

    def test_d180_boundary(self):
        path = self._expiry_path(180)
        with patch.object(sw, "create_issue") as mock_create:
            sw.check_cert_expiry(dry_run=False, cert_path=path, now=self.now)
        mock_create.assert_called_once()
        title = mock_create.call_args.args[0]
        self.assertIn("D-180", title)

    def test_d90_boundary(self):
        path = self._expiry_path(90)
        with patch.object(sw, "create_issue") as mock_create:
            sw.check_cert_expiry(dry_run=False, cert_path=path, now=self.now)
        title = mock_create.call_args.args[0]
        self.assertIn("D-90", title)

    def test_d30_boundary(self):
        path = self._expiry_path(30)
        with patch.object(sw, "create_issue") as mock_create:
            sw.check_cert_expiry(dry_run=False, cert_path=path, now=self.now)
        title = mock_create.call_args.args[0]
        self.assertIn("D-30", title)

    def test_d0_boundary(self):
        path = self._expiry_path(0)  # expires exactly now: days_remaining == 0
        with patch.object(sw, "create_issue") as mock_create:
            sw.check_cert_expiry(dry_run=False, cert_path=path, now=self.now)
        mock_create.assert_called_once()
        title = mock_create.call_args.args[0]
        self.assertIn("D-0", title)

    def test_expired_cert_uses_d0(self):
        path = self._expiry_path(-5)  # 5 days past expiry, no threshold below 0 exists
        with patch.object(sw, "create_issue") as mock_create:
            sw.check_cert_expiry(dry_run=False, cert_path=path, now=self.now)
        mock_create.assert_called_once()
        title = mock_create.call_args.args[0]
        self.assertIn("D-0", title)

    def test_only_nearest_threshold_selected_at_d19(self):
        path = self._expiry_path(19)
        with patch.object(sw, "create_issue") as mock_create:
            sw.check_cert_expiry(dry_run=False, cert_path=path, now=self.now)
        # Must fire exactly once, for D-30 only (not D-180 or D-90 too).
        mock_create.assert_called_once()
        title = mock_create.call_args.args[0]
        self.assertIn("D-30", title)
        self.assertNotIn("D-180", title)
        self.assertNotIn("D-90", title)

    def test_missing_baseline_raises(self):
        missing = self.tmpdir / "does-not-exist.txt"
        with self.assertRaises(FileNotFoundError):
            sw.check_cert_expiry(dry_run=False, cert_path=missing, now=self.now)


class CreateIssueDedupTests(unittest.TestCase):
    def test_skips_when_identifier_already_exists(self):
        with patch.object(sw, "issue_exists", return_value=True) as mock_exists, \
             patch.object(sw, "gh") as mock_gh:
            sw.create_issue("infra: something [cf-ipv4:abc123456789]", "body", dry_run=False)
        mock_exists.assert_called_once_with("[cf-ipv4:abc123456789]", False)
        mock_gh.assert_not_called()

    def test_creates_when_identifier_not_found(self):
        with patch.object(sw, "issue_exists", return_value=False), \
             patch.object(sw, "gh", return_value="") as mock_gh:
            sw.create_issue("infra: something [cf-ipv4:abc123456789]", "body", dry_run=False)
        mock_gh.assert_called_once()
        args = mock_gh.call_args.args[0]
        self.assertIn("--assignee", args)
        self.assertIn(sw.ASSIGNEE, args)
        self.assertIn("--label", args)
        self.assertIn(sw.LABEL, args)


if __name__ == "__main__":
    unittest.main(verbosity=2)
