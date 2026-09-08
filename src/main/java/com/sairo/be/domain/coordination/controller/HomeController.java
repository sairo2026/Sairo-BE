package com.sairo.be.domain.coordination.controller;

import com.sairo.be.domain.coordination.dto.response.HomeSummaryResponse;
import com.sairo.be.domain.coordination.service.HomeSummaryService;
import com.sairo.be.global.security.StaffPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/home")
@RequiredArgsConstructor
public class HomeController {

  private final HomeSummaryService homeSummaryService;

  @GetMapping
  public HomeSummaryResponse getSummary(@AuthenticationPrincipal StaffPrincipal principal) {
    return homeSummaryService.getSummary(principal.officeId());
  }
}
