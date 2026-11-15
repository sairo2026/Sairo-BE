package com.sairo.be.perf;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

public final class SqlRecorder {

  public record ParamCall(Method method, Object[] args) {}

  public static final class Stat {
    public final String sql;
    public long count;
    public long totalNanos;
    public long maxNanos;
    public List<ParamCall> slowestParams = List.of();

    Stat(String sql) {
      this.sql = sql;
    }

    public double totalMillis() {
      return totalNanos / 1_000_000.0;
    }

    public double maxMillis() {
      return maxNanos / 1_000_000.0;
    }
  }

  public static final class Scenario {
    public final String name;
    final Map<String, Stat> stats = new ConcurrentHashMap<>();

    Scenario(String name) {
      this.name = name;
    }

    public long statementCount() {
      return stats.values().stream().mapToLong(s -> s.count).sum();
    }

    public List<Stat> byTotalTime() {
      List<Stat> list = new ArrayList<>(stats.values());
      list.sort(Comparator.comparingLong((Stat s) -> s.totalNanos).reversed());
      return list;
    }
  }

  private static volatile Scenario current;

  private SqlRecorder() {}

  public static Scenario begin(String name) {
    Scenario scenario = new Scenario(name);
    current = scenario;
    return scenario;
  }

  public static void end() {
    current = null;
  }

  static void record(String sql, List<ParamCall> params, long nanos) {
    Scenario scenario = current;
    if (scenario == null || sql == null) {
      return;
    }
    Stat stat = scenario.stats.computeIfAbsent(sql, Stat::new);
    synchronized (stat) {
      stat.count++;
      stat.totalNanos += nanos;
      if (nanos > stat.maxNanos) {
        stat.maxNanos = nanos;
        stat.slowestParams = List.copyOf(params);
      }
    }
  }

  public static boolean explainable(String sql) {
    String text = sql.stripLeading().toLowerCase();
    if (!(text.startsWith("select") || text.startsWith("with"))) {
      return false;
    }
    return !text.contains("for update")
        && !text.contains(" insert ")
        && !text.contains(" update ")
        && !text.contains(" delete ")
        && !text.contains("nextval")
        && !text.contains("pg_advisory");
  }

  public static List<String> explain(DataSource dataSource, Stat stat) {
    List<String> lines = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement ps =
            connection.prepareStatement("EXPLAIN (ANALYZE, BUFFERS) " + stat.sql)) {
      for (ParamCall call : stat.slowestParams) {
        call.method().invoke(ps, call.args());
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          lines.add(rs.getString(1));
        }
      }
    } catch (Exception e) {
      lines.add("EXPLAIN 실패: " + e.getClass().getSimpleName() + " " + e.getMessage());
    }
    return lines;
  }
}
