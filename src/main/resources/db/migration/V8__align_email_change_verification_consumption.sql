-- EMAIL_CHANGE 인증 challenge도 실제 이메일 변경 완료 시 한 번만 소비할 수 있도록 인증 소비 제약을 맞춘다.

ALTER TABLE email_verification_code DROP CONSTRAINT ck_email_verification_consumed;

ALTER TABLE email_verification_code ADD CONSTRAINT ck_email_verification_consumed
    CHECK (consumed_at IS NULL OR (status = 'VERIFIED' AND purpose IN ('SIGNUP','LOGIN','EMAIL_CHANGE')));

COMMENT ON COLUMN email_verification_code.consumed_at IS 'SIGNUP·LOGIN·EMAIL_CHANGE 인증 challenge를 실제 작업 완료 시 소비한 시각. STEP_UP은 별도 step-up 토큰으로 관리한다';

COMMENT ON TABLE office_registration IS '사업자등록번호로 시작하는 사무소 인증 신청과 증빙·심사 상태. 사무소 기본정보는 승인 시 검증·확정된 값으로 office를 생성한다';

COMMENT ON INDEX uq_office_membership_member_approved IS '사이로 소속 정책상 회원당 APPROVED 사무소 소속을 최대 1개로 제한';
COMMENT ON TABLE office_membership IS '사무소 소속(회원↔사무소 관계, 관리자/직원 권한). 사이로 소속 정책상 회원당 APPROVED 소속은 최대 1개';

COMMENT ON COLUMN coordination.status IS '조율 상태: PENDING_RESPONSE 응답 대기, RESPONDED 응답 완료, CONFIRMED 일정 확정, COMPLETED 임장 완료, CANCELLED 취소, NEEDS_REBOOKING 재조율 필요(terminal)';
COMMENT ON COLUMN coordination.origin_coordination_id IS '재조율 필요 후 새로 생성된 경우의 원본 조율. office_id와 함께 같은 사무소만 참조하며 자기참조는 금지';
COMMENT ON COLUMN coordination.rebooking_requested_at IS '모두 불가능 제출로 NEEDS_REBOOKING에 진입한 시각. PENDING_RESPONSE 또는 RESPONDED에서 일정 확정 전에 전이할 수 있다';

COMMENT ON COLUMN expiry_task.status IS '업무 상태: OPEN 진행 중, COMPLETED 향후 확장용 예약 상태, CANCELLED 취소. 현재 MVP는 직접 완료 API 없이 계약 처리 결과에 따라 OPEN을 CANCELLED로 정리한다';
COMMENT ON COLUMN expiry_task.completed_at IS 'status=COMPLETED일 때의 완료 처리 일시. 현재 MVP 사용자 흐름에서는 사용하지 않는다';
