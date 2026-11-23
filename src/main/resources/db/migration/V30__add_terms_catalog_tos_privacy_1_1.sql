-- 이용약관 제7조 제1항과 개인정보처리방침의 사실 정정에 맞춘 새 시행 버전 1.1이다.
-- content_hash는 프런트엔드 법적 문서 본문 상수(TERMS_BODY, PRIVACY_BODY)를 개행 LF, UTF-8로 인코딩한 SHA-256이다.
-- 변경 내용은 가입 때 휴대폰 번호 인증 필수 서술을 문자 인증 제공 여부에 따른 서술로 줄이고,
-- 암호화 저장 고지를 실제 암호화하는 항목으로 좁히는 것이다. 이용자 의무와 권리를 불리하게 바꾸지 않으므로 재동의는 요구하지 않는다.
-- 개인정보처리방침은 NOTICE 분류라서 reconsent_required를 true로 둘 수 없다.
-- 1.0 행은 가입 때 동의 이력이 참조하므로 그대로 둔다.
INSERT INTO terms_catalog (terms_code, terms_version, title, category, required, document_url, content_hash, effective_at, display_context, reconsent_required, change_summary)
VALUES
    ('TOS', '1.1', '사이로 이용약관', 'TERMS', true, '/legal/terms', '97ddc8d8ae69212f858eaea8c47ffa803c9d52754404117a31c6fb2ddbad3176', TIMESTAMPTZ '2026-12-16 00:00:00+09', 'SIGNUP', false, '회원가입 때 휴대폰 번호 인증을 필수로 적었던 제7조 제1항을 문자 인증을 제공하는 동안과 제공하지 않는 동안으로 나누어 실제 운영에 맞게 고쳤습니다.'),
    ('PRIVACY', '1.1', '사이로 개인정보처리방침', 'NOTICE', false, '/legal/privacy', '0a5e22ca4a5b4c2c75e8f187788589ea22605d93596d5edd8dbe6005c6379307', TIMESTAMPTZ '2026-12-16 00:00:00+09', 'SIGNUP', false, '휴대폰 인증 시각과 인증 목적을 문자 인증 제공 여부에 맞게 고치고, 안전성 확보 조치의 암호화 저장 항목을 실제 암호화하는 항목으로 바로잡았습니다.')
ON CONFLICT (terms_code, terms_version) DO NOTHING;