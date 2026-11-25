ALTER TABLE billing_charge_attempt ADD COLUMN pg_receipt_url TEXT;
COMMENT ON COLUMN billing_charge_attempt.pg_receipt_url IS 'PG 승인·조회 응답의 receipt.url. 성공한 실제 카드 결제 시도에만 저장한다.';
