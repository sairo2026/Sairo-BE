package com.sairo.be.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

public abstract class ExportIntegrationTestBase extends SupportIntegrationTestBase {

  protected ResultActions postExport(Cookie cookie, String body) throws Exception {
    return mockMvc.perform(jsonPostAs("/api/exports", body, cookie));
  }

  protected ResultActions getExport(Cookie cookie, Object exportId) throws Exception {
    return mockMvc.perform(authedGet("/api/exports/" + exportId, cookie));
  }

  protected long createExport(Cookie cookie, String body) throws Exception {
    MvcResult result = postExport(cookie, body).andExpect(status().isCreated()).andReturn();
    return AuthFlowSupport.json(result).get("exportId").longValue();
  }

  protected static String tablesBody(String... codes) {
    StringBuilder builder = new StringBuilder("{\"scope\":\"TABLES\",\"tables\":[");
    for (int i = 0; i < codes.length; i++) {
      builder.append(i == 0 ? "" : ",").append('"').append(codes[i]).append('"');
    }
    return builder.append("]}").toString();
  }

  protected void allow(long officeId, String... codes) {
    jdbcTemplate.update("DELETE FROM office_export_table_policy WHERE office_id = ?", officeId);
    for (String code : codes) {
      jdbcTemplate.update(
          "INSERT INTO office_export_table_policy (office_id, table_code) VALUES (?, ?)",
          officeId,
          code);
    }
  }

  protected String exportStatus(long exportId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM export_job WHERE id = ?", String.class, exportId);
  }

  protected String exportKey(long exportId) {
    return jdbcTemplate.queryForObject(
        "SELECT file_object_key FROM export_job WHERE id = ?", String.class, exportId);
  }

  protected void expireExport(long exportId) {
    jdbcTemplate.update(
        "UPDATE export_job SET created_at = now() - interval '25 hours', file_expires_at = now() -"
            + " interval '1 hour' WHERE id = ?",
        exportId);
  }

  protected Map<String, byte[]> unzipExport(long exportId) {
    byte[] archive = objectStorage.find(exportKey(exportId)).orElseThrow();
    Map<String, byte[]> entries = new LinkedHashMap<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        entries.put(entry.getName(), zip.readAllBytes());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return entries;
  }

  protected JsonNode body(ResultActions result) throws Exception {
    return AuthFlowSupport.json(result.andReturn());
  }
}
