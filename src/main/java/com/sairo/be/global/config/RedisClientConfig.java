package com.sairo.be.global.config;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Redis 연결이 끊긴 동안 명령을 큐에 쌓아 명령 제한 시간(60초 기본값) 동안 요청 스레드를 붙잡지 않도록, 끊긴 상태에서는 명령을 즉시 거절한다. 자동 재연결은 그대로
 * 켜져 있어 Redis가 돌아오면 앱 재시작 없이 회복한다.
 */
@Profile("!migrate")
@Configuration(proxyBeanMethods = false)
public class RedisClientConfig {

  @Bean
  LettuceClientOptionsBuilderCustomizer redisFailFastWhileDisconnected() {
    return builder ->
        builder
            .autoReconnect(true)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
  }
}
