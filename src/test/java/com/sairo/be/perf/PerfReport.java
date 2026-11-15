package com.sairo.be.perf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PerfReport {

  private static final Path DIRECTORY =
      Path.of(
          System.getenv("PERF_RESULT_DIR") == null || System.getenv("PERF_RESULT_DIR").isBlank()
              ? "build/perf-results"
              : System.getenv("PERF_RESULT_DIR"));
  private static final StringBuilder SUMMARY = new StringBuilder();
  private static final StringBuilder CSV =
      new StringBuilder("scenario,samples,p50_ms,p95_ms,max_ms,sql_per_request,errors\n");
  private static final Pattern SEQ_SCAN = Pattern.compile("Seq Scan on (\\w+)");
  private static final Pattern EXECUTION = Pattern.compile("Execution Time: ([0-9.]+) ms");

  private PerfReport() {}

  static synchronized void section(String title) {
    SUMMARY.append("\n## ").append(title).append("\n\n");
    flush();
  }

  static synchronized void line(String text) {
    SUMMARY.append(text).append('\n');
    flush();
  }

  static synchronized void csv(String row) {
    CSV.append(row).append('\n');
    write("api-latency.csv", CSV.toString());
  }

  static synchronized void plan(String file, String content) {
    write("plans/" + file, content);
  }

  static String executionMillis(java.util.List<String> plan) {
    for (String text : plan) {
      Matcher matcher = EXECUTION.matcher(text);
      if (matcher.find()) {
        return matcher.group(1);
      }
    }
    return "?";
  }

  static String seqScans(java.util.List<String> plan) {
    java.util.Set<String> tables = new java.util.LinkedHashSet<>();
    for (String text : plan) {
      Matcher matcher = SEQ_SCAN.matcher(text);
      if (matcher.find()) {
        tables.add(matcher.group(1));
      }
    }
    return tables.isEmpty() ? "-" : String.join(", ", tables);
  }

  private static void flush() {
    write("summary.md", "# 대용량 성능 측정 결과\n" + SUMMARY);
  }

  private static void write(String name, String content) {
    try {
      Path target = DIRECTORY.resolve(name);
      Files.createDirectories(target.getParent());
      Files.writeString(target, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
