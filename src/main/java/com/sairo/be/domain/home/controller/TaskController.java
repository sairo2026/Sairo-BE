package com.sairo.be.domain.home.controller;

import com.sairo.be.domain.home.dto.response.TaskListItem;
import com.sairo.be.domain.home.dto.response.TaskResponse;
import com.sairo.be.domain.home.service.TaskService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.IfMatchVersion;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@RequestMapping("/api/tasks")
@RequiresAccess(AccessPermission.STAFF)
@Tag(name = "HOME", description = "업무")
public class TaskController {

  private final TaskService taskService;

  public TaskController(TaskService taskService) {
    this.taskService = taskService;
  }

  @Operation(
      summary = "업무 목록",
      description = "status(쉼표로 여러 값)와 assigneeMembershipId로 거른다. 중개보조원은 본인 담당 업무만 본다.")
  @GetMapping
  public PageResponse<TaskListItem> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Long assigneeMembershipId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return taskService.list(principal, status, assigneeMembershipId, PageQuery.of(page, size));
  }

  @Operation(summary = "업무 생성", description = "수동 업무를 만든다. 중개보조원은 본인을 담당자로만 만든다.")
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public TaskResponse create(
      @RequestBody JsonNode body, @AuthenticationPrincipal StaffPrincipal principal) {
    return taskService.create(principal, body);
  }

  @Operation(
      summary = "업무 수정",
      description = "변경할 필드만 보낸다. 자동 생성 업무는 완료 처리, 완료 취소, 메모만 바꿀 수 있다. If-Match에 현재 version을 보낸다.")
  @PatchMapping("/{taskId}")
  public TaskResponse update(
      @PathVariable Long taskId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return taskService.update(principal, taskId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(summary = "업무 삭제", description = "수동 업무만 지운다.")
  @DeleteMapping("/{taskId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable Long taskId, @AuthenticationPrincipal StaffPrincipal principal) {
    taskService.delete(principal, taskId);
  }
}
