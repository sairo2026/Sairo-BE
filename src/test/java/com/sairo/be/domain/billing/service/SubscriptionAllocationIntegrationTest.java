package com.sairo.be.domain.billing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.entity.SubscriptionStatus;
import com.sairo.be.domain.billing.service.SubscriptionAllocationService.VerificationFacts;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.BillingFixtures;
import com.sairo.be.support.fixture.TestData;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class SubscriptionAllocationIntegrationTest extends AuthIntegrationTestBase {

  private static final Instant START = Instant.parse("2026-11-29T15:00:00Z");
  private static final Instant MID = Instant.parse("2026-12-15T00:00:00Z");
  private static final Instant END = Instant.parse("2027-01-31T15:00:00Z");

  @Autowired private SubscriptionAllocationService allocationService;
  @Autowired private OfficeRepository officeRepository;
  @Autowired private OfficeMembershipRepository officeMembershipRepository;

  private BillingFixtures fixtures;

  @BeforeEach
  void seed() {
    fixtures = new BillingFixtures(jdbcTemplate);
  }

  private TestData.Staff owner() {
    return new TestData(appUserRepository, officeRepository, officeMembershipRepository)
        .approvedStaff(OfficeMembershipRole.OWNER);
  }

  private static VerificationFacts facts(TestData.Staff owner, Instant verifiedAt) {
    return new VerificationFacts(owner.office().getId(), verifiedAt, verifiedAt, verifiedAt);
  }

  private long general(String mode, Instant starts, Instant ends, Instant fixedEnd) {
    return fixtures.trialEvent("GENERAL", mode, starts, ends, fixedEnd, "ACTIVE");
  }

  @Test
  void 기간_방식_일반_이벤트는_인증_완료_시각에_60일을_더한_무료_종료_시각으로_체험을_시작한다() {
    long event = general("DURATION", START, END, null);
    TestData.Staff owner = owner();
    Subscription subscription = allocationService.allocateOnVerification(facts(owner, MID));
    assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.TRIAL);
    assertThat(subscription.getTrialStartedAt()).isEqualTo(MID);
    assertThat(subscription.getTrialEndsAt()).isEqualTo(MID.plus(Duration.ofDays(60)));
    assertThat(subscription.getFirstBillingScheduledAt()).isEqualTo(MID.plus(Duration.ofDays(60)));
    assertThat(subscription.getTrialEventId()).isEqualTo(event);
    assertThat(subscription.getPrePaymentSince()).isNull();
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing_invoice", Integer.class))
        .isZero();
  }

  @Test
  void 고정_종료_방식_일반_이벤트는_이벤트의_고정_무료_종료_시각을_쓴다() {
    Instant fixed = Instant.parse("2027-04-01T00:00:00+09:00");
    long event = general("FIXED_END", START, END, fixed);
    TestData.Staff owner = owner();
    Subscription subscription = allocationService.allocateOnVerification(facts(owner, MID));
    assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.TRIAL);
    assertThat(subscription.getTrialEndsAt()).isEqualTo(fixed);
    assertThat(subscription.getFirstBillingScheduledAt()).isEqualTo(fixed);
    assertThat(subscription.getTrialEventId()).isEqualTo(event);
  }

  @Test
  void 고정_종료_시각이_인증_완료_시각_이하이면_이벤트를_적용하지_않고_결제_전_상태가_된다() {
    general("FIXED_END", START, END, Instant.parse("2026-12-01T00:00:00Z"));
    TestData.Staff owner = owner();
    Subscription subscription = allocationService.allocateOnVerification(facts(owner, MID));
    assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.PRE_PAYMENT);
    assertThat(subscription.getPrePaymentSince()).isEqualTo(MID);
    assertThat(subscription.getTrialEventId()).isNull();
    assertThat(subscription.getFirstBillingScheduledAt()).isNull();
  }

  @Test
  void 활성_일반_이벤트가_없거나_가입_창_밖이면_결제_전_상태이다() {
    general("DURATION", START, END, null);
    TestData.Staff late = owner();
    Subscription outside =
        allocationService.allocateOnVerification(facts(late, END.plus(Duration.ofDays(1))));
    assertThat(outside.getStatus()).isEqualTo(SubscriptionStatus.PRE_PAYMENT);
    assertThat(outside.getPrePaymentSince()).isEqualTo(END.plus(Duration.ofDays(1)));
    TestData.Staff early = owner();
    Subscription before =
        allocationService.allocateOnVerification(facts(early, START.minus(Duration.ofSeconds(1))));
    assertThat(before.getStatus()).isEqualTo(SubscriptionStatus.PRE_PAYMENT);
  }

  @Test
  void 이벤트_상태가_활성이_아니면_적용하지_않는다() {
    fixtures.trialEvent("GENERAL", "DURATION", START, END, null, "SCHEDULED");
    fixtures.trialEvent(
        "GENERAL", "DURATION", Instant.parse("2025-01-01T00:00:00Z"), START, null, "ENDED");
    TestData.Staff owner = owner();
    Subscription subscription = allocationService.allocateOnVerification(facts(owner, MID));
    assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.PRE_PAYMENT);
  }

  @Test
  void 가입_창_종료_시각과_같은_인증_완료는_그_이벤트가_아니라_다음_이벤트에_속한다() {
    general("FIXED_END", START, END, Instant.parse("2027-07-01T00:00:00Z"));
    long next =
        general(
            "FIXED_END",
            END,
            Instant.parse("2027-03-02T00:00:00Z"),
            Instant.parse("2027-04-01T00:00:00Z"));
    TestData.Staff owner = owner();
    Subscription subscription = allocationService.allocateOnVerification(facts(owner, END));
    assertThat(subscription.getTrialEventId()).isEqualTo(next);
    assertThat(subscription.getTrialEndsAt()).isEqualTo(Instant.parse("2027-04-01T00:00:00Z"));
  }

  @Test
  void 가입_창_시작_시각과_같은_인증_완료는_그_이벤트에_속한다() {
    long event = general("DURATION", START, END, null);
    TestData.Staff owner = owner();
    Subscription subscription = allocationService.allocateOnVerification(facts(owner, START));
    assertThat(subscription.getTrialEventId()).isEqualTo(event);
  }

  @Test
  void 이벤트가_사무소_생성_시각이나_최초_신청_시각이_창_안일_것을_요구하면_창_밖_사무소는_제외한다() {
    long event = general("DURATION", START, END, null);
    TestData.Staff createdBefore = owner();
    Subscription excluded =
        allocationService.allocateOnVerification(
            new VerificationFacts(
                createdBefore.office().getId(), MID, START.minus(Duration.ofDays(1)), MID));
    assertThat(excluded.getStatus()).isEqualTo(SubscriptionStatus.PRE_PAYMENT);
    TestData.Staff appliedBefore = owner();
    Subscription excludedByApplication =
        allocationService.allocateOnVerification(
            new VerificationFacts(
                appliedBefore.office().getId(), MID, MID, START.minus(Duration.ofDays(1))));
    assertThat(excludedByApplication.getStatus()).isEqualTo(SubscriptionStatus.PRE_PAYMENT);

    jdbcTemplate.update(
        "UPDATE trial_event SET office_created_during = false, first_application_during = false"
            + " WHERE id = ?",
        event);
    TestData.Staff relaxed = owner();
    Subscription included =
        allocationService.allocateOnVerification(
            new VerificationFacts(
                relaxed.office().getId(),
                MID,
                START.minus(Duration.ofDays(30)),
                START.minus(Duration.ofDays(20))));
    assertThat(included.getStatus()).isEqualTo(SubscriptionStatus.TRIAL);
  }

  @Test
  void 승인된_시작_고객_합의가_가입_창_안에_있으면_일반_이벤트보다_시작_고객_이벤트를_쓴다() {
    general("DURATION", START, END, null);
    Instant freeEnd = Instant.parse("2027-07-01T00:00:00+09:00");
    long startEvent =
        fixtures.trialEvent("START_CUSTOMER", "FIXED_END", START, END, freeEnd, "ACTIVE");
    long price =
        fixtures.price(
            "START-V", "START_CUSTOMER", MID.minus(Duration.ofDays(1)), "ACTIVE", 29_900);
    TestData.Staff owner = owner();
    fixtures.startCustomerAgreement(
        owner.office().getId(), startEvent, price, MID, MID.plus(Duration.ofDays(365)), "APPROVED");
    jdbcTemplate.update(
        "UPDATE billing_start_customer_agreement SET approved_at = ?",
        java.sql.Timestamp.from(MID));
    Subscription subscription = allocationService.allocateOnVerification(facts(owner, MID));
    assertThat(subscription.getTrialEventId()).isEqualTo(startEvent);
    assertThat(subscription.getTrialEndsAt()).isEqualTo(freeEnd);
    assertThat(subscription.getFirstBillingScheduledAt()).isEqualTo(freeEnd);
  }

  @Test
  void 합의_승인_시각이_이벤트_창_밖이거나_합의가_승인_상태가_아니면_일반_이벤트로_배정한다() {
    long general = general("DURATION", START, END, null);
    Instant freeEnd = Instant.parse("2027-07-01T00:00:00+09:00");
    long startEvent =
        fixtures.trialEvent("START_CUSTOMER", "FIXED_END", START, END, freeEnd, "ACTIVE");
    long price =
        fixtures.price(
            "START-V", "START_CUSTOMER", MID.minus(Duration.ofDays(1)), "ACTIVE", 29_900);
    TestData.Staff outside = owner();
    fixtures.startCustomerAgreement(
        outside.office().getId(),
        startEvent,
        price,
        MID,
        MID.plus(Duration.ofDays(365)),
        "APPROVED");
    jdbcTemplate.update(
        "UPDATE billing_start_customer_agreement SET approved_at = ? WHERE office_id = ?",
        java.sql.Timestamp.from(END.plus(Duration.ofDays(1))),
        outside.office().getId());
    assertThat(allocationService.allocateOnVerification(facts(outside, MID)).getTrialEventId())
        .isEqualTo(general);
    TestData.Staff registered = owner();
    fixtures.startCustomerAgreement(
        registered.office().getId(),
        startEvent,
        price,
        MID,
        MID.plus(Duration.ofDays(365)),
        "REGISTERED");
    assertThat(allocationService.allocateOnVerification(facts(registered, MID)).getTrialEventId())
        .isEqualTo(general);
  }

  @Test
  void 이미_체험_중인_구독에_다시_배정해도_바뀌지_않고_결제_전_구독은_이벤트가_생기면_체험이_된다() {
    TestData.Staff owner = owner();
    Subscription pre = allocationService.allocateOnVerification(facts(owner, MID));
    assertThat(pre.getStatus()).isEqualTo(SubscriptionStatus.PRE_PAYMENT);
    long event = general("DURATION", START, END, null);
    Subscription trial = allocationService.allocateOnVerification(facts(owner, MID));
    assertThat(trial.getStatus()).isEqualTo(SubscriptionStatus.TRIAL);
    assertThat(trial.getTrialEventId()).isEqualTo(event);
    assertThat(trial.getPrePaymentSince()).isNull();
    Instant originalEnd = trial.getTrialEndsAt();
    Subscription again =
        allocationService.allocateOnVerification(facts(owner, MID.plus(Duration.ofDays(5))));
    assertThat(again.getTrialEndsAt()).isEqualTo(originalEnd);
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM subscription", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void 시작_고객_합의_승인_뒤_승격은_무료_종료를_늦추는_방향으로만_바꾸고_상태를_체험으로_둔다() {
    long generalEvent = general("DURATION", START, END, null);
    TestData.Staff owner = owner();
    Subscription trial = allocationService.allocateOnVerification(facts(owner, MID));
    Instant before = trial.getTrialEndsAt();
    Instant freeEnd = Instant.parse("2027-07-01T00:00:00+09:00");
    long startEvent =
        fixtures.trialEvent("START_CUSTOMER", "FIXED_END", START, END, freeEnd, "ACTIVE");
    long price =
        fixtures.price(
            "START-V", "START_CUSTOMER", MID.minus(Duration.ofDays(1)), "ACTIVE", 29_900);
    fixtures.startCustomerAgreement(
        owner.office().getId(), startEvent, price, MID, MID.plus(Duration.ofDays(365)), "APPROVED");
    jdbcTemplate.update(
        "UPDATE billing_start_customer_agreement SET approved_at = ?",
        java.sql.Timestamp.from(MID));
    Optional<Subscription> promoted =
        allocationService.promoteStartCustomer(owner.office().getId());
    assertThat(promoted).isPresent();
    assertThat(promoted.get().getStatus()).isEqualTo(SubscriptionStatus.TRIAL);
    assertThat(promoted.get().getTrialEndsAt()).isEqualTo(freeEnd).isAfter(before);
    assertThat(promoted.get().getFirstBillingScheduledAt()).isEqualTo(freeEnd);
    assertThat(promoted.get().getTrialEventId()).isEqualTo(startEvent).isNotEqualTo(generalEvent);
    assertThat(promoted.get().getTrialStartedAt()).isEqualTo(MID);
  }

  @Test
  void 승격은_이미_부여한_무료_기간을_줄이지_않는다() {
    general("DURATION", START, END, null);
    TestData.Staff owner = owner();
    Subscription trial = allocationService.allocateOnVerification(facts(owner, MID));
    Instant before = trial.getTrialEndsAt();
    Instant earlierFreeEnd = MID.plus(Duration.ofDays(10));
    long startEvent =
        fixtures.trialEvent("START_CUSTOMER", "FIXED_END", START, END, earlierFreeEnd, "ACTIVE");
    long price =
        fixtures.price(
            "START-V", "START_CUSTOMER", MID.minus(Duration.ofDays(1)), "ACTIVE", 29_900);
    fixtures.startCustomerAgreement(
        owner.office().getId(), startEvent, price, MID, MID.plus(Duration.ofDays(365)), "APPROVED");
    jdbcTemplate.update(
        "UPDATE billing_start_customer_agreement SET approved_at = ?",
        java.sql.Timestamp.from(MID));
    Subscription result =
        allocationService.promoteStartCustomer(owner.office().getId()).orElseThrow();
    assertThat(result.getTrialEndsAt()).isEqualTo(before);
    assertThat(result.getTrialEventId()).isNotEqualTo(startEvent);
  }

  @Test
  void 청구서가_있으면_승격하지_않고_결제_전_구독은_체험으로_승격한다() {
    TestData.Staff withInvoice = owner();
    Instant freeEnd = Instant.parse("2027-07-01T00:00:00+09:00");
    long startEvent =
        fixtures.trialEvent("START_CUSTOMER", "FIXED_END", START, END, freeEnd, "ACTIVE");
    long price =
        fixtures.price(
            "START-V", "START_CUSTOMER", MID.minus(Duration.ofDays(1)), "ACTIVE", 29_900);
    fixtures.purchaseTerms(BillingFixtures.PURCHASE_TERMS_VERSION);
    long subscriptionId =
        fixtures
            .subscription(withInvoice.office().getId())
            .status("PRE_PAYMENT")
            .prePaymentSince(MID)
            .insert();
    long consent =
        fixtures.consent(
            subscriptionId,
            withInvoice.user().getId(),
            withInvoice.office().getId(),
            39_900,
            3_627,
            39_900);
    fixtures.invoice(withInvoice.office().getId(), subscriptionId, consent).insert();
    fixtures.startCustomerAgreement(
        withInvoice.office().getId(),
        startEvent,
        price,
        MID,
        MID.plus(Duration.ofDays(365)),
        "APPROVED");
    jdbcTemplate.update(
        "UPDATE billing_start_customer_agreement SET approved_at = ?",
        java.sql.Timestamp.from(MID));
    Subscription unchanged =
        allocationService.promoteStartCustomer(withInvoice.office().getId()).orElseThrow();
    assertThat(unchanged.getStatus()).isEqualTo(SubscriptionStatus.PRE_PAYMENT);

    TestData.Staff noInvoice = owner();
    fixtures
        .subscription(noInvoice.office().getId())
        .status("PRE_PAYMENT")
        .prePaymentSince(MID)
        .insert();
    fixtures.startCustomerAgreement(
        noInvoice.office().getId(),
        startEvent,
        price,
        MID,
        MID.plus(Duration.ofDays(365)),
        "APPROVED");
    jdbcTemplate.update(
        "UPDATE billing_start_customer_agreement SET approved_at = ? WHERE office_id = ?",
        java.sql.Timestamp.from(MID),
        noInvoice.office().getId());
    Subscription promoted =
        allocationService.promoteStartCustomer(noInvoice.office().getId()).orElseThrow();
    assertThat(promoted.getStatus()).isEqualTo(SubscriptionStatus.TRIAL);
    assertThat(promoted.getTrialEndsAt()).isEqualTo(freeEnd);
    assertThat(promoted.getPrePaymentSince()).isNull();
  }

  @Test
  void 구독이_없는_사무소의_승격은_아무것도_만들지_않는다() {
    TestData.Staff owner = owner();
    assertThat(allocationService.promoteStartCustomer(owner.office().getId())).isEmpty();
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM subscription", Integer.class))
        .isZero();
  }
}
