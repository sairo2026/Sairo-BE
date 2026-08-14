# GitHub 협업 규칙

이 문서는 사이로 백엔드 저장소의 Issue, 브랜치, 커밋과 Pull Request 작성 기준을 설명한다.

## 작업 순서

1. 실제 작업 범위만 담은 Issue를 만든다.
2. Assignee를 `KimTaeHwan21`, 작업 종류에 맞는 Label을 지정한다.
3. Issue의 Development에 작업 브랜치를 연결한다.
4. 브랜치에서 변경하고 검증한 뒤 커밋한다.
5. Pull Request를 만들고 Assignee·Label·관련 Issue를 확인한다.
6. 필수 CI 통과 후 squash merge한다. `main`에 직접 push하지 않는다.

## 제목과 브랜치

| 대상 | 형식 | 예시 |
|---|---|---|
| Issue | `[SAIRO-BE] <type>: <한글 요약>` | `[SAIRO-BE] feat: 마이페이지 개발` |
| 브랜치 | `<type>/<영문-kebab-case>` | `feat/my-page` |
| 커밋 | `<type>: <한글 요약>` | `feat: 마이페이지 조회 기능 추가` |
| Pull Request | `<type>: <한글 요약>` | `feat: 마이페이지 개발` |

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
