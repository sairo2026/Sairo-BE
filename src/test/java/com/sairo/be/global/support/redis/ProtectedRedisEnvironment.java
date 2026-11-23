package com.sairo.be.global.support.redis;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 운영과 같은 옵션(requirepass, AOF, 키스페이스 알림)의 Redis와 PostgreSQL을 테스트 JVM당 한 번만 띄운다. */
public final class ProtectedRedisEnvironment {

  public static final String PASSWORD = "synthetic-redis-secret-7f3a91c0d2e84b65";

  private static final int REDIS_PORT = freePort();

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4.12-alpine"))
          .withCommand(
              "redis-server",
              "--requirepass",
              PASSWORD,
              "--appendonly",
              "yes",
              "--notify-keyspace-events",
              "Egx",
              "--maxmemory",
              "128mb",
              "--maxmemory-policy",
              "noeviction")
          .withExposedPorts(6379)
          .withCreateContainerCmdModifier(
              cmd ->
                  cmd.getHostConfig()
                      .withPortBindings(
                          new PortBinding(
                              Ports.Binding.bindPort(REDIS_PORT), new ExposedPort(6379))))
          .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

  static {
    POSTGRES.start();
    REDIS.start();
  }

  private ProtectedRedisEnvironment() {}

  public static void register(DynamicPropertyRegistry registry, String redisPassword) {
    properties(redisPassword).forEach((key, value) -> registry.add(key, () -> value));
  }

  public static Map<String, String> properties(String redisPassword) {
    return Map.of(
        "spring.datasource.url", POSTGRES.getJdbcUrl(),
        "spring.datasource.username", POSTGRES.getUsername(),
        "spring.datasource.password", POSTGRES.getPassword(),
        "spring.data.redis.host", REDIS.getHost(),
        "spring.data.redis.port", String.valueOf(REDIS_PORT),
        "spring.data.redis.password", redisPassword,
        "spring.session.data.redis.configure-action", "none");
  }

  /** 지정한 비밀번호로 redis-cli를 실행한다. 서버 비밀번호를 바꾼 뒤 되돌릴 때 쓴다. */
  public static String cliAs(String password, String... args) {
    try {
      String[] command = new String[args.length + 4];
      command[0] = "redis-cli";
      command[1] = "-a";
      command[2] = password;
      command[3] = "--no-auth-warning";
      System.arraycopy(args, 0, command, 4, args.length);
      return REDIS.execInContainer(command).getStdout().trim();
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  public static void stopRedis() {
    DockerClientFactory.instance().client().stopContainerCmd(REDIS.getContainerId()).exec();
  }

  public static void killRedis() {
    DockerClientFactory.instance().client().killContainerCmd(REDIS.getContainerId()).exec();
  }

  public static void startRedisIfStopped() {
    if (!REDIS.isRunning()) {
      startRedis();
    }
  }

  public static void startRedis() {
    DockerClientFactory.instance().client().startContainerCmd(REDIS.getContainerId()).exec();
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      try {
        if (REDIS.isRunning()
            && REDIS
                .execInContainer("redis-cli", "-a", PASSWORD, "--no-auth-warning", "ping")
                .getStdout()
                .contains("PONG")) {
          return;
        }
      } catch (IOException | InterruptedException | RuntimeException e) {
        // 아직 기동 중이다.
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    throw new IllegalStateException("Redis가 다시 기동하지 않았습니다.");
  }

  public static String cli(String... args) {
    try {
      String[] command = new String[args.length + 4];
      command[0] = "redis-cli";
      command[1] = "-a";
      command[2] = PASSWORD;
      command[3] = "--no-auth-warning";
      System.arraycopy(args, 0, command, 4, args.length);
      return REDIS.execInContainer(command).getStdout().trim();
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
