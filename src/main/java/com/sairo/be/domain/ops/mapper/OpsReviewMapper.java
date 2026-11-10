package com.sairo.be.domain.ops.mapper;

import com.sairo.be.domain.office.entity.EvidenceType;
import com.sairo.be.domain.office.entity.Office;
import com.sairo.be.domain.office.entity.OfficeChangeRequest;
import com.sairo.be.domain.office.entity.OfficeRegistration;
import com.sairo.be.domain.office.repository.OfficeRegistrationRecordRepository;
import com.sairo.be.domain.ops.dto.response.OfficeRegistrationReviewChecklistResponse;
import com.sairo.be.domain.ops.dto.response.OperatorRegistrationDetailResponse;
import com.sairo.be.domain.ops.dto.response.OperatorRegistrationListItemResponse;
import com.sairo.be.domain.ops.dto.response.OpsOfficeChangeItemResponse;
import com.sairo.be.domain.ops.dto.response.OpsOfficeChangeResultResponse;
import com.sairo.be.domain.ops.dto.response.OpsRegistrationResponse;
import com.sairo.be.domain.ops.dto.response.ReviewChecklistItemResponse;
import com.sairo.be.domain.ops.entity.ReviewItemCode;
import com.sairo.be.domain.ops.repository.RegistrationReviewCheckRepository;
import com.sairo.be.global.support.TimeRules;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class OpsReviewMapper {

  private OpsReviewMapper() {}

  public static OperatorRegistrationListItemResponse toListItem(OfficeRegistration registration) {
    return new OperatorRegistrationListItemResponse(
        registration.getId(),
        registration.getRequestedOfficeName(),
        registration.getBusinessRegistrationNumber(),
        registration.getStatus().name(),
        TimeRules.toDisplayOffset(registration.getCreatedAt()));
  }

  public static List<ReviewChecklistItemResponse> toChecklist(
      Map<ReviewItemCode, RegistrationReviewCheckRepository.Row> rows) {
    return Arrays.stream(ReviewItemCode.values())
        .map(
            code -> {
              var row = rows.get(code);
              return row == null
                  ? new ReviewChecklistItemResponse(code.name(), false, null, null)
                  : new ReviewChecklistItemResponse(
                      code.name(),
                      row.checked(),
                      row.checkedByOperatorName(),
                      TimeRules.toDisplayOffset(row.checkedAt()));
            })
        .toList();
  }

  public static boolean canApprove(List<ReviewChecklistItemResponse> checklist) {
    return checklist.stream().allMatch(ReviewChecklistItemResponse::checked);
  }

  public static OfficeRegistrationReviewChecklistResponse toChecklistResponse(
      OfficeRegistration registration, List<ReviewChecklistItemResponse> checklist) {
    return new OfficeRegistrationReviewChecklistResponse(
        registration.getId(),
        registration.getCurrentRevisionNo(),
        checklist,
        canApprove(checklist));
  }

  public static OperatorRegistrationDetailResponse toDetail(
      OfficeRegistration registration,
      List<OfficeRegistrationRecordRepository.RevisionRow> revisions,
      Map<Integer, List<EvidenceType>> evidenceByRevision,
      List<ReviewChecklistItemResponse> checklist,
      List<OfficeRegistrationRecordRepository.HistoryRow> history) {
    boolean hasEvidence =
        !evidenceByRevision.getOrDefault(registration.getCurrentRevisionNo(), List.of()).isEmpty();
    return new OperatorRegistrationDetailResponse(
        registration.getId(),
        registration.getApplicantUserId(),
        registration.getBusinessRegistrationNumber(),
        registration.getRealEstateLicenseNumber(),
        registration.getRequestedOfficeName(),
        registration.getRequestedRepresentativeName(),
        registration.getRequestedRepresentativePhone(),
        registration.getRequestedAddress(),
        registration.getStatus().name(),
        hasEvidence,
        registration.getEntityType().name(),
        registration.getRegistrationAuthority(),
        registration.getApplicantRegistrationRelation(),
        registration.getCurrentRevisionNo(),
        revisions.stream()
            .map(
                row ->
                    new OperatorRegistrationDetailResponse.Revision(
                        row.revisionNo(),
                        TimeRules.toDisplayOffset(row.submittedAt()),
                        row.requestedOfficeName(),
                        row.requestedAddress(),
                        evidenceByRevision.getOrDefault(row.revisionNo(), List.of()).stream()
                            .map(Enum::name)
                            .toList()))
            .toList(),
        checklist,
        canApprove(checklist),
        registration.getRejectionReason(),
        history.stream()
            .map(
                row ->
                    new OperatorRegistrationDetailResponse.History(
                        row.id(),
                        row.fromStatus() == null ? null : row.fromStatus().name(),
                        row.toStatus().name(),
                        row.changedByType().name(),
                        row.changedByOperatorId(),
                        row.reason(),
                        TimeRules.toDisplayOffset(row.createdAt())))
            .toList());
  }

  public static OpsRegistrationResponse toRegistrationResponse(OfficeRegistration registration) {
    return new OpsRegistrationResponse(
        registration.getId(),
        registration.getStatus().name(),
        registration.getResultingOfficeId(),
        registration.getCurrentRevisionNo(),
        TimeRules.toDisplayOffset(registration.getCreatedAt()),
        TimeRules.toDisplayOffset(registration.getUpdatedAt()),
        registration.getVersion());
  }

  public static OpsOfficeChangeItemResponse toChangeItem(
      OfficeChangeRequest request, Map<String, String> values) {
    return new OpsOfficeChangeItemResponse(
        request.getId(),
        request.getOfficeId(),
        request.getRequestedByMembershipId(),
        request.getChangeType().name(),
        values,
        request.getStatus().name(),
        TimeRules.toDisplayOffset(request.getReviewedAt()),
        request.getRejectionReason(),
        TimeRules.toDisplayOffset(request.getCreatedAt()));
  }

  public static OpsOfficeChangeResultResponse toChangeResult(
      OfficeChangeRequest request, Office office) {
    return new OpsOfficeChangeResultResponse(
        request.getId(),
        request.getStatus().name(),
        TimeRules.toDisplayOffset(request.getReviewedAt()),
        office.getId(),
        TimeRules.toDisplayOffset(office.getUpdatedAt()),
        office.getVersion());
  }
}
