package com.sairo.be.global.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class UploadSizeHandlerTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new Probe())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void 지원_티켓_첨부가_업로드_한도를_넘으면_422_ATTACHMENT_TOO_LARGE다() throws Exception {
    mockMvc
        .perform(post("/api/support-inquiries/1/attachments"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("ATTACHMENT_TOO_LARGE"));
  }

  @Test
  void 다른_업로드가_한도를_넘으면_413_FILE_TOO_LARGE다() throws Exception {
    mockMvc
        .perform(post("/api/import-batches"))
        .andExpect(status().isContentTooLarge())
        .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
  }

  @RestController
  static class Probe {

    @PostMapping({"/api/support-inquiries/{id}/attachments", "/api/import-batches"})
    void upload() {
      throw new MaxUploadSizeExceededException(20L * 1024 * 1024);
    }
  }
}
