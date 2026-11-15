-- Stops uq_office_membership_active from blocking a user whose membership in the same office was revoked or left.
-- REVOKED rows stay as history and a new PENDING row can be created next to them. V24 and earlier are not modified. No rows are inserted, updated or deleted.

DROP INDEX uq_office_membership_active;
CREATE UNIQUE INDEX uq_office_membership_active
    ON office_membership (office_id, user_id)
    WHERE status IN ('PENDING','APPROVED','SUSPENDED')
       OR (status = 'REJECTED' AND resolved_at IS NULL);

COMMENT ON INDEX uq_office_membership_active IS '같은 사무소·사용자 조합에서 열린 소속(PENDING, APPROVED, SUSPENDED)과 해결되지 않은 REJECTED가 둘 이상 생기지 않게 막는다. REVOKED(회수·나가기)·EXPIRED·CANCELLED는 이력으로 남고 막지 않아 같은 사무소에서 대표의 초대와 사용자의 참여 요청을 다시 받을 수 있다. REJECTED는 resolved_at이 채워지면(재신청 시작) 더 이상 막지 않는다';
