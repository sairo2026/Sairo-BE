package com.sairo.be;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Disabled("완전출시 스키마 V12 적용으로 MVP 도메인 코드와 DB 구조가 맞지 않는 상태이며 완전출시 백엔드 구현 시 재작성한다")
class SairoBeApplicationTests {

  @Test
  void contextLoads() {}
}
