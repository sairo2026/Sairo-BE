-- Existing campaigns remain operator-only. A self-redeemable code requires explicit operator opt-in.
ALTER TABLE promotion_campaign
    ADD COLUMN self_redeemable BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE promotion_campaign
    ADD CONSTRAINT ck_self_redeemable_campaign_limit
        CHECK (NOT self_redeemable OR
            (max_offices IS NOT NULL AND code IS NOT NULL AND length(code) >= 16));

-- Keep an immutable actor for either operator grants or owner redemptions.
ALTER TABLE promotion_grant
    ALTER COLUMN granted_by_operator_id DROP NOT NULL;
ALTER TABLE promotion_grant
    ADD COLUMN redeemed_by_membership_id BIGINT;
ALTER TABLE promotion_grant
    ADD CONSTRAINT fk_promotion_redemption_office_membership
        FOREIGN KEY (redeemed_by_membership_id, office_id)
        REFERENCES office_membership(id, office_id);
ALTER TABLE promotion_grant
    ADD CONSTRAINT ck_promotion_grant_actor
        CHECK ((granted_by_operator_id IS NOT NULL) <> (redeemed_by_membership_id IS NOT NULL));
