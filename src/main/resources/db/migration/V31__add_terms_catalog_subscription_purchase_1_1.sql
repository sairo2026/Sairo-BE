-- 구독·결제 약관의 사실 정정에 맞춘 SUBSCRIPTION_PURCHASE 새 시행 버전 1.1이다.
-- content_hash는 프런트엔드 법적 문서 본문 상수(SUBSCRIPTION_TERMS_BODY)를 개행 LF, UTF-8로 인코딩한 SHA-256이다.
-- 변경 내용은 결제 재시도 기준 시각과 실패 사유 안내를 실제 처리에 맞추고, 결제 실패·제한 시작·미납 종료 안내 기준을 추가하며,
-- 환불 사유와 신청 방식을 실제 처리에 맞추는 것이다. 이용자 권리를 불리하게 바꾸지 않으므로 reconsent_required는 false로 둔다.
-- 구독 구매 동의는 동의한 버전이 현재 시행 버전과 같아야 유효하므로 효력 시각 이후 대표는 다음 결제 전에 구독 구매 동의를 다시 해야 한다.
-- 효력 시각은 진행 중인 구독의 갱신 결제가 끝난 뒤로 잡는다. 1.0 행은 동의 이력이 참조하므로 그대로 둔다.
INSERT INTO terms_catalog (terms_code, terms_version, title, category, required, document_url, content_hash, effective_at, display_context, reconsent_required, change_summary)
VALUES
    ('SUBSCRIPTION_PURCHASE', '1.1', '구독·결제 약관과 구독 구매 동의', 'SEPARATE_CONSENT', true, '/legal/subscription-terms', '812b4bece290192f98a585221f29eaaba665ae86f2bac66ed11f73535ebbf74b', TIMESTAMPTZ '2026-12-16 00:00:00+09', 'PURCHASE', false, '결제 재시도 기준 시각과 실패 사유 안내, 결제 실패·제한 시작·미납 종료 때의 대표 안내 기준, 환불 사유와 신청 방식을 실제 처리에 맞게 고쳤습니다.')
ON CONFLICT (terms_code, terms_version) DO NOTHING;