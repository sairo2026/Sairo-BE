-- 구독·결제 약관의 사실 정정에 맞춘 SUBSCRIPTION_PURCHASE 새 시행 버전 1.1이다.
-- content_hash는 프런트엔드 법적 문서 본문 상수(SUBSCRIPTION_TERMS_BODY)를 개행 LF, UTF-8로 인코딩한 SHA-256이다.
-- 변경 내용은 결제 재시도 기준 시각, 실패 사유 표시 위치, 결제 실패·제한 시작·미납 종료 알림 기준, 환불 사유와 신청 방식, 서비스 장애 환불 금액, 회원 탈퇴 조건을 실제 처리에 맞추는 것이다.
-- reconsent_required는 false이지만 구독 구매 동의는 동의한 버전이 현재 시행 버전과 같아야 유효하다.
-- 따라서 효력 시각 이후에는 기존 구독 구매 동의가 모두 무효가 되어 대표는 다음 결제 전에 구독 구매 동의를 다시 해야 한다.
-- 효력 시각은 진행 중인 구독의 갱신 결제가 끝난 뒤로 잡고 갱신 7일 전 동의 요청 안내가 나가는 시점을 함께 확인해야 한다.
-- 1.0 행은 동의 이력이 참조하므로 그대로 둔다.
-- 이 파일은 V30 다음에 적용되어야 하므로 V30 약관 카탈로그 행 추가가 먼저 병합되어야 한다.
INSERT INTO terms_catalog (terms_code, terms_version, title, category, required, document_url, content_hash, effective_at, display_context, reconsent_required, change_summary)
VALUES
    ('SUBSCRIPTION_PURCHASE', '1.1', '구독·결제 약관과 구독 구매 동의', 'SEPARATE_CONSENT', true, '/legal/subscription-terms', 'c5eedb6fa69de26f5edc51b9fcb8e2ec73913bf4ed9a308ae4fad1696c853b1c', TIMESTAMPTZ '2026-12-16 00:00:00+09', 'PURCHASE', false, '결제 재시도 기준 시각, 결제 실패 사유 표시 위치, 결제 실패·제한 시작·미납 종료 때의 대표 안내 기준, 환불 사유와 신청 방식, 서비스 장애 환불 금액, 회원 탈퇴 조건을 실제 처리에 맞게 고쳤습니다.')
ON CONFLICT (terms_code, terms_version) DO NOTHING;