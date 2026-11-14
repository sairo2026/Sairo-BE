CREATE TABLE perf_seed_office (
    office_id BIGINT PRIMARY KEY,
    kind VARCHAR(10) NOT NULL,
    n_props INT NOT NULL,
    n_cust INT NOT NULL,
    first_member_id BIGINT,
    first_customer_id BIGINT,
    first_property_id BIGINT
);

INSERT INTO office (name, entity_type, registration_authority, real_estate_license_number,
    business_registration_number, representative_name, representative_phone, address)
SELECT '성능시험 사무소 ' || g, 'INDIVIDUAL', '가상구청', 'PERF-' || lpad(g::text, 6, '0'),
    lpad((9000000000 + g)::text, 10, '0'), '가상대표', '000-0000-0000', '가상시 가상구 가상로 ' || g
FROM generate_series(1, 1 + @@MID_OFFICES@@) g
ORDER BY g;

INSERT INTO perf_seed_office (office_id, kind, n_props, n_cust)
SELECT o.id, CASE WHEN o.rn = 1 THEN 'LARGE' ELSE 'MID' END,
    CASE WHEN o.rn = 1 THEN @@LARGE_PROPERTIES@@ ELSE @@MID_PROPERTIES@@ END,
    CASE WHEN o.rn = 1 THEN @@LARGE_CUSTOMERS@@ ELSE @@MID_CUSTOMERS@@ END
FROM (SELECT id, row_number() OVER (ORDER BY id) AS rn FROM office
      WHERE real_estate_license_number LIKE 'PERF-%') o;

INSERT INTO app_user (name, kakao_provider_key)
SELECT '시험회원 ' || s.office_id || '-' || m, 'perf-kakao-' || s.office_id || '-' || m
FROM perf_seed_office s CROSS JOIN generate_series(1, 6) m
ORDER BY s.office_id, m;

INSERT INTO office_membership (office_id, user_id, role, status, approved_at)
SELECT s.office_id, u.id,
    CASE WHEN m = 1 THEN 'OWNER' WHEN m <= 4 THEN 'AGENT' ELSE 'DEPUTY' END, 'APPROVED', now()
FROM perf_seed_office s CROSS JOIN generate_series(1, 6) m
JOIN app_user u ON u.kakao_provider_key = 'perf-kakao-' || s.office_id || '-' || m
ORDER BY s.office_id, m;

UPDATE perf_seed_office s SET first_member_id =
    (SELECT min(m.id) FROM office_membership m WHERE m.office_id = s.office_id);

INSERT INTO customer (office_id, is_corporation, name, managing_membership_id, phone,
    data_source, data_purpose, retention_until, deleted_at, created_at, updated_at)
SELECT s.office_id, x.g % 25 = 0, '가상고객 ' || (x.base % 20000), s.first_member_id + (x.g % 4),
    '000-' || lpad((x.base / 10000)::text, 4, '0') || '-' || lpad((x.base % 10000)::text, 4, '0'),
    'PERF', 'PERF', current_date + 1825,
    CASE WHEN x.g % 50 = 7 THEN now() - interval '5 days' END,
    now() - ((x.g * 7919) % 730) * interval '1 day' - ((x.g * 104729) % 86400) * interval '1 second',
    LEAST(now(), now() - ((x.g * 7919) % 730) * interval '1 day'
        - ((x.g * 104729) % 86400) * interval '1 second' + ((x.g * 31) % 100) * interval '1 day')
FROM perf_seed_office s
CROSS JOIN LATERAL (SELECT g, CASE WHEN g % 200 = 0 THEN g - 1 ELSE g END AS base
                    FROM generate_series(1, s.n_cust) g) x
ORDER BY s.office_id, x.g;

UPDATE perf_seed_office s SET first_customer_id =
    (SELECT min(c.id) FROM customer c WHERE c.office_id = s.office_id);

INSERT INTO customer_merge_log (primary_customer_id, merged_customer_id, executed_by_membership_id)
SELECT s.first_customer_id + g - 2, s.first_customer_id + g - 1, s.first_member_id
FROM perf_seed_office s CROSS JOIN LATERAL generate_series(1, s.n_cust) g
WHERE g % 200 = 100;

INSERT INTO customer_merge_log (primary_customer_id, merged_customer_id, executed_by_membership_id)
SELECT s.first_customer_id + g - 3, s.first_customer_id + g - 2, s.first_member_id
FROM perf_seed_office s CROSS JOIN LATERAL generate_series(1, s.n_cust) g
WHERE g % 2000 = 1000;

INSERT INTO property (office_id, category_id, name, address, address_detail, exclusive_area_sqm,
    supply_area_sqm, occupancy_status, managing_membership_id, registration_stage,
    created_at, updated_at, deleted_at)
SELECT s.office_id,
    (SELECT (array_agg(c.id ORDER BY c.id))[1 + (x.g % count(*))::int]
       FROM property_category c WHERE c.office_id IS NULL),
    '가상매물 ' || s.office_id || '-' || x.g,
    '가상시 가상구 가상로 ' || ((x.g + 1) / 2) || '번지',
    ((x.g % 2) + 1) || '0' || ((x.g % 9) + 1) || '호',
    20 + ((x.g * 37) % 130), (20 + ((x.g * 37) % 130)) * 1.2,
    CASE x.g % 4 WHEN 0 THEN 'VACANT' WHEN 1 THEN 'OWNER_OCCUPIED'
        WHEN 2 THEN 'TENANT_OCCUPIED' ELSE 'UNKNOWN' END,
    s.first_member_id + (x.g % 4),
    CASE WHEN x.g % 20 = 19 THEN 'DRAFT' ELSE 'ACTIVE' END,
    now() - ((x.g * 7919) % 730) * interval '1 day' - ((x.g * 104729) % 86400) * interval '1 second',
    LEAST(now(), now() - ((x.g * 7919) % 730) * interval '1 day'
        - ((x.g * 104729) % 86400) * interval '1 second' + ((x.g * 31) % 100) * interval '1 day'),
    CASE WHEN x.g % 33 = 0 THEN now() - interval '10 days' END
FROM perf_seed_office s CROSS JOIN LATERAL generate_series(1, s.n_props) AS x(g)
ORDER BY s.office_id, x.g;

UPDATE perf_seed_office s SET first_property_id =
    (SELECT min(p.id) FROM property p WHERE p.office_id = s.office_id);

INSERT INTO property_owner_occupant (office_id, property_id, customer_id, role, start_date,
    confirmed_at)
SELECT s.office_id, s.first_property_id + g - 1,
    s.first_customer_id + ((g * 13) % s.n_cust), 'OWNER', current_date - 365 - (g % 365),
    CASE WHEN g % 3 = 0 THEN now() END
FROM perf_seed_office s CROSS JOIN LATERAL generate_series(1, s.n_props) g;

INSERT INTO property_owner_occupant (office_id, property_id, customer_id, role, start_date)
SELECT s.office_id, s.first_property_id + g - 1,
    s.first_customer_id + ((g * 17 + 1) % s.n_cust), 'OCCUPANT', current_date - 100
FROM perf_seed_office s CROSS JOIN LATERAL generate_series(1, s.n_props) g
WHERE g % 5 < 2;

INSERT INTO property_offer (property_id, deal_type, status, sale_price, deposit_amount,
    monthly_rent_amount)
SELECT s.first_property_id + g - 1,
    CASE g % 3 WHEN 0 THEN 'SALE' WHEN 1 THEN 'JEONSE' ELSE 'MONTHLY' END,
    CASE WHEN g % 10 = 0 THEN 'NEGOTIATING' WHEN g % 9 = 0 THEN 'COMPLETED' ELSE 'AVAILABLE' END,
    CASE WHEN g % 3 = 0 THEN 100000000::bigint + (g % 500) * 10000000::bigint END,
    CASE WHEN g % 3 <> 0 THEN 10000000::bigint + (g % 300) * 10000000::bigint END,
    CASE WHEN g % 3 = 2 THEN 300000 + (g % 100) * 10000 END
FROM perf_seed_office s CROSS JOIN LATERAL generate_series(1, s.n_props) g;

INSERT INTO property_offer (property_id, deal_type, status, sale_price, deposit_amount,
    monthly_rent_amount)
SELECT s.first_property_id + g - 1,
    CASE g % 3 WHEN 0 THEN 'JEONSE' WHEN 1 THEN 'MONTHLY' ELSE 'SALE' END, 'AVAILABLE',
    CASE WHEN g % 3 = 2 THEN 100000000::bigint + (g % 500) * 10000000::bigint END,
    CASE WHEN g % 3 <> 2 THEN 10000000::bigint + (g % 300) * 10000000::bigint END,
    CASE WHEN g % 3 = 1 THEN 300000 + (g % 100) * 10000 END
FROM perf_seed_office s CROSS JOIN LATERAL generate_series(1, s.n_props) g
WHERE g % 4 = 0;

INSERT INTO entity_lifecycle_event (office_id, entity_type, property_id, event_type,
    creation_source, effective_at, recorded_at)
SELECT p.office_id, 'PROPERTY', p.id, 'CREATED',
    CASE WHEN p.id % 10 = 0 THEN 'IMPORT' ELSE 'MANUAL' END, p.created_at, p.created_at
FROM property p JOIN perf_seed_office s ON s.office_id = p.office_id;

INSERT INTO entity_lifecycle_event (office_id, entity_type, property_id, event_type,
    effective_at, recorded_at)
SELECT p.office_id, 'PROPERTY', p.id, 'ARCHIVED', p.deleted_at, p.deleted_at
FROM property p JOIN perf_seed_office s ON s.office_id = p.office_id
WHERE p.deleted_at IS NOT NULL;

INSERT INTO entity_lifecycle_event (office_id, entity_type, customer_id, event_type,
    creation_source, effective_at, recorded_at)
SELECT c.office_id, 'CUSTOMER', c.id, 'CREATED',
    CASE WHEN c.id % 10 = 0 THEN 'IMPORT' ELSE 'MANUAL' END, c.created_at, c.created_at
FROM customer c JOIN perf_seed_office s ON s.office_id = c.office_id;

INSERT INTO entity_lifecycle_event (office_id, entity_type, customer_id, event_type,
    effective_at, recorded_at)
SELECT c.office_id, 'CUSTOMER', c.id, 'ARCHIVED', c.deleted_at, c.deleted_at
FROM customer c JOIN perf_seed_office s ON s.office_id = c.office_id
WHERE c.deleted_at IS NOT NULL;

INSERT INTO customer_demand_condition (customer_id, deal_type, price_min, price_max, deposit_min,
    deposit_max, rent_min, rent_max)
SELECT s.first_customer_id + g - 1,
    CASE (g / 3) % 3 WHEN 0 THEN 'SALE' WHEN 1 THEN 'JEONSE' ELSE 'MONTHLY' END,
    100000000, 900000000, 10000000, 300000000, 300000, 900000
FROM perf_seed_office s CROSS JOIN LATERAL generate_series(1, s.n_cust) g
WHERE g % 3 = 0;

INSERT INTO customer_consultation (customer_id, occurred_at, author_membership_id, content,
    consultation_type)
SELECT s.first_customer_id + g - 1, now() - ((g * 7919) % 700) * interval '1 day',
    s.first_member_id + (g % 4), '가상 최초 문의 내용', 'FIRST_INQUIRY'
FROM perf_seed_office s CROSS JOIN LATERAL generate_series(1, s.n_cust) g
WHERE g % 5 < 3;

INSERT INTO customer_consultation (customer_id, occurred_at, author_membership_id, content,
    consultation_type)
SELECT s.first_customer_id + g - 1, now() - ((g * 104729) % 600) * interval '1 day',
    s.first_member_id + (g % 4), '가상 후속 상담 내용', 'FOLLOWUP'
FROM perf_seed_office s CROSS JOIN LATERAL generate_series(1, s.n_cust) g
WHERE g % 10 < 3;

INSERT INTO customer_property_interest (office_id, customer_id, property_id, start_date)
SELECT s.office_id, s.first_customer_id + g - 1,
    s.first_property_id + ((g * 11) % s.n_props), current_date - (g % 300)
FROM perf_seed_office s CROSS JOIN LATERAL generate_series(1, s.n_cust) g
WHERE g % 5 = 0;

CREATE TABLE perf_seed_coordination AS
SELECT s.office_id, g, s.first_member_id, s.first_customer_id, s.first_property_id,
    s.n_cust, s.n_props
FROM perf_seed_office s CROSS JOIN LATERAL generate_series(1, s.n_props / 4) g;

INSERT INTO visit_coordination (office_id, customer_id, manager_membership_id, status,
    period_start_date, period_end_date, created_at, updated_at, cancelled_at,
    reschedule_trigger_type, reschedule_raised_at)
SELECT c.office_id, c.first_customer_id + ((c.g * 7) % c.n_cust), c.first_member_id + (c.g % 4),
    CASE WHEN c.g % 100 < 5 THEN 'TIME_COLLECTING' WHEN c.g % 100 < 10 THEN 'COORDINATABLE'
        WHEN c.g % 100 < 13 THEN 'FINAL_CONFIRMATION_REQUIRED' WHEN c.g % 100 < 28 THEN 'CONFIRMED'
        WHEN c.g % 100 < 30 THEN 'RESCHEDULE_REQUIRED' WHEN c.g % 100 < 85 THEN 'VISIT_COMPLETED'
        WHEN c.g % 100 < 93 THEN 'VISIT_INCOMPLETE' ELSE 'CANCELLED' END,
    CASE WHEN c.g % 100 BETWEEN 13 AND 29 THEN current_date + (c.g % 30)
        ELSE current_date - 8 - ((c.g * 31) % 700) END,
    CASE WHEN c.g % 100 BETWEEN 13 AND 29 THEN current_date + (c.g % 30) + 7
        ELSE current_date - 1 - ((c.g * 31) % 700) END,
    now() - interval '40 days' - ((c.g * 31) % 700) * interval '1 day',
    now() - interval '40 days' - ((c.g * 31) % 700) * interval '1 day',
    CASE WHEN c.g % 100 >= 93 THEN now() - interval '40 days' END,
    CASE WHEN c.g % 100 BETWEEN 28 AND 29 THEN 'SCHEDULE_CHANGED' END,
    CASE WHEN c.g % 100 BETWEEN 28 AND 29 THEN now() - interval '1 day' END
FROM perf_seed_coordination c
ORDER BY c.office_id, c.g;

INSERT INTO visit_candidate_property (coordination_id, office_id, property_id, sort_order,
    property_office_id)
SELECT vc.id, vc.office_id, s.first_property_id + ((vc.id * 3 + k) % s.n_props), k, vc.office_id
FROM visit_coordination vc JOIN perf_seed_office s ON s.office_id = vc.office_id
CROSS JOIN generate_series(0, 1) k;

INSERT INTO visit_schedule_item (coordination_id, office_id, candidate_property_id,
    scheduled_start_at, scheduled_end_at, sort_order, result, actual_visited_at, created_at)
SELECT vc.id, vc.office_id, cp.id,
    CASE WHEN vc.status IN ('CONFIRMED', 'RESCHEDULE_REQUIRED')
        THEN date_trunc('day', now()) + (vc.id % 30) * interval '1 day'
            + (9 + (vc.id % 8)) * interval '1 hour' + cp.sort_order * interval '45 minutes'
        ELSE vc.period_start_date + interval '10 hours' + cp.sort_order * interval '45 minutes' END,
    CASE WHEN vc.status IN ('CONFIRMED', 'RESCHEDULE_REQUIRED')
        THEN date_trunc('day', now()) + (vc.id % 30) * interval '1 day'
            + (9 + (vc.id % 8)) * interval '1 hour' + cp.sort_order * interval '45 minutes'
            + interval '30 minutes'
        ELSE vc.period_start_date + interval '10 hours' + cp.sort_order * interval '45 minutes'
            + interval '30 minutes' END,
    cp.sort_order,
    CASE vc.status WHEN 'VISIT_COMPLETED' THEN 'COMPLETED' WHEN 'VISIT_INCOMPLETE' THEN 'NO_SHOW'
        ELSE 'SCHEDULED' END,
    CASE WHEN vc.status = 'VISIT_COMPLETED'
        THEN vc.period_start_date + interval '10 hours' + cp.sort_order * interval '45 minutes' END,
    vc.created_at
FROM visit_coordination vc
JOIN visit_candidate_property cp ON cp.coordination_id = vc.id
JOIN perf_seed_office s ON s.office_id = vc.office_id
WHERE vc.status IN ('CONFIRMED', 'RESCHEDULE_REQUIRED', 'VISIT_COMPLETED', 'VISIT_INCOMPLETE');

INSERT INTO visit_coordination_status_history (coordination_id, to_status, source, effective_at,
    created_at)
SELECT vc.id, 'TIME_COLLECTING', 'STAFF', vc.created_at, vc.created_at
FROM visit_coordination vc JOIN perf_seed_office s ON s.office_id = vc.office_id;

INSERT INTO visit_coordination_status_history (coordination_id, from_status, to_status, source,
    effective_at, created_at)
SELECT vc.id, 'TIME_COLLECTING', vc.status, 'STAFF',
    CASE WHEN vc.status IN ('VISIT_COMPLETED', 'VISIT_INCOMPLETE')
        THEN vc.period_start_date + interval '12 hours' ELSE vc.created_at + interval '1 hour' END,
    vc.created_at + interval '1 hour'
FROM visit_coordination vc JOIN perf_seed_office s ON s.office_id = vc.office_id
WHERE vc.status <> 'TIME_COLLECTING';

INSERT INTO visit_schedule_item_result_history (schedule_item_id, previous_result, result,
    actual_visited_at, effective_at, recorded_by_membership_id)
SELECT si.id, 'SCHEDULED', 'COMPLETED', si.actual_visited_at, si.actual_visited_at,
    vc.manager_membership_id
FROM visit_schedule_item si JOIN visit_coordination vc ON vc.id = si.coordination_id
JOIN perf_seed_office s ON s.office_id = vc.office_id
WHERE si.result = 'COMPLETED';

CREATE TABLE perf_seed_contract AS
SELECT s.office_id, g, s.first_member_id, s.first_property_id, s.n_props
FROM perf_seed_office s CROSS JOIN LATERAL generate_series(1, s.n_props / 5) g;

INSERT INTO contract (office_id, property_id, deal_type, managing_membership_id,
    seller_landlord_name, buyer_tenant_name, signed_date, sale_price, deposit_amount,
    monthly_rent_amount, balance_due_date, lease_start_date, lease_end_date, contract_status,
    lease_period_status, created_at, updated_at)
SELECT c.office_id, c.first_property_id + ((c.g * 5) % c.n_props),
    CASE c.g % 3 WHEN 0 THEN 'SALE' WHEN 1 THEN 'JEONSE' ELSE 'MONTHLY' END,
    c.first_member_id + (c.g % 4),
    CASE WHEN c.g % 20 = 0 THEN NULL ELSE '가상매도인' END,
    CASE WHEN c.g % 20 = 0 THEN NULL ELSE '가상매수인' END,
    CASE WHEN c.g % 20 = 0 THEN NULL ELSE current_date - ((c.g * 37) % 700) END,
    CASE WHEN c.g % 20 <> 0 AND c.g % 3 = 0 THEN 300000000 END,
    CASE WHEN c.g % 20 <> 0 AND c.g % 3 <> 0 THEN 50000000 END,
    CASE WHEN c.g % 20 <> 0 AND c.g % 3 = 2 THEN 500000 END,
    CASE WHEN c.g % 20 = 0 THEN NULL ELSE current_date - ((c.g * 37) % 700) + 30 END,
    CASE WHEN c.g % 20 <> 0 AND c.g % 3 <> 0 THEN current_date - ((c.g * 37) % 700) + 30 END,
    CASE WHEN c.g % 20 <> 0 AND c.g % 3 <> 0
        THEN current_date - ((c.g * 37) % 700) + 760 - (c.g % 400) END,
    CASE WHEN c.g % 20 = 0 THEN 'DRAFT' WHEN c.g % 20 = 1 THEN 'CANCELLED'
        WHEN c.g % 20 < 4 THEN 'ENDED' ELSE 'SIGNED' END,
    CASE WHEN c.g % 20 > 3 AND c.g % 3 <> 0 THEN 'IN_PROGRESS' END,
    now() - ((c.g * 37) % 700) * interval '1 day', now() - ((c.g * 37) % 700) * interval '1 day'
FROM perf_seed_contract c
ORDER BY c.office_id, c.g;

INSERT INTO contract_status_history (contract_id, track, to_status, effective_at)
SELECT c.id, 'CONTRACT', 'DRAFT', c.created_at - interval '3 days'
FROM contract c JOIN perf_seed_office s ON s.office_id = c.office_id;

INSERT INTO contract_status_history (contract_id, track, from_status, to_status, effective_at)
SELECT c.id, 'CONTRACT', 'DRAFT', 'SIGNED', c.created_at
FROM contract c JOIN perf_seed_office s ON s.office_id = c.office_id
WHERE c.contract_status IN ('SIGNED', 'ENDED');

INSERT INTO contract_status_history (contract_id, track, from_status, to_status, effective_at)
SELECT c.id, 'CONTRACT', 'SIGNED', 'ENDED', now() - interval '30 days'
FROM contract c JOIN perf_seed_office s ON s.office_id = c.office_id
WHERE c.contract_status = 'ENDED';

INSERT INTO contract_party (contract_id, side, sequence_no, name)
SELECT c.id, side.v, 1, '가상당사자'
FROM contract c JOIN perf_seed_office s ON s.office_id = c.office_id
CROSS JOIN (VALUES ('SELLER_LANDLORD'), ('BUYER_TENANT')) side(v)
WHERE c.contract_status IN ('SIGNED', 'ENDED');

INSERT INTO contract_fee (contract_id, payer_type, payer_party_id, agreed_fee_amount,
    vat_included, supply_amount, tax_amount, total_amount, billed_at)
SELECT c.id, 'BUYER_TENANT', p.id, 500000, false, 500000, 50000, 550000, c.signed_date
FROM contract c JOIN perf_seed_office s ON s.office_id = c.office_id
JOIN contract_party p ON p.contract_id = c.id AND p.side = 'BUYER_TENANT'
WHERE c.contract_status IN ('SIGNED', 'ENDED');

INSERT INTO contract_fee_revision (fee_id, revision_type, effective_date, agreed_fee_amount,
    supply_amount, tax_amount, total_amount, recorded_by_membership_id)
SELECT f.id, 'INITIAL', f.billed_at, 500000, 500000, 50000, 550000, c.managing_membership_id
FROM contract_fee f JOIN contract c ON c.id = f.contract_id
JOIN perf_seed_office s ON s.office_id = c.office_id;

INSERT INTO contract_fee_transaction (fee_id, transaction_type, amount, actual_date,
    recorded_by_membership_id)
SELECT f.id, 'RECEIPT', 550000, f.billed_at + 5, c.managing_membership_id
FROM contract_fee f JOIN contract c ON c.id = f.contract_id
JOIN perf_seed_office s ON s.office_id = c.office_id
WHERE f.id % 10 < 7;

INSERT INTO task (office_id, title, assignee_membership_id, due_date, related_customer_id,
    status, created_at, updated_at)
SELECT s.office_id, '가상 업무 ' || g, s.first_member_id + (g % 6),
    current_date - 30 + (g % 90), s.first_customer_id + (g % s.n_cust),
    CASE WHEN g % 100 < 40 THEN 'TODO' WHEN g % 100 < 95 THEN 'DONE' ELSE 'CANCELLED' END,
    now() - (g % 120) * interval '1 day', now() - (g % 120) * interval '1 day'
FROM perf_seed_office s CROSS JOIN LATERAL generate_series(1, s.n_cust / 2) g;

CREATE TABLE perf_seed_hot AS
SELECT row_number() OVER (ORDER BY p.id) AS idx, p.id AS property_id, p.office_id
FROM (SELECT p.id, p.office_id FROM property p
      WHERE p.office_id = (SELECT office_id FROM perf_seed_office WHERE kind = 'LARGE')
        AND p.deleted_at IS NULL AND p.registration_stage = 'ACTIVE'
      ORDER BY p.id OFFSET 1000 LIMIT @@APPROVER_LINKS@@) p;

INSERT INTO property_access_approval (property_id, approver_type, approver_name,
    approver_phone_encrypted, valid_from, valid_until, source, retention_until)
SELECT h.property_id, 'OWNER', '가상승인자', 'perf-not-a-real-value', now() - interval '30 days',
    now() + interval '60 days', 'DIRECT', current_date + 365
FROM perf_seed_hot h ORDER BY h.idx;

INSERT INTO visit_approver_condition (approval_id, version_no, applies_from, applies_until)
SELECT a.id, 1, now() - interval '30 days', now() + interval '60 days'
FROM property_access_approval a JOIN perf_seed_hot h ON h.property_id = a.property_id;

CREATE TABLE perf_seed_request AS
SELECT h.idx, h.property_id, h.office_id, k, s.first_member_id, s.first_customer_id, s.n_cust
FROM perf_seed_hot h JOIN perf_seed_office s ON s.office_id = h.office_id
CROSS JOIN generate_series(1, @@REQUESTS_PER_LINK@@) k;

INSERT INTO visit_coordination (office_id, customer_id, manager_membership_id, status,
    period_start_date, period_end_date)
SELECT r.office_id, r.first_customer_id + ((r.idx * 131 + r.k * 17) % r.n_cust),
    r.first_member_id + (r.k % 4), 'COORDINATABLE', current_date, current_date + 14
FROM perf_seed_request r ORDER BY r.idx, r.k;

CREATE TABLE perf_seed_request_coord AS
SELECT r.idx, r.k, vc.id AS coordination_id
FROM (SELECT idx, k, row_number() OVER (ORDER BY idx, k) AS rn FROM perf_seed_request) r
JOIN (SELECT id, row_number() OVER (ORDER BY id) AS rn FROM visit_coordination
      WHERE id > (SELECT max(coordination_id) FROM visit_candidate_property)) vc ON vc.rn = r.rn;

INSERT INTO visit_candidate_property (coordination_id, office_id, property_id, sort_order,
    property_office_id)
SELECT rc.coordination_id, h.office_id, h.property_id, 0, h.office_id
FROM perf_seed_request_coord rc JOIN perf_seed_hot h ON h.idx = rc.idx;

INSERT INTO visit_request (condition_id, approval_id, property_id, applicant_office_id,
    applicant_membership_id, coordination_id, candidate_property_id, requested_start_at,
    requested_end_at, expected_duration_minutes, property_office_id, status, submitted_at,
    updated_at)
SELECT cond.id, a.id, h.property_id, h.office_id, vc.manager_membership_id, vc.id, cp.id,
    date_trunc('day', now()) + (1 + rc.k % 20) * interval '1 day' + (9 + rc.k % 8) * interval '1 hour',
    date_trunc('day', now()) + (1 + rc.k % 20) * interval '1 day' + (9 + rc.k % 8) * interval '1 hour'
        + interval '30 minutes',
    30, h.office_id, 'PENDING', now() - rc.k * interval '1 hour', now() - rc.k * interval '1 hour'
FROM perf_seed_request_coord rc
JOIN perf_seed_hot h ON h.idx = rc.idx
JOIN visit_coordination vc ON vc.id = rc.coordination_id
JOIN visit_candidate_property cp ON cp.coordination_id = vc.id
JOIN property_access_approval a ON a.property_id = h.property_id
JOIN visit_approver_condition cond ON cond.approval_id = a.id;

CREATE TABLE perf_seed_hot_coordination AS
SELECT vc.id AS coordination_id, vc.office_id, vc.manager_membership_id
FROM visit_coordination vc
WHERE vc.office_id = (SELECT office_id FROM perf_seed_office WHERE kind = 'LARGE')
  AND vc.status = 'CONFIRMED'
ORDER BY vc.manager_membership_id, vc.id
LIMIT 1;
