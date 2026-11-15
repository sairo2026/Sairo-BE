CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX ix_property_office_updated_active ON property (office_id, updated_at DESC, id DESC) WHERE deleted_at IS NULL;
CREATE INDEX ix_property_office_updated_archived ON property (office_id, updated_at DESC, id DESC) WHERE deleted_at IS NOT NULL;
CREATE INDEX ix_property_office_address_key ON property (office_id, (lower(regexp_replace(coalesce(address, ''), '\s+', '', 'g')))) WHERE deleted_at IS NULL;
CREATE INDEX ix_property_name_trgm ON property USING gin ((lower(name)) gin_trgm_ops);
CREATE INDEX ix_property_address_trgm ON property USING gin ((lower(coalesce(address, ''))) gin_trgm_ops);

CREATE INDEX ix_customer_office_updated_active ON customer (office_id, updated_at DESC, id DESC) WHERE deleted_at IS NULL;
CREATE INDEX ix_customer_office_updated_archived ON customer (office_id, updated_at DESC, id DESC) WHERE deleted_at IS NOT NULL;
CREATE INDEX ix_customer_office_name_key ON customer (office_id, (lower(regexp_replace(name, '\s+', '', 'g'))));
CREATE INDEX ix_customer_name_trgm ON customer USING gin ((lower(name)) gin_trgm_ops);
CREATE INDEX ix_customer_phone_trgm ON customer USING gin ((lower(coalesce(phone, ''))) gin_trgm_ops);
CREATE INDEX ix_customer_phone_digits_trgm ON customer USING gin ((regexp_replace(coalesce(phone, ''), '\D', '', 'g')) gin_trgm_ops);
CREATE INDEX ix_customer_secondary_phone_digits_trgm ON customer USING gin ((regexp_replace(coalesce(secondary_phone, ''), '\D', '', 'g')) gin_trgm_ops);

CREATE INDEX ix_customer_merge_log_primary ON customer_merge_log (primary_customer_id);
CREATE INDEX ix_customer_demand_condition_customer ON customer_demand_condition (customer_id);
CREATE INDEX ix_property_owner_occupant_property ON property_owner_occupant (property_id, role, start_date);
CREATE INDEX ix_property_owner_occupant_customer ON property_owner_occupant (customer_id, role);
CREATE INDEX ix_property_owner_occupant_office_role ON property_owner_occupant (office_id, role, customer_id);

CREATE INDEX ix_entity_lifecycle_event_office_type_effective ON entity_lifecycle_event (office_id, entity_type, event_type, effective_at);
CREATE INDEX ix_contract_status_history_contract_track ON contract_status_history (contract_id, track, effective_at);
CREATE INDEX ix_visit_coordination_status_history_coordination ON visit_coordination_status_history (coordination_id, effective_at, id);

COMMENT ON INDEX ix_property_office_updated_active IS '매물 목록의 기본 정렬인 수정 시각 내림차순을 사무소 단위로 바로 읽는 인덱스다';
COMMENT ON INDEX ix_property_office_updated_archived IS '보관된 매물 목록을 사무소 단위로 수정 시각 내림차순으로 읽는 인덱스다';
COMMENT ON INDEX ix_property_office_address_key IS '공백을 제거한 소문자 주소로 같은 주소 매물을 찾는 인덱스다';
COMMENT ON INDEX ix_property_name_trgm IS '매물명 부분 문자열 검색을 위한 3글자 조각 인덱스다';
COMMENT ON INDEX ix_property_address_trgm IS '매물 주소 부분 문자열 검색을 위한 3글자 조각 인덱스다';
COMMENT ON INDEX ix_customer_office_updated_active IS '고객 목록의 기본 정렬인 수정 시각 내림차순을 사무소 단위로 바로 읽는 인덱스다';
COMMENT ON INDEX ix_customer_office_updated_archived IS '보관된 고객 목록을 사무소 단위로 수정 시각 내림차순으로 읽는 인덱스다';
COMMENT ON INDEX ix_customer_office_name_key IS '공백을 제거한 소문자 이름으로 같은 이름 고객을 찾는 인덱스다';
COMMENT ON INDEX ix_customer_name_trgm IS '고객 이름 부분 문자열 검색을 위한 3글자 조각 인덱스다';
COMMENT ON INDEX ix_customer_phone_trgm IS '고객 연락처 부분 문자열 검색을 위한 3글자 조각 인덱스다';
COMMENT ON INDEX ix_customer_phone_digits_trgm IS '숫자만 남긴 고객 연락처의 부분 검색을 위한 3글자 조각 인덱스다';
COMMENT ON INDEX ix_customer_secondary_phone_digits_trgm IS '숫자만 남긴 고객 보조 연락처의 부분 검색을 위한 3글자 조각 인덱스다';
COMMENT ON INDEX ix_customer_merge_log_primary IS '대표 고객에 병합된 고객 묶음을 찾는 인덱스다';
COMMENT ON INDEX ix_customer_demand_condition_customer IS '고객별 희망 조건 존재 여부를 찾는 인덱스다';
COMMENT ON INDEX ix_property_owner_occupant_property IS '매물별 현재 소유자와 점유자를 찾는 인덱스다';
COMMENT ON INDEX ix_property_owner_occupant_customer IS '고객별 소유자와 점유자 역할을 찾는 인덱스다';
COMMENT ON INDEX ix_property_owner_occupant_office_role IS '사무소 안에서 역할별 고객 묶음을 찾는 인덱스다';
COMMENT ON INDEX ix_entity_lifecycle_event_office_type_effective IS '월말결산이 사무소의 생성·보관 사건을 기간으로 읽는 인덱스다';
COMMENT ON INDEX ix_contract_status_history_contract_track IS '계약별 상태 이력을 트랙과 효력 시각 순서로 읽는 인덱스다';
COMMENT ON INDEX ix_visit_coordination_status_history_coordination IS '조율 건별 상태 이력을 효력 시각 순서로 읽는 인덱스다';
