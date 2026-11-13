package com.sairo.be.global.security.access;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 브라우저가 이동해 오는 콜백 핸들러에서 세션이 없을 때 JSON 대신 프론트엔드 경로로 `error=UNAUTHENTICATED`를 붙여 302로 보낸다. 다른 접근 판정은
 * 그대로 따른다.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RedirectOnUnauthenticated {

  String value();
}
