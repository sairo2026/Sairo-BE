INSERT INTO terms_catalog (terms_code, terms_version, title, category, required, document_url, content_hash, effective_at, display_context, reconsent_required)
VALUES
    ('TOS', '1.0', '사이로 이용약관', 'TERMS', true, '/legal/terms', 'b27e6077e58d65b494752f3be0ae2d92b302c27624307bd28f52230dc2a44cbc', TIMESTAMPTZ '2026-11-10 00:00:00+09', 'SIGNUP', false),
    ('PRIVACY', '1.0', '사이로 개인정보처리방침', 'NOTICE', false, '/legal/privacy', '790dadc73d1e0b46fc4ceaa2b2d5c6d2e7ec5c4d4f920b270ddb0dcca35ecc77', TIMESTAMPTZ '2026-11-10 00:00:00+09', 'SIGNUP', false),
    ('MARKETING', '1.0', '이메일 공지·혜택 안내 수신 및 개인정보 수집·이용 동의(선택)', 'MARKETING', false, '/legal/marketing', 'd0822e7880f278f5cf0b65f0f0ea63cd4530390c93813deedf780fa49d315dff', TIMESTAMPTZ '2026-11-10 00:00:00+09', 'SIGNUP', false),
    ('CARD_REGISTRATION', '1.0', '카드 등록 안내', 'SEPARATE_CONSENT', true, '/billing/card', 'f97894164c79aeb5ddb398f57096a7ddc5bd214e9194e4999dadf4e53d105540', TIMESTAMPTZ '2026-11-10 00:00:00+09', 'CARD_REGISTRATION', false),
    ('SUBSCRIPTION_PURCHASE', '1.0', '구독·결제 약관과 구독 구매 동의', 'SEPARATE_CONSENT', true, '/legal/subscription-terms', '31bae3beb921f30d18c5d62f53c54fd700c7e62964d56e5d46fdbd3de79198e5', TIMESTAMPTZ '2026-11-10 00:00:00+09', 'PURCHASE', false),
    ('GUEST_LINK_NOTICE_APPROVER', '1.0', '승인자 링크 개인정보 안내', 'NOTICE', false, '/legal/link-notice/approver', '16a89ceb373e5fcb74bdc260573733196918f138793756b384ceb2a9b84e90b6', TIMESTAMPTZ '2026-11-10 00:00:00+09', 'LINK_NOTICE', false),
    ('GUEST_LINK_NOTICE_CUSTOMER', '1.0', '고객 링크 개인정보 안내', 'NOTICE', false, '/legal/link-notice/customer', 'fb1a6aa110bb820e403d6544b105c4f6c8f5e59ccfaf800c2de2b68c0f595d50', TIMESTAMPTZ '2026-11-10 00:00:00+09', 'LINK_NOTICE', false)
ON CONFLICT (terms_code, terms_version) DO NOTHING;

INSERT INTO legal_retention_rule (rule_code, data_group_label, retention_days, anchor_event, basis_note, status)
VALUES
    ('TERMS_AGREEMENT_AFTER_WITHDRAWAL', '약관 동의와 철회 이벤트, 탈퇴 후', 1095, 'WITHDRAWN_AT', '내부 권장 보관 기간. 구독 구매 동의와 수신 설정 통지 이력이 참조하는 동안은 지우지 않는다. 개인정보 보호법과 전자상거래 법령의 검토 대상이다', 'ASSUMPTION'),
    ('LOGIN_HISTORY', '로그인 기록', 365, 'RECORDED_AT', '내부 권장 보관 기간. 개인정보 보호법과 통신비밀보호법의 검토 대상이다', 'ASSUMPTION'),
    ('OPS_AUDIT_LOG', '운영자 접근과 감사 기록', 730, 'RECORDED_AT', '내부 권장 보관 기간. 운영 승인 요청도 같은 기간 보관한다', 'ASSUMPTION'),
    ('GUEST_INPUT_AFTER_APPROVAL_END', '비회원 링크 입력 데이터와 승인자 개인정보', 90, 'APPROVAL_END', '내부 권장 보관 기간. 보관 기간이 끝나면 익명화한다', 'ASSUMPTION'),
    ('GUEST_LINK_ACCESS_LOG', '비회원 링크 접속 로그', 365, 'ACCESSED_AT', '내부 권장 보관 기간. 변환값만 보관한다', 'ASSUMPTION'),
    ('GUEST_INTAKE_CONTACT', '비회원 권리 요청 연락처', 30, 'COMPLETED_AT', '내부 권장 보관 기간. 처리 완료 뒤 연락처만 먼저 삭제한다', 'ASSUMPTION'),
    ('GUEST_INTAKE_RECORD', '비회원 권리 요청 접수 이력', 1095, 'COMPLETED_AT', '내부 권장 보관 기간. 요청 처리 사실의 증명에 쓴다', 'ASSUMPTION'),
    ('PRIVACY_REQUEST_RECORD', '개인정보 권리 요청 처리 이력', 1095, 'COMPLETED_AT', '내부 권장 보관 기간. 개인정보 보호법의 검토 대상이다', 'ASSUMPTION'),
    ('SUPPORT_INQUIRY', '지원 문의', 1095, 'CLOSED_AT', '내부 권장 보관 기간. 전자상거래 법령의 검토 대상이다', 'ASSUMPTION'),
    ('REVIEW_RECORD', '방문 평가, 선호 중개사 지정, 신뢰 요약 스냅샷', 1095, 'RECORDED_AT', '내부 권장 보관 기간. 이의 처리 중에는 보류한다', 'ASSUMPTION'),
    ('MARKETING_NOTICE_DELIVERY', '수신 설정 결과 통지와 재확인 발송 이력', 1095, 'RECORDED_AT', '내부 권장 보관 기간. 정보통신망법의 검토 대상이다', 'ASSUMPTION'),
    ('KAKAO_UNLINK_EVENT', '카카오 연결 끊기 호출과 수신 이력', 365, 'COMPLETED_AT', '내부 권장 보관 기간. 식별자는 변환값만 보관한다', 'ASSUMPTION'),
    ('KAKAO_UNLINK_KEY_MAX', '연결 끊기 호출 대기 중 식별자 보관 상한', 7, 'RECORDED_AT', '내부 권장 보관 기간. 호출이 끝나지 않아도 이 상한이 지나면 삭제한다', 'ASSUMPTION'),
    ('BILLING_RECORD', '구독과 결제 기록', 1825, 'RECORDED_AT', '내부 권장 보관 기간. 세법과 전자상거래 법령 확인 뒤 항목별로 나눈다', 'ASSUMPTION'),
    ('TAX_DOCUMENT', '세금계산서와 현금영수증 발급 기록', 1825, 'ISSUED_AT', '내부 권장 보관 기간. 세무사 검토 전 가정이다', 'ASSUMPTION'),
    ('AUTHORITY_REQUEST', '수사기관 등 자료 요청 기록', 1825, 'CLOSED_AT', '내부 권장 보관 기간. 출시 후 확장 대상이다', 'ASSUMPTION'),
    ('PROVIDER_CONTRACT_COPY', '공급자 계약서와 처리 조건 사본', 1825, 'CONTRACT_END', '내부 권장 보관 기간. 출시 후 확장 대상이다', 'ASSUMPTION'),
    ('OFFICE_CONTRACT_DOCUMENT', '사무소가 수탁 보관하는 거래계약서', 1825, 'RECORDED_AT', '내부 권장 보관 기간. 기술설계 보관 기간 표의 거래계약서 5년이며 공인중개사법의 검토 대상이다', 'ASSUMPTION'),
    ('OFFICE_EXPLANATION_DOCUMENT', '사무소가 수탁 보관하는 중개대상물 확인설명서', 1095, 'RECORDED_AT', '내부 권장 보관 기간. 기술설계 보관 기간 표의 확인설명서 3년이며 공인중개사법의 검토 대상이다', 'ASSUMPTION')
ON CONFLICT (rule_code) DO NOTHING;
