package com.sairo.be.domain.visit.mapper;

import com.sairo.be.domain.visit.dto.response.VisitRequestDetailResponse;
import com.sairo.be.domain.visit.dto.response.VisitRequestListResponse;
import com.sairo.be.domain.visit.dto.response.VisitRequestNoticeListResponse;
import com.sairo.be.domain.visit.dto.response.VisitRequestResponse;
import com.sairo.be.domain.visit.entity.VisitRequest;
import com.sairo.be.domain.visit.entity.VisitRequestNotice;
import com.sairo.be.domain.visit.entity.VisitRequestStatusHistory;
import com.sairo.be.domain.visit.repository.VisitRequestReadRepository.RevalidationRow;
import com.sairo.be.domain.visit.repository.VisitRequestReadRepository.SelectionRow;
import com.sairo.be.global.support.TimeRules;
import java.util.List;
import java.util.Map;

public final class VisitRequestMapper {

  private VisitRequestMapper() {}

  public static VisitRequestResponse toResponse(VisitRequest request) {
    return new VisitRequestResponse(
        request.getId(),
        request.getConditionId(),
        request.getApprovalId(),
        request.getPropertyId(),
        request.getPropertyOfficeId(),
        request.isCrossOffice(),
        request.getAccessGrantId(),
        request.getApplicantOfficeId(),
        request.getApplicantMembershipId(),
        request.getCoordinationId(),
        request.getCandidatePropertyId(),
        request.getRequestKind().name(),
        request.getReplacesScheduleItemId(),
        TimeRules.toDisplayOffset(request.getRequestedStartAt()),
        TimeRules.toDisplayOffset(request.getRequestedEndAt()),
        request.getExpectedDurationMinutes(),
        request.getPartySize(),
        request.getRequestNote(),
        request.getStatus().name(),
        request.getStatusReason(),
        TimeRules.toDisplayOffset(request.getSubmittedAt()),
        TimeRules.toDisplayOffset(request.getClosedAt()),
        TimeRules.toDisplayOffset(request.getUpdatedAt()),
        request.getVersion());
  }

  public static VisitRequestListResponse toList(List<VisitRequest> requests) {
    return new VisitRequestListResponse(
        requests.stream()
            .map(
                request ->
                    new VisitRequestListResponse.Item(
                        request.getId(),
                        request.getPropertyId(),
                        request.getPropertyOfficeId(),
                        request.isCrossOffice(),
                        request.getAccessGrantId(),
                        request.getCandidatePropertyId(),
                        request.getStatus().name(),
                        TimeRules.toDisplayOffset(request.getRequestedStartAt()),
                        TimeRules.toDisplayOffset(request.getRequestedEndAt()),
                        TimeRules.toDisplayOffset(request.getSubmittedAt()),
                        TimeRules.toDisplayOffset(request.getClosedAt()),
                        request.getStatusReason(),
                        request.getVersion()))
            .toList());
  }

  public static VisitRequestDetailResponse toDetail(
      VisitRequest request,
      List<VisitRequestStatusHistory> history,
      List<SelectionRow> selections,
      List<RevalidationRow> revalidations) {
    return new VisitRequestDetailResponse(
        request.getId(),
        request.getConditionId(),
        request.getApprovalId(),
        request.getPropertyId(),
        request.getPropertyOfficeId(),
        request.isCrossOffice(),
        request.getAccessGrantId(),
        request.getApplicantOfficeId(),
        request.getApplicantMembershipId(),
        request.getCoordinationId(),
        request.getCandidatePropertyId(),
        request.getRequestKind().name(),
        request.getReplacesScheduleItemId(),
        TimeRules.toDisplayOffset(request.getRequestedStartAt()),
        TimeRules.toDisplayOffset(request.getRequestedEndAt()),
        request.getExpectedDurationMinutes(),
        request.getPartySize(),
        request.getRequestNote(),
        request.getStatus().name(),
        request.getStatusReason(),
        TimeRules.toDisplayOffset(request.getSubmittedAt()),
        TimeRules.toDisplayOffset(request.getClosedAt()),
        TimeRules.toDisplayOffset(request.getUpdatedAt()),
        request.getVersion(),
        history.stream()
            .map(
                row ->
                    new VisitRequestDetailResponse.StatusHistory(
                        row.getFromStatus() == null ? null : row.getFromStatus().name(),
                        row.getToStatus().name(),
                        row.getSource().name(),
                        row.getReason(),
                        TimeRules.toDisplayOffset(row.getEffectiveAt())))
            .toList(),
        selections.stream()
            .map(
                row ->
                    new VisitRequestDetailResponse.Selection(
                        row.id(),
                        row.comparedRequestCount(),
                        TimeRules.toDisplayOffset(row.selectedAt())))
            .toList(),
        revalidations.stream()
            .map(
                row ->
                    new VisitRequestDetailResponse.Revalidation(
                        row.id(),
                        row.selectionId(),
                        row.conditionId(),
                        row.result(),
                        row.failureCode(),
                        row.failureDetail(),
                        TimeRules.toDisplayOffset(row.checkedAt())))
            .toList());
  }

  public static VisitRequestNoticeListResponse toNotices(
      List<VisitRequestNotice> notices, Map<Long, Long> coordinationByRequest) {
    return new VisitRequestNoticeListResponse(
        notices.stream()
            .map(
                notice ->
                    new VisitRequestNoticeListResponse.Item(
                        notice.getId(),
                        notice.getRequestId(),
                        coordinationByRequest.get(notice.getRequestId()),
                        notice.getNoticeType().name(),
                        notice.getChannel(),
                        TimeRules.toDisplayOffset(notice.getCreatedAt()),
                        TimeRules.toDisplayOffset(notice.getReadAt())))
            .toList());
  }
}
