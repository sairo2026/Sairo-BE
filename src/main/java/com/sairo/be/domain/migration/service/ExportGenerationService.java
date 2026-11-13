package com.sairo.be.domain.migration.service;

import com.sairo.be.domain.migration.repository.ExportJobJdbcRepository;
import com.sairo.be.domain.migration.repository.ExportJobJdbcRepository.Row;
import com.sairo.be.global.port.ObjectStorage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSetMetaData;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Slf4j
@Profile("!migrate")
@Service
public class ExportGenerationService {

  public static final String FAILURE_REASON = "내보내기 파일을 만들지 못했습니다.";
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
  private static final int SWEEP_LIMIT = 50;

  private record Attachment(String directory, long id, String objectKey) {}

  private final ExportJobJdbcRepository jobRepository;
  private final JdbcClient jdbcClient;
  private final ObjectStorage objectStorage;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  public ExportGenerationService(
      ExportJobJdbcRepository jobRepository,
      JdbcClient jdbcClient,
      ObjectStorage objectStorage,
      TransactionTemplate transactionTemplate,
      Clock clock) {
    this.jobRepository = jobRepository;
    this.jdbcClient = jdbcClient;
    this.objectStorage = objectStorage;
    this.transactionTemplate = transactionTemplate;
    this.clock = clock;
  }

  public void generate(long jobId) {
    try {
      transactionTemplate.executeWithoutResult(status -> generateLocked(jobId));
    } catch (RuntimeException e) {
      log.warn("내보내기 파일 생성에 실패했습니다. exportId={}", jobId, e);
      transactionTemplate.executeWithoutResult(
          status -> jobRepository.failPending(jobId, FAILURE_REASON, now()));
    }
  }

  public int generatePending() {
    List<Long> ids = jobRepository.findPendingIds(SWEEP_LIMIT);
    ids.forEach(this::generate);
    return ids.size();
  }

  public int purgeExpired() {
    List<Row> expired = jobRepository.findExpiredWithFile(clock.instant(), SWEEP_LIMIT);
    for (Row row : expired) {
      objectStorage.delete(row.fileObjectKey());
      jobRepository.clearFile(row.id(), now());
    }
    return expired.size();
  }

  private void generateLocked(long jobId) {
    Optional<Row> locked = jobRepository.lockPending(jobId);
    if (locked.isEmpty()) {
      return;
    }
    Row job = locked.get();
    byte[] archive = build(job);
    String key = "exports/" + job.officeId() + "/" + job.id() + "-" + UUID.randomUUID() + ".zip";
    objectStorage.put(key, archive, "application/zip");
    try {
      jobRepository.complete(job.id(), key, now());
    } catch (RuntimeException e) {
      objectStorage.delete(key);
      throw e;
    }
  }

  private static final String RETAINED_DOCUMENT_CONDITION =
      " AND (t.retention_end_date IS NULL OR t.retention_end_date >= CURRENT_DATE)"
          + " AND EXISTS (SELECT 1 FROM legal_retention_rule r WHERE r.rule_code ="
          + " CASE t.document_type WHEN 'SALES_CONTRACT' THEN 'OFFICE_CONTRACT_DOCUMENT'"
          + " ELSE 'OFFICE_EXPLANATION_DOCUMENT' END)";

  private static final List<ExportTables.Source> ARCHIVE_SOURCES =
      List.of(
          new ExportTables.Source(
              "contract.csv",
              "SELECT t.* FROM contract t WHERE t.office_id = :officeId ORDER BY t.id"),
          new ExportTables.Source(
              "contract_document.csv",
              "SELECT t.id, t.contract_id, t.contract_revision_id, t.document_type, t.version_no,"
                  + " t.supersedes_document_id, t.written_date, t.delivered_date,"
                  + " t.date_pending_reason, t.retention_end_date, t.retention_years,"
                  + " t.pii_masking_confirmed, t.uploaded_by_membership_id, t.uploaded_at FROM"
                  + " contract_document t JOIN contract c ON c.id = t.contract_id WHERE"
                  + " c.office_id = :officeId"
                  + RETAINED_DOCUMENT_CONDITION
                  + " ORDER BY t.id"),
          new ExportTables.Source(
              "contract_fee.csv",
              "SELECT t.* FROM contract_fee t JOIN contract c ON c.id = t.contract_id"
                  + " WHERE c.office_id = :officeId ORDER BY t.id"),
          new ExportTables.Source(
              "contract_fee_transaction.csv",
              "SELECT t.* FROM contract_fee_transaction t JOIN contract_fee f ON f.id = t.fee_id"
                  + " JOIN contract c ON c.id = f.contract_id WHERE c.office_id = :officeId"
                  + " ORDER BY t.id"),
          new ExportTables.Source(
              "contract_payment_schedule.csv",
              "SELECT t.* FROM contract_payment_schedule t JOIN contract c ON c.id ="
                  + " t.contract_id WHERE c.office_id = :officeId ORDER BY t.id"),
          new ExportTables.Source(
              "contract_payment_transaction.csv",
              "SELECT t.* FROM contract_payment_transaction t JOIN contract_payment_schedule s ON"
                  + " s.id = t.schedule_id JOIN contract c ON c.id = s.contract_id WHERE"
                  + " c.office_id = :officeId ORDER BY t.id"));

  private byte[] buildArchive(Row job) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Map<String, Long> rowCounts = new LinkedHashMap<>();
    try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
      for (ExportTables.Source source : ARCHIVE_SOURCES) {
        zip.putNextEntry(new ZipEntry("data/" + source.fileName()));
        rowCounts.put(
            "data/" + source.fileName(), writeCsvQuery(zip, source.sql(), job.officeId()));
        zip.closeEntry();
      }
      ArrayNode attachmentsNode = MAPPER.createArrayNode();
      long included = 0;
      long missing = 0;
      for (Attachment attachment : retainedDocumentAttachments(job.officeId())) {
        Optional<byte[]> content = objectStorage.find(attachment.objectKey());
        ObjectNode node = attachmentsNode.addObject();
        String path = attachmentPath(attachment);
        node.put("path", path);
        if (content.isPresent()) {
          zip.putNextEntry(new ZipEntry(path));
          zip.write(content.get());
          zip.closeEntry();
          node.put("included", true);
          included++;
        } else {
          node.put("included", false);
          missing++;
        }
      }
      ObjectNode manifest = MAPPER.createObjectNode();
      manifest.put("exportId", job.id());
      manifest.put("scope", job.scope());
      manifest.put("archiveGrantId", job.archiveGrantId());
      manifest.put("generatedAt", clock.instant().toString());
      manifest.put("encoding", "UTF-8 (BOM)");
      ObjectNode files = manifest.putObject("rowCounts");
      rowCounts.forEach(files::put);
      manifest.putObject("attachmentSummary").put("included", included).put("missing", missing);
      manifest.set("attachments", attachmentsNode);
      zip.putNextEntry(new ZipEntry("manifest.json"));
      zip.write(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
      zip.closeEntry();
    } catch (IOException e) {
      throw new IllegalStateException("내보내기 압축 파일을 만들 수 없습니다.", e);
    }
    return bytes.toByteArray();
  }

  private List<Attachment> retainedDocumentAttachments(long officeId) {
    List<Attachment> result = new ArrayList<>();
    jdbcClient
        .sql(
            "SELECT t.id, t.object_key FROM contract_document t JOIN contract c ON c.id ="
                + " t.contract_id WHERE c.office_id = :officeId"
                + RETAINED_DOCUMENT_CONDITION
                + " ORDER BY t.id")
        .param("officeId", officeId)
        .query(
            rs -> {
              result.add(new Attachment("contract_document", rs.getLong(1), rs.getString(2)));
            });
    return result;
  }

  private byte[] build(Row job) {
    if (job.scope().equals("ARCHIVE")) {
      return buildArchive(job);
    }
    boolean full = job.scope().equals("FULL");
    List<ExportTables> tables =
        full
            ? List.of(ExportTables.values())
            : job.tableCodes().stream().map(ExportTables::valueOf).toList();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Map<String, Long> rowCounts = new LinkedHashMap<>();
    List<String> usedTables = new ArrayList<>();
    try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
      for (ExportTables table : tables) {
        for (ExportTables.Source source : table.sources()) {
          zip.putNextEntry(new ZipEntry("data/" + source.fileName()));
          rowCounts.put(
              "data/" + source.fileName(), writeCsvQuery(zip, source.sql(), job.officeId()));
          zip.closeEntry();
          usedTables.add(source.fileName().replace(".csv", ""));
        }
      }
      if (full) {
        writeFullExtras(zip, job, tables, usedTables, rowCounts);
      }
    } catch (IOException e) {
      throw new IllegalStateException("내보내기 압축 파일을 만들 수 없습니다.", e);
    }
    return bytes.toByteArray();
  }

  private void writeFullExtras(
      ZipOutputStream zip,
      Row job,
      List<ExportTables> tables,
      List<String> usedTables,
      Map<String, Long> rowCounts)
      throws IOException {
    String inList = "'" + String.join("','", usedTables) + "'";
    zip.putNextEntry(new ZipEntry("field_dictionary.csv"));
    rowCounts.put(
        "field_dictionary.csv",
        writeCsvQuery(
            zip,
            "SELECT c.relname AS table_name, a.attname AS column_name,"
                + " format_type(a.atttypid, a.atttypmod) AS data_type,"
                + " col_description(a.attrelid, a.attnum) AS description"
                + " FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid"
                + " WHERE c.relnamespace = current_schema()::regnamespace AND a.attnum > 0"
                + " AND NOT a.attisdropped AND c.relname IN ("
                + inList
                + ") ORDER BY c.relname, a.attnum",
            null));
    zip.closeEntry();
    zip.putNextEntry(new ZipEntry("relations.csv"));
    rowCounts.put(
        "relations.csv",
        writeCsvQuery(
            zip,
            "SELECT cl.relname AS child_table, att.attname AS child_column,"
                + " rcl.relname AS parent_table, ratt.attname AS parent_column"
                + " FROM pg_constraint con JOIN pg_class cl ON cl.oid = con.conrelid"
                + " JOIN pg_class rcl ON rcl.oid = con.confrelid"
                + " CROSS JOIN LATERAL unnest(con.conkey, con.confkey) AS k(a, ra)"
                + " JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = k.a"
                + " JOIN pg_attribute ratt ON ratt.attrelid = con.confrelid AND ratt.attnum = k.ra"
                + " WHERE con.contype = 'f' AND cl.relnamespace = current_schema()::regnamespace"
                + " AND cl.relname IN ("
                + inList
                + ") ORDER BY cl.relname, att.attname",
            null));
    zip.closeEntry();
    ArrayNode attachmentsNode = MAPPER.createArrayNode();
    long included = 0;
    long missing = 0;
    for (Attachment attachment : attachments(job.officeId())) {
      Optional<byte[]> content = objectStorage.find(attachment.objectKey());
      ObjectNode node = attachmentsNode.addObject();
      String path = attachmentPath(attachment);
      node.put("path", path);
      if (content.isPresent()) {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.get());
        zip.closeEntry();
        node.put("included", true);
        included++;
      } else {
        node.put("included", false);
        missing++;
      }
    }
    ObjectNode manifest = MAPPER.createObjectNode();
    manifest.put("exportId", job.id());
    manifest.put("scope", job.scope());
    manifest.put("generatedAt", clock.instant().toString());
    manifest.put("encoding", "UTF-8 (BOM)");
    ArrayNode tableNodes = manifest.putArray("tables");
    tables.forEach(table -> tableNodes.add(table.name()));
    ObjectNode files = manifest.putObject("rowCounts");
    rowCounts.forEach(files::put);
    manifest.putObject("attachmentSummary").put("included", included).put("missing", missing);
    manifest.set("attachments", attachmentsNode);
    zip.putNextEntry(new ZipEntry("manifest.json"));
    zip.write(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
    zip.closeEntry();
  }

  private List<Attachment> attachments(long officeId) {
    List<Attachment> result = new ArrayList<>();
    jdbcClient
        .sql(
            "SELECT t.id, t.object_key FROM property_photo t JOIN property p ON p.id ="
                + " t.property_id WHERE p.office_id = :officeId ORDER BY t.id")
        .param("officeId", officeId)
        .query(
            rs -> {
              result.add(new Attachment("property_photo", rs.getLong(1), rs.getString(2)));
            });
    jdbcClient
        .sql(
            "SELECT t.id, t.object_key FROM contract_document t JOIN contract c ON c.id ="
                + " t.contract_id WHERE c.office_id = :officeId ORDER BY t.id")
        .param("officeId", officeId)
        .query(
            rs -> {
              result.add(new Attachment("contract_document", rs.getLong(1), rs.getString(2)));
            });
    return result;
  }

  private static String attachmentPath(Attachment attachment) {
    String key = attachment.objectKey();
    int dot = key.lastIndexOf('.');
    String extension =
        dot >= 0 && key.length() - dot <= 6 ? key.substring(dot).toLowerCase(Locale.ROOT) : "";
    return "attachments/" + attachment.directory() + "/" + attachment.id() + extension;
  }

  private long writeCsvQuery(ZipOutputStream zip, String sql, Long officeId) throws IOException {
    OutputStreamWriter writer = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
    zip.write(BOM);
    CSVFormat format = CSVFormat.DEFAULT.builder().setRecordSeparator("\r\n").get();
    CSVPrinter printer = new CSVPrinter(writer, format);
    var statement = jdbcClient.sql(sql);
    if (officeId != null) {
      statement = statement.param("officeId", officeId);
    }
    try {
      Long count =
          statement.query(
              (ResultSetExtractor<Long>)
                  rs -> {
                    ResultSetMetaData meta = rs.getMetaData();
                    int columns = meta.getColumnCount();
                    List<String> header = new ArrayList<>();
                    for (int i = 1; i <= columns; i++) {
                      header.add(meta.getColumnLabel(i));
                    }
                    long rows = 0;
                    try {
                      printer.printRecord(header);
                      while (rs.next()) {
                        List<Object> values = new ArrayList<>(columns);
                        for (int i = 1; i <= columns; i++) {
                          values.add(cell(rs.getObject(i)));
                        }
                        printer.printRecord(values);
                        rows++;
                      }
                    } catch (IOException e) {
                      throw new IllegalStateException(e);
                    }
                    return rows;
                  });
      printer.flush();
      return count == null ? 0 : count;
    } catch (IllegalStateException e) {
      if (e.getCause() instanceof IOException io) {
        throw io;
      }
      throw e;
    }
  }

  private static Object cell(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number || value instanceof Boolean) {
      return value;
    }
    String text = value.toString();
    if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
      return "'" + text;
    }
    return text;
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
