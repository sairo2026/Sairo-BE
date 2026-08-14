# GitHub 협업 규칙

이 문서는 사이로 백엔드 저장소의 브랜치 전략, Issue, 커밋과 Pull Request 작성 기준을 설명한다.

## 브랜치 전략

저장소는 `main`과 `dev` 두 개의 상시 브랜치만 유지한다.

- `dev`: 개발 중인 모든 기능이 모이는 통합 브랜치. 기본(default) 브랜치이며 기능 브랜치는 여기서 분기하고 Pull Request도 여기로 보낸다.
- `main`: 실제 운영에 배포되는 브랜치. `main`에 push되면 Deploy 워크플로가 자동으로 운영 서버에 배포한다. 그래서 `main`은 전체 기능 개발과 검수(QA)가 끝났을 때만, `dev`를 병합해서 갱신한다. 기능 브랜치를 `main`에 직접 병합하지 않는다.
- 기능 브랜치는 병합 후 남기지 않는다. 저장소의 "Automatically delete head branches" 설정이 켜져 있어 Pull Request가 병합되면 브랜치가 자동으로 삭제된다. 그 결과 저장소에는 평소 `main`, `dev` 두 브랜치만 남는다.

## 작업 순서

1. 실제 작업 범위만 담은 Issue를 만든다.
2. Assignee를 `KimTaeHwan21`, 작업 종류에 맞는 Label을 지정한다.
3. Issue의 Development에 `dev`에서 분기한 작업 브랜치를 연결한다.
4. 브랜치에서 변경하고 검증한 뒤 커밋한다.
5. `dev`를 대상으로 Pull Request를 만들고 Assignee·Label·관련 Issue를 확인한다.
6. 필수 CI 통과 후 `dev`로 squash merge한다. 브랜치는 자동 삭제된다.
7. 전체 기능 개발과 검수가 끝나면 `dev`→`main` Pull Request를 만들어 병합한다. 이 병합은 운영 배포를 직접 실행하므로 병합 전에 반드시 별도로 승인받는다.

## 제목과 브랜치

| 대상 | 형식 | 예시 |
|---|---|---|
| Issue | `[SAIRO-BE] <type>: <한글 요약>` | `[SAIRO-BE] feat: 마이페이지 개발` |
| 브랜치 | `<type>/<영문-kebab-case>` (`dev`에서 분기) | `feat/my-page` |
| 커밋 | `<type>: <한글 요약>` | `feat: 마이페이지 조회 기능 추가` |
| Pull Request | `<type>: <한글 요약>` (base는 보통 `dev`) | `feat: 마이페이지 개발` |

## type과 Label

| type | Label |
|---|---|
| `feat` | `✨ Feature` |
| `fix` | `🐞 Fix` |
| `refactor` | `🔨 Refactor` |
| `infra` | `🏗️ infra` |
| `deploy` | `🌏 Deploy` |
| `chore` | `🧹chore` |
| `docs` | `📃 Docs` |
| `setting` | `⚙️ Setting` |

## 작성 원칙

- Issue와 Pull Request는 템플릿의 섹션만 사용하고 짧은 문장과 목록으로 작성한다.
- 문서 자체만 읽어도 목적과 변경 내용을 이해할 수 있게 쓴다.
- 개인 작업 경로, 대화 과정, 작성 도구 이름처럼 다른 사람이 알 수 없는 표현은 넣지 않는다.
- Issue에는 현재 작업만 적고 별개의 미래 작업은 시작할 때 새 Issue로 만든다.
- Pull Request에는 실제 변경과 핵심 검증만 적고 완료할 Issue는 `closed #번호`로 연결한다.
- Assignee·Label·Development 브랜치를 비워 두지 않는다.
- 커밋에 `Co-Authored-By` 등 자동화 도구의 공동 작성자 trailer를 넣지 않는다.

## 간단한 예시

Issue의 작업 설명:

> 회원이 자신의 계정과 소속 사무소 정보를 조회할 수 있도록 마이페이지 기능을 개발합니다.

Issue의 작업 상세 내용:

- [ ] 계정 정보 조회 구현
- [ ] 소속 사무소 조회 구현
- [ ] 테스트와 CI 확인

Pull Request의 작업 내용:

- 계정 정보 조회 기능 추가
- 소속 사무소 조회 기능 추가
- 관련 테스트 추가
