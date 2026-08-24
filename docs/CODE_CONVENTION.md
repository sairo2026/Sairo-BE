# SAIRO 백엔드 코드 컨벤션

이 문서는 SAIRO 백엔드의 필수 구현·리뷰 기준이다. 개인 취향보다 일관성, 변경 안전성, 테스트 가능성을 우선한다. 포맷은 Spotless, 구조 규칙은 ArchUnit, 동작은 테스트와 리뷰로 검증한다.

## 1. 기본 원칙

- Java 21과 Spring Boot의 표준 기능을 우선하고 불필요한 자체 프레임워크를 만들지 않는다.
- 기능은 도메인 단위로 응집하고 계층 간 의존 방향을 한 방향으로 유지한다.
- Controller는 HTTP 변환, Service는 유스케이스 조정, Entity는 상태와 불변식, Mapper는 객체 변환만 담당한다.
- 필드 주입, 전역 가변 상태, 무분별한 setter, 범용 `RuntimeException`, 의미 없는 주석을 금지한다.
- 중복 제거보다 명확한 책임과 낮은 결합도를 우선한다. 두 번 등장했다는 이유만으로 성급하게 추상화하지 않는다.

## 2. 패키지 구조와 의존 방향

최상위는 기술 계층이 아니라 기능 도메인으로 나눈다.

```text
com.sairo.be
├─ auth
│  ├─ controller
│  ├─ service
│  ├─ domain
│  ├─ repository
│  ├─ dto
│  └─ mapper
├─ office
├─ property
├─ contract
├─ coordination
├─ task
└─ global
   ├─ config
   ├─ error
   └─ support
```

의존 방향은 `controller → service → domain/repository`다. Controller가 Repository·Entity를 직접 참조하거나 Service가 Controller DTO에 의존하면 안 된다. 도메인 간 호출은 상대 도메인의 내부 구현이 아니라 공개 Service 또는 명시적인 포트를 통한다. 순환 의존은 허용하지 않는다.

## 3. 클래스별 책임

### Controller

- 요청 파싱·Bean Validation·인증 주체 전달·응답 상태 결정만 담당한다.
- 비즈니스 분기, 트랜잭션, Entity 수정, Repository 호출, 복잡한 매핑을 넣지 않는다.
- API 요청·응답에는 Entity를 직접 노출하지 않고 전용 DTO를 사용한다.
- 생성은 `201 Created`, 본문 없는 성공은 `204 No Content`처럼 API 명세의 상태 코드를 명시한다.

### Service

- 하나의 공개 메서드는 하나의 유스케이스를 표현한다.
- 트랜잭션 경계, 권한·상태 검증, 도메인 호출, 저장 순서만 조정한다.
- 긴 객체 조립이나 DTO 변환은 `mapper`로 옮긴다. Mapper는 Repository나 외부 API를 호출하지 않는 순수 변환기여야 한다.
- 조회 전용 유스케이스는 `@Transactional(readOnly = true)`, 쓰기는 `@Transactional`을 명시한다.
- 서로 무관한 private 메서드가 늘어나거나 공개 메서드가 여러 책임을 가지면 유스케이스를 분리한다.

### Mapper

- `XxxMapper`로 이름 짓고 `domain ↔ dto` 또는 조회 결과 조립을 담당한다.
- 단순 변환은 정적 메서드 또는 상태 없는 클래스로 구현한다. 협력 객체가 필요하면 `@Component`와 생성자 주입을 사용한다.
- 조회·저장·권한 검사·시간 조회 같은 부수효과를 넣지 않는다.
- 자동 매핑이 실제 중복을 줄일 때만 MapStruct 도입을 검토한다. 중요한 상태 전이는 자동 매핑에 숨기지 않는다.

### Repository

- 영속성 조회·저장만 담당하며 비즈니스 정책을 구현하지 않는다.
- 메서드 이름은 결과와 조건을 드러낸다. 복잡한 조회는 Query Repository로 분리한다.
- `Optional`은 단건 조회 부재를 나타낼 때만 사용하며 Entity 필드·DTO 필드·메서드 인자로 사용하지 않는다.

## 4. 의존성 주입과 Lombok

- Spring Bean의 의존성은 `private final` 필드와 단일 생성자로 주입한다.
- 보일러플레이트를 줄이는 경우 `@RequiredArgsConstructor`를 사용한다. 선택 의존성이나 생성 검증이 필요하면 명시적 생성자를 사용한다.
- `@Autowired` 필드 주입과 일반 클래스의 `@Data`를 금지한다.
- Lombok은 의도를 숨기지 않는 범위에서만 사용한다. `@Getter`, `@RequiredArgsConstructor`, 제한적인 `@Builder`는 허용한다.
- JPA Entity에 클래스 수준 `@Setter`, `@Data`, `@Value`, 무분별한 `@Builder`를 사용하지 않는다.

## 5. Entity와 상태 변경

- 필드는 `private`로 두고 외부에서 직접 수정하지 못하게 한다.
- JPA용 기본 생성자는 `@NoArgsConstructor(access = AccessLevel.PROTECTED)`로 제한한다.
- 신규 Entity는 의미 있는 정적 팩터리 또는 검증하는 생성자로 만들고, 복잡한 선택 인자가 많을 때만 제한된 Builder를 사용한다.
- 변경은 `approve`, `reject`, `cancel`, `changeName`처럼 업무 의미를 가진 메서드로 수행한다. `setStatus` 같은 범용 setter는 금지한다.
- Entity 메서드는 자신의 불변식과 상태 전이를 지키며 Service가 필드를 조합해 상태를 만들지 않게 한다.
- 연관관계 편의 메서드는 양쪽 일관성을 책임지고 컬렉션은 외부에서 교체하지 못하게 한다.
- `equals`/`hashCode`는 지연 로딩과 식별자 생명주기를 고려해 명시적으로 설계한다.

## 6. DTO, enum과 값 객체

- 불변 요청·응답 DTO는 우선 `record`를 사용한다.
- 요청 DTO는 Bean Validation을 경계에서 수행하고, 도메인 규칙은 Entity·Service에서 다시 보장한다.
- 문자열 상수 집합과 상태는 `enum`으로 표현한다. DB·API 값과 enum 이름이 다르면 명시적 변환기를 둔다.
- boolean은 `isActive`, `hasOffice`, `canApprove`처럼 질문 형태로 이름 짓는다.
- 금액·기간·전화번호처럼 규칙을 가진 값은 원시 타입 남용 대신 값 객체 도입을 검토한다.
- API 필드의 nullable과 생략 가능 여부를 구분하며 `null`, 빈 문자열, 빈 배열을 임의로 혼용하지 않는다.

## 7. 예외와 오류 응답

- 모든 업무 예외를 404로 반환하지 않는다. HTTP 의미에 따라 400·401·403·404·409·422·429·500 등을 구분한다.
- `ErrorCode` enum이 안정적인 오류 코드, HTTP 상태, 외부 공개 메시지를 소유한다.
- `BusinessException`은 `ErrorCode`와 필요한 안전한 context만 가진다. 예외 메시지로 분기하지 않는다.
- `@RestControllerAdvice` 한 곳에서 `BusinessException`, 검증 실패, Spring 표준 예외, 예상하지 못한 예외를 매핑한다.
- 오류 본문은 RFC 9457 `ProblemDetail`을 기반으로 `code`, `message`, `traceId`, `fieldErrors`를 일관되게 제공한다.
- 존재하지 않는 자원은 404, 현재 상태와 충돌은 409, 형식·필드 검증 실패는 400, 인증 없음은 401, 권한 없음은 403이다.
- 예상하지 못한 예외는 내부 상세·SQL·스택을 응답에 노출하지 않고 서버 로그에 traceId와 함께 남긴다.

권장 형태:

```java
public enum ErrorCode {
  OFFICE_NOT_FOUND(HttpStatus.NOT_FOUND, "OFFICE-404-001", "사무소를 찾을 수 없습니다."),
  MEMBERSHIP_ALREADY_PENDING(HttpStatus.CONFLICT, "OFFICE-409-001", "처리 중인 참여 요청이 있습니다.");
}
```

## 8. 메서드와 명명

- 클래스는 명사, 메서드는 동사, 조건 메서드는 `is`·`has`·`can`, 조회는 `find`·`get`의 의미를 구분한다.
- `find`는 부재 가능성을 `Optional`로 표현하고 `get`은 존재가 전제되며 없으면 명시적 업무 예외를 던진다.
- 축약어보다 도메인 용어를 쓰고 `data`, `info`, `util`, `manager`, `process` 같은 모호한 이름을 피한다.
- 매개변수가 많아지면 요청 객체로 묶는다. boolean 플래그로 서로 다른 동작을 한 메서드에 합치지 않는다.
- 메서드는 한 추상화 수준을 유지하고, 중첩 분기보다 guard clause를 사용한다.

## 9. 주석과 문서화

- 코드가 무엇을 하는지 반복하는 주석은 삭제한다. 이름과 구조로 표현할 수 없는 이유·제약·트레이드오프만 기록한다.
- `TODO`에는 추적 가능한 Issue 번호를 붙인다. 주석 처리한 코드와 작성자 서명은 남기지 않는다.
- 공개 API나 오해하기 쉬운 도메인 규칙은 Javadoc으로 계약을 설명한다.
- 개인정보·토큰·비밀번호·내부 예외 상세를 로그에 남기지 않는다.

## 10. 테스트

- 정상 경로뿐 아니라 권한, 상태 전이, 경계값, 중복 요청, 동시성, 롤백을 검증한다.
- Service 테스트는 결과와 협력 관계의 핵심만 확인하고 private 구현 순서에 결합하지 않는다.
- Repository 쿼리는 실제 PostgreSQL과 다른 동작 가능성이 있으면 Testcontainers로 검증한다.
- Controller 테스트는 상태 코드, 오류 코드, validation, 인증·인가를 확인한다.
- 버그 수정에는 실패를 재현하는 회귀 테스트를 먼저 추가한다.
- 테스트 이름은 `조건_행위_결과`가 드러나게 작성한다.

## 11. 데이터베이스와 트랜잭션

- 적용된 Flyway 파일은 수정하지 않고 다음 버전의 전진 마이그레이션을 추가한다.
- 스키마 제약으로 지킬 수 있는 불변식은 애플리케이션 검증과 함께 DB에도 둔다.
- 외부 API·파일 저장소 호출을 긴 DB 트랜잭션 안에 두지 않는다. 필요하면 outbox와 재시도를 사용한다.
- N+1을 숨기기 위해 무조건 EAGER를 사용하지 않는다. 유스케이스별 fetch join·EntityGraph·projection을 선택한다.
- 페이지 없는 무제한 목록 조회를 만들지 않는다.

## 12. 자동 검증과 PR 체크리스트

로컬에서 다음 명령을 통과해야 한다.

```shell
./gradlew spotlessApply
./gradlew check
```

PR에서는 다음을 확인한다.

- Controller가 Repository·Entity를 직접 참조하지 않는가?
- Service에 DTO 조립·매핑 코드가 쌓이지 않았는가?
- Entity 변경이 의미 있는 메서드로 제한되는가?
- 오류 코드와 HTTP 상태가 실제 실패 의미에 맞는가?
- 트랜잭션·동시성·DB 제약과 테스트가 함께 설계됐는가?
- 불필요한 주석, 로그의 민감정보, 임시 코드가 없는가?

## 13. 기준 자료

- Google Java Style Guide: 포맷·명명·소스 구조
- Spring Framework `ProblemDetail`: 표준 오류 응답
- Spring Petclinic: 포맷·Checkstyle을 CI에서 강제하는 Spring 공식 예제
- ArchUnit: 패키지 의존·계층·순환 구조 자동 검증
- MapStruct: 반복 매핑이 충분할 때 검토할 컴파일 타임 매퍼

외부 기준을 그대로 복제하지 않고 SAIRO의 Java 21·Spring Boot·도메인 구조에 맞게 이 문서로 확정한다. 충돌 시 이 문서가 저장소의 기준이다.
