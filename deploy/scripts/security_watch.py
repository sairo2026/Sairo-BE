#!/usr/bin/env python3
"""Detect Cloudflare IPv4 range drift and Origin cert expiry milestones.

Never modifies AWS/Cloudflare resources. On drift/milestone it opens a
GitHub Issue (deduplicated by an identifier embedded in the title) so a
human runs the manual update procedure documented in the infra ledger
(section 15.6 / "Cloudflare 항목").
"""
import argparse
import hashlib
import ipaddress
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
BASELINE_IPV4_PATH = REPO_ROOT / "deploy" / "cloudflare-ipv4.txt"
CERT_EXPIRY_PATH = REPO_ROOT / "deploy" / "cloudflare-origin-cert-expiry.txt"
CERT_THRESHOLDS_DAYS = [180, 90, 30, 0]
LABEL = "🏗️ infra"
ASSIGNEE = "KimTaeHwan21"


def normalize_ipv4_lines(lines):
    nets = []
    for raw in lines:
        line = raw.strip()
        if not line:
            continue
        net = ipaddress.ip_network(line, strict=True)
        if not isinstance(net, ipaddress.IPv4Network):
            raise ValueError(f"not an IPv4 network: {line}")
        nets.append(net)
    if not nets:
        raise ValueError("empty IPv4 list after validation")
    nets = sorted(set(nets), key=lambda n: (int(n.network_address), n.prefixlen))
    return [str(n) for n in nets]


def sha12(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:12]


def gh(args, dry_run):
    cmd = ["gh", *args]
    if dry_run:
        print(f"[dry-run] would run: {' '.join(cmd)}")
        return ""
    result = subprocess.run(cmd, capture_output=True, text=True, check=True)
    return result.stdout.strip()


def issue_exists(identifier, dry_run):
    if dry_run:
        print(f"[dry-run] would search issues (state=all) for '{identifier} in:title'")
        return False
    out = gh(
        [
            "issue", "list",
            "--state", "all",
            "--search", f"{identifier} in:title",
            "--json", "number",
            "--jq", ".[].number",
        ],
        dry_run=False,
    )
    return bool(out.strip())


def create_issue(title, body, dry_run):
    if issue_exists(title_identifier(title), dry_run):
        print(f"[skip] issue already exists for identifier in title: {title}")
        return
    if dry_run:
        print(f"[dry-run] would create issue:\n  title: {title}\n  body:\n{body}\n")
        return
    gh(
        [
            "issue", "create",
            "--title", title,
            "--body", body,
            "--label", LABEL,
            "--assignee", ASSIGNEE,
        ],
        dry_run=False,
    )
    print(f"[created] {title}")


def title_identifier(title):
    # identifiers are embedded as the last bracketed token, e.g. "... [cf-ipv4:abc123]"
    start = title.rfind("[")
    end = title.rfind("]")
    if start == -1 or end == -1 or end < start:
        raise ValueError(f"title has no identifier bracket: {title}")
    return title[start:end + 1]


def check_ip_drift(live_ips_path: Path, dry_run: bool, baseline_path: Path = None):
    baseline_path = baseline_path or BASELINE_IPV4_PATH
    live_lines = live_ips_path.read_text(encoding="utf-8").splitlines()
    live = normalize_ipv4_lines(live_lines)

    if not baseline_path.exists():
        # Fail loudly rather than silently skipping the check — a missing
        # baseline must not look like a healthy "no drift" run.
        raise FileNotFoundError(f"IPv4 baseline file missing: {baseline_path}")
    baseline = normalize_ipv4_lines(baseline_path.read_text(encoding="utf-8").splitlines())

    if live == baseline:
        print("[ok] Cloudflare IPv4 list matches baseline, no drift")
        return

    added = sorted(set(live) - set(baseline))
    removed = sorted(set(baseline) - set(live))
    live_hash = sha12("\n".join(live))
    identifier = f"[cf-ipv4:{live_hash}]"
    title = f"infra: Cloudflare IPv4 대역 변경 감지 {identifier}"
    body_lines = [
        "## 📌 작업 설명",
        "공식 Cloudflare IPv4 대역이 저장소 기준 파일(`deploy/cloudflare-ipv4.txt`)과 달라졌습니다.",
        "",
        f"- 추가된 대역: {', '.join(added) if added else '(없음)'}",
        f"- 제거된 대역: {', '.join(removed) if removed else '(없음)'}",
        "",
        "## ☑️ 작업 상세 내용",
        "- [ ] 추가된 대역만 `sairo-ec2-sg` 443 인바운드에 `authorize-security-group-ingress`로 추가 (보안그룹은 자동으로 바뀌지 않습니다)",
        "- [ ] `curl -i https://api.sairo.agency/actuator/health`로 정상 확인",
        "- [ ] 제거된 대역만 `revoke-security-group-ingress`로 삭제",
        "- [ ] 삭제 직후 다시 health 확인, 문제 있으면 즉시 재추가로 롤백",
        "- [ ] `deploy/cloudflare-ipv4.txt`를 위 목록으로 갱신해 커밋",
        "- [ ] 인프라 원장 갱신 후 이 이슈 close",
    ]
    create_issue(title, "\n".join(body_lines), dry_run)


def check_cert_expiry(dry_run: bool, cert_path: Path = None, now: datetime = None):
    cert_path = cert_path or CERT_EXPIRY_PATH
    if not cert_path.exists():
        # Fail loudly — see the same rationale in check_ip_drift().
        raise FileNotFoundError(f"cert expiry baseline missing: {cert_path}")
    raw = cert_path.read_text(encoding="utf-8").strip()
    expiry = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    now = now or datetime.now(timezone.utc)
    days_remaining = (expiry - now).days
    print(f"[info] Origin cert expiry={raw} days_remaining={days_remaining}")

    eligible = [t for t in CERT_THRESHOLDS_DAYS if days_remaining <= t]
    if not eligible:
        print("[ok] Origin cert expiry is beyond all alert thresholds")
        return

    # Only the nearest (smallest) crossed threshold — otherwise a cert that's
    # already at D-19 would open D-180, D-90, and D-30 issues all at once.
    threshold = min(eligible)
    identifier = f"[cert-expiry:{raw}:D-{threshold}]"
    title = f"infra: Origin 인증서 만료 D-{threshold} {identifier}"
    body = (
        "## 📌 작업 설명\n"
        f"Cloudflare Origin 인증서 만료일: {raw} (남은 일수: {days_remaining}, 단계: D-{threshold})\n\n"
        "## ☑️ 작업 상세 내용\n"
        "- [ ] EC2에서 새 개인키·CSR 생성 (SAN: api.sairo.agency만)\n"
        "- [ ] Cloudflare에서 새 Origin CA 인증서 발급\n"
        "- [ ] 새 파일을 EC2에 올리고 `mv`로 원자적 교체\n"
        "- [ ] `sudo nginx -t`\n"
        "- [ ] `sudo systemctl reload nginx` (restart 아님, 무중단)\n"
        "- [ ] EC2에서 실제 Origin 인증서 파일로 새 만료일 확인 (SSM): "
        "`sudo openssl x509 -in /etc/nginx/ssl/sairo-origin.pem -noout -dates -subject -ext subjectAltName` "
        "(외부 `openssl s_client`는 Cloudflare Edge 인증서만 보이므로 사용 금지)\n"
        "- [ ] 외부 `curl -i https://api.sairo.agency/actuator/health`로 Cloudflare→Origin 전체 경로 정상 확인\n"
        "- [ ] Cloudflare SSL/TLS 모드가 Full (strict)인지 재확인\n"
        "- [ ] 구 키 폐기\n"
        "- [ ] `deploy/cloudflare-origin-cert-expiry.txt`를 새 만료일로 갱신해 커밋\n"
        "- [ ] 인프라 원장 갱신 후 이 이슈 close\n"
    )
    create_issue(title, body, dry_run)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--live-ips", type=Path, help="path to the freshly curl'd Cloudflare ips-v4 response")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--check", choices=["ip", "cert", "all"], default="all")
    args = parser.parse_args()

    if args.check in ("ip", "all"):
        if not args.live_ips:
            print("--live-ips is required for the ip check", file=sys.stderr)
            sys.exit(2)
        check_ip_drift(args.live_ips, args.dry_run)

    if args.check in ("cert", "all"):
        check_cert_expiry(args.dry_run)


if __name__ == "__main__":
    main()
