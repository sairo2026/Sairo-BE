INSERT INTO operator_account (kakao_provider_key, name, status, mfa_enabled, totp_secret_encrypted)
VALUES
    ('system:seed-maker', '시스템 작성', 'DISABLED', true, 'SYSTEM_ACCOUNT'),
    ('system:seed-checker', '시스템 승인', 'DISABLED', true, 'SYSTEM_ACCOUNT')
ON CONFLICT (kakao_provider_key) DO NOTHING;

INSERT INTO price_schedule (version, solo_amount, team_first_extra, team_after_four_extra, effective_at, notice_at, status, approved_by_operator_id, created_by_operator_id, audience)
SELECT v.version, 39900, 10000, 8000, TIMESTAMPTZ '2026-11-01 00:00:00+09', NULL, 'ACTIVE', checker.id, maker.id, v.audience
FROM (VALUES ('2026-STANDARD', 'STANDARD'), ('2026-START-CUSTOMER', 'START_CUSTOMER')) AS v(version, audience)
CROSS JOIN (SELECT id FROM operator_account WHERE kakao_provider_key = 'system:seed-maker') AS maker
CROSS JOIN (SELECT id FROM operator_account WHERE kakao_provider_key = 'system:seed-checker') AS checker
ON CONFLICT (version) DO NOTHING;

INSERT INTO trial_event (name, starts_at, ends_at, office_created_during, first_application_during, status, approved_by_operator_id, created_by_operator_id, free_end_mode, fixed_free_ends_at, audience, max_offices)
SELECT e.name, e.starts_at, e.ends_at, false, false, 'SCHEDULED', checker.id, maker.id, e.free_end_mode, e.fixed_free_ends_at, e.audience, e.max_offices
FROM (VALUES
    ('시작 고객', TIMESTAMPTZ '2026-11-30 00:00:00+09', TIMESTAMPTZ '2027-02-01 00:00:00+09', 'FIXED_END', TIMESTAMPTZ '2027-07-01 00:00:00+09', 'START_CUSTOMER', 15),
    ('출시 초기 일반 가입', TIMESTAMPTZ '2026-11-30 00:00:00+09', TIMESTAMPTZ '2027-02-01 00:00:00+09', 'DURATION', NULL::timestamptz, 'GENERAL', NULL::integer),
    ('2월 가입자', TIMESTAMPTZ '2027-02-01 00:00:00+09', TIMESTAMPTZ '2027-03-02 00:00:00+09', 'FIXED_END', TIMESTAMPTZ '2027-04-01 00:00:00+09', 'GENERAL', NULL::integer),
    ('3월 가입자', TIMESTAMPTZ '2027-03-02 00:00:00+09', TIMESTAMPTZ '2027-04-01 00:00:00+09', 'FIXED_END', TIMESTAMPTZ '2027-05-01 00:00:00+09', 'GENERAL', NULL::integer),
    ('4월 이후 상시 가입자', TIMESTAMPTZ '2027-04-01 00:00:00+09', TIMESTAMPTZ '2028-04-01 00:00:00+09', 'DURATION', NULL::timestamptz, 'GENERAL', NULL::integer)
) AS e(name, starts_at, ends_at, free_end_mode, fixed_free_ends_at, audience, max_offices)
CROSS JOIN (SELECT id FROM operator_account WHERE kakao_provider_key = 'system:seed-maker') AS maker
CROSS JOIN (SELECT id FROM operator_account WHERE kakao_provider_key = 'system:seed-checker') AS checker
WHERE NOT EXISTS (SELECT 1 FROM trial_event t WHERE t.name = e.name);
