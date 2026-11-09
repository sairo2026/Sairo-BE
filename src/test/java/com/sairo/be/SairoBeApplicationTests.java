package com.sairo.be;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SairoBeApplicationTests {

  @Autowired private DataSource dataSource;

  @Test
  void 마이그레이션을_적용한_스키마에서_validate로_컨텍스트가_기동된다() {
    Integer applied =
        new JdbcTemplate(dataSource)
            .queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
    assertThat(applied).isEqualTo(13);
  }
}
