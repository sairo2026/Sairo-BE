package com.sairo.be.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sairo.be.TestcontainersConfiguration;
import java.util.Iterator;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocumentationTest {

  private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");

  @Autowired private MockMvc mockMvc;

  @Test
  void 인증_없이_api_docs에_접근할_수_있고_18개_api가_전부_문서화돼_있다() throws Exception {
    String body =
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode root = new ObjectMapper().readTree(body);
    JsonNode paths = root.get("paths");
    assertThat(paths).isNotNull();

    int operationCount = 0;
    Iterator<String> pathNames = paths.fieldNames();
    while (pathNames.hasNext()) {
      JsonNode pathItem = paths.get(pathNames.next());
      Iterator<String> fieldNames = pathItem.fieldNames();
      while (fieldNames.hasNext()) {
        if (HTTP_METHODS.contains(fieldNames.next())) {
          operationCount++;
        }
      }
    }
    assertThat(operationCount).isEqualTo(18);

    JsonNode servers = root.get("servers");
    assertThat(servers).hasSize(1);
    assertThat(servers.get(0).get("url").asText()).isEqualTo("https://api.sairo.agency");
    assertThat(body).doesNotContain("localhost");

    assertThat(
            root.get("components").get("securitySchemes").has(OpenApiConfig.SESSION_COOKIE_SCHEME))
        .isTrue();
  }

  @Test
  void 인증_없이_swagger_ui_페이지에_접근할_수_있다() throws Exception {
    mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
  }
}
