-- 아직 시행 전인 약관 1.1 행의 본문 해시와 변경 요약을 정정된 본문에 맞춘다.
-- 이용약관 1.1은 제18조 제3항(회원 탈퇴 조건)과 제22조 제1항(약관 변경 안내 방식)을,
-- 개인정보처리방침 1.1은 알림 보관 기간 서술을, 구독·결제 약관 1.1은 제11조(무료 이용 뒤 첫 결제 실패)와
-- 제13조 제2항(종료 뒤 승인된 결제의 환불 접수와 금액 확인)을 고쳤다. 버전 문자열은 1.1 그대로 둔다.
-- content_hash는 프런트엔드 법적 문서 본문 상수(TERMS_BODY, PRIVACY_BODY, SUBSCRIPTION_TERMS_BODY)를
-- 개행 LF, UTF-8로 인코딩한 SHA-256이다.
-- V30과 V31은 이미 적용된 파일이라 고치지 않고 이 파일에서 바꾼다.
-- 각 문장은 효력 시각이 아직 오지 않았고 해시가 V30, V31이 넣은 값 그대로인 행만 바꾼다.
-- 효력 시각이 지난 행이나 이미 다른 값으로 바뀐 행은 어떤 경우에도 고치지 않는다.
UPDATE terms_catalog
SET content_hash = '2a9fae7a75eef01efdce8b33cc7a195fa74c92bc762c683472152ff480c5ef94',
    change_summary = '회원가입 때 휴대폰 번호 인증을 필수로 적었던 제7조 제1항을 문자 인증을 제공하는 동안과 제공하지 않는 동안으로 나누어 실제 운영에 맞게 고치고, 회원 탈퇴 조건(제18조 제3항)을 대표 권한을 가진 회원 모두에게 적용하는 것으로, 약관 변경 안내 방식(제22조 제1항)을 서비스 안 알림과 이메일로 바로잡았습니다.'
WHERE terms_code = 'TOS' AND terms_version = '1.1'
  AND content_hash = '97ddc8d8ae69212f858eaea8c47ffa803c9d52754404117a31c6fb2ddbad3176'
  AND effective_at > now();

UPDATE terms_catalog
SET content_hash = '371004f23c65f23e841feb1e20ca89251ad32b226e2bae7decf12aa2e6461cd4',
    change_summary = '휴대폰 인증 시각과 인증 목적을 문자 인증 제공 여부에 맞게 고치고, 안전성 확보 조치의 암호화 저장 항목을 실제 암호화하는 항목으로 바로잡았으며, 알림의 보관 기간을 실제 삭제 시점에 맞게 고쳤습니다.'
WHERE terms_code = 'PRIVACY' AND terms_version = '1.1'
  AND content_hash = '0a5e22ca4a5b4c2c75e8f187788589ea22605d93596d5edd8dbe6005c6379307'
  AND effective_at > now();

UPDATE terms_catalog
SET content_hash = '011d39a2d4c8a1cf4ca253d0299a0a07ca00f9e72d8820404f3baedd118b7d86',
    change_summary = '결제 재시도 기준 시각, 결제 실패 사유 표시 위치, 결제 실패·제한 시작·미납 종료 때의 대표 안내 기준, 무료 이용 뒤 첫 결제 실패 때의 처리, 환불 사유와 신청 방식, 서비스 장애 환불 금액, 사무소 종료 뒤 승인된 결제의 환불 접수와 금액 확인 방식, 회원 탈퇴 조건을 실제 처리에 맞게 고쳤습니다.'
WHERE terms_code = 'SUBSCRIPTION_PURCHASE' AND terms_version = '1.1'
  AND content_hash = 'eca742d6baf0299cc04ca3d8e95bf10f8974e25689527479c4423a219f581059'
  AND effective_at > now();
