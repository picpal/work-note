# work-note 파일 첨부 · 이미지 미리보기 · 업로드 정책 설계

> 2026-06-14 brainstorming 산출물. 노트에 drag&drop으로 이미지·문서를 첨부하고,
> 마크다운/`<img>` 태그로 이미지를 인라인 미리보기하며, 관리자가 업로드 허용 확장자·용량을
> 통제하는 기능. 기존 권한 모델(노트 종속 리소스)·단일 렌더 경로·admin 스크린 패턴을 계승한다.

## 1. 목표

- 에디터에서 파일을 **drag&drop / paste / 📎 버튼**으로 첨부한다.
- 이미지는 마크다운 `![](url)` 과 직접 작성한 `<img src=".." width=100% />` 둘 다로 인라인 미리보기된다.
- 비이미지(문서 등)는 본문에 다운로드 칩으로 표시된다.
- 관리자 화면에서 **업로드 허용 확장자 + 파일당 최대 용량**을 런타임으로 통제한다.

## 2. 확정 결정

| # | 결정 | 근거 |
|---|---|---|
| ① 저장 방식 | **파일시스템**(DB는 경로/메타만, 실제 바이너리는 서버 디스크) | 다운로드·교체·누적 시 마이그레이션 용이. DB 비대 회피 |
| ② 이미지 src | **내부 첨부만**(`/api/attachments/` + 상대경로). 외부 http(s)·`data:` 차단 | 공유 노트의 외부 콜백(추적 픽셀로 열람자 IP·시각 노출) 원천 차단. 폐쇄망엔 외부 URL 무의미 |
| ③ 관리자 정책 | **허용 확장자 + 파일당 용량(MB)** | 용량 cap이 실수 거대 파일 드롭으로 인한 디스크 비대도 방어 |
| A 업로드 루트 | 기본 `${WORKNOTE_DB 디렉토리}/attachments`, `WORKNOTE_UPLOAD_DIR`로 오버라이드 | 백업 대상이 DB와 한 폴더로 모임 |
| B 기본 최대 용량 | 25 MB (`26214400` bytes) | 일반 문서·이미지 충분, DB/디스크 안전 |
| C SVG | 기본 allowlist 제외(관리자 추가 가능, 경고) | `<img>` 로드는 스크립트 비실행이나 URL 직접 탐색 시 origin 내 실행 위험 |

## 3. 데이터 모델 (마이그레이션 `V4__attachment.sql`)

```sql
CREATE TABLE attachment (
  id         TEXT PRIMARY KEY,                 -- att-<uuid> (불투명 id, URL·파일명에 노출되는 키)
  node_id    TEXT NOT NULL REFERENCES node(id),-- 소유 노트
  filename   TEXT NOT NULL,                    -- 원본 파일명 (표시·다운로드 헤더용)
  ext        TEXT NOT NULL,                    -- 소문자 확장자 (정책 검사 키)
  mime       TEXT NOT NULL,                    -- 저장 시 결정된 content-type
  size       INTEGER NOT NULL,                 -- bytes
  rel_path   TEXT NOT NULL,                    -- 업로드 루트 기준 상대경로 (ab/cd/att-xxx 샤딩)
  created_by TEXT,                             -- emp (server 모드)
  created_at TEXT NOT NULL
  -- soft-delete 없음: 삭제·노트 purge 시 메타+파일을 hard delete (고아 방지)
);
CREATE INDEX idx_attachment_node ON attachment(node_id);

CREATE TABLE app_setting (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
-- Flyway seed (V4 내 INSERT):
--   upload.allowed_ext = 'png,jpg,jpeg,gif,webp,pdf,docx,xlsx,pptx,txt,md,csv,zip'
--   upload.max_bytes   = '26214400'
```

- **저장 경로는 클라이언트 파일명을 절대 사용하지 않는다.** `rel_path`는 `id` 기반으로 생성하여 path traversal을 차단한다. 원본 파일명은 메타로만 보관하고 다운로드 `Content-Disposition` 헤더에서만 사용(헤더 인젝션 방지 위해 sanitize).
- `app_setting`은 런타임 변경 가능한 첫 key/value 설정 저장소. 이후 다른 정책도 동일 테이블로 확장.
- `attachment.node_id`는 FK이나 SQLite는 기존 결정대로 FK enforcement off — 정합성은 서비스가 보장.

## 4. 백엔드 (Spring Boot)

### 4.1 엔드포인트

| 메서드/경로 | 가드 | 동작 |
|---|---|---|
| `POST /api/nodes/{id}/attachments` (multipart `file`) | 노트 **write** | 정책 검사(확장자 allowlist + 용량) → 디스크 저장 → 메타 insert → `201 {id, filename, size, url}` |
| `GET /api/attachments/{id}` | 노트 **read** | 파일 스트리밍. 이미지=`inline`, 그 외=`Content-Disposition: attachment; filename=...`. 공통 `X-Content-Type-Options: nosniff` |
| `DELETE /api/attachments/{id}` | 노트 **write** | DB 메타 삭제 + 디스크 파일 삭제 |
| `GET /api/share/{token}/attachments/{id}` | 토큰 유효 + 첨부가 토큰 노트 소속 | 공유뷰 이미지 서빙(deny 우회 read 예외 동일 적용). 감사 로그 |
| `GET /api/admin/settings/upload` | **admin** | `{allowedExt: string[], maxBytes: number}` |
| `PUT /api/admin/settings/upload` | **admin** | 허용 확장자·최대 용량 갱신. 감사 로그 |

- 정책 위반 응답: 확장자 거부 = `415 Unsupported Media Type`(또는 422 + 메시지), 용량 초과 = `413 Payload Too Large`. 일관된 메시지로 프런트 toast 노출.
- 무효 토큰/타 노트 첨부 접근 = 공유 정책대로 **404 단일화**(열거 방지).

### 4.2 신규 클래스

- `attachment/AttachmentController` · `AttachmentService` · `AttachmentRow` · `AttachmentMapper`(+XML)
- `setting/SettingService`(app_setting 읽기/쓰기, 확장자·용량 파싱) · `admin/AdminSettingController`
- `UploadPolicy`(확장자 정규화·검사, 용량 검사 — 순수 로직, 테스트 용이)
- 설정: `application.yml`에 `worknote.upload.dir: ${WORKNOTE_UPLOAD_DIR:}` (빈 값이면 DB 경로에서 파생), `spring.servlet.multipart.max-file-size/max-request-size`를 maxBytes 상한 이상으로.

### 4.3 권한/공유 연계

- 업로드·삭제 = 노트 write, 열람 = 노트 read를 `VaultGuard`로 강제(server 모드). local 모드는 무인증 통과.
- 공유 첨부 엔드포인트는 `ShareLinkService.resolve(token)`으로 노드 확정 후 `attachment.node_id == 해당 노드`만 허용. ShareController 패턴(가드 없이 토큰이 통제) 계승.

### 4.4 휴지통/purge 연계

- 노트 soft-delete(휴지통) 시 첨부 파일은 **보존**(복구 가능성).
- 30일 후 `TrashPurgeService`가 노트를 hard purge할 때 해당 노트의 첨부 **파일+메타도 함께 삭제**(고아 파일 방지). purge 경로에 첨부 정리 단계 추가.

## 5. 프런트엔드 (React + CodeMirror 6)

### 5.1 업로드 진입점

- **에디터 표면(`cm.ts` + `Editor.tsx`)**: CodeMirror DOM에 `drop`·`paste` 핸들러 추가.
  - 파일별로 업로드 → 커서 위치에 삽입: 이미지 `![filename](/api/attachments/{id})`, 그 외 `[📎 filename](/api/attachments/{id})`.
  - 업로드 중 `⏳ 업로드 중…` 텍스트 플레이스홀더 삽입 → 완료 시 실제 마크다운으로 치환, 실패 시 제거 + toast.
- **📎 이미지 툴바 버튼**: http 모드에서 파일 피커 오픈(기존 정적 `![](image.png)` 템플릿 대체), local 모드는 기존 템플릿 폴백.
- **local(무백엔드) 모드**: 업로드 비활성. drop/paste 시 "서버 모드에서만 첨부할 수 있습니다" toast.

### 5.2 렌더링 (`lib/markdown.ts` — 단일 경로, 에디터·공유뷰 공용)

- `<img>`는 DOMPurify 기본 정책이 이미 허용(width/height/alt 보존). 추가로 **src 제한 훅**:
  - 허용: `/api/attachments/...`, `/api/share/.../attachments/...`, 상대경로.
  - 차단: `http(s):`, `data:`, `//` protocol-relative, `javascript:` (결정 ②).
  - 차단 시 해당 `<img>`의 src 제거(깨진 이미지 placeholder).
- 비이미지 첨부 링크(`href`가 `/api/attachments/` 패턴)는 종이클립 chip 클래스 부여(후처리).

### 5.3 공유뷰 (`SharePage.tsx`)

- 동일 렌더 경로 사용하되, 렌더 결과의 `img[src^="/api/attachments/"]` 를 `/api/share/{token}/attachments/{id}`로 **치환**(공유 열람자는 노트 read 권한이 없으므로 토큰 스코프로 서빙).

### 5.4 API 레이어

- `storage/AttachmentApi.ts`: `upload(nodeId, file): Promise<{id, filename, url}>`, `remove(id)`, `url(id): string`.
- 정책 사전조회(선택): 최초 1회 `GET .../settings/upload`로 client-side pre-check(확장자/용량) — UX용, 서버 검사가 진실의 원천.

## 6. 관리자 화면 (10번째 스크린 `Uploads`)

- `admin/screens/Uploads.tsx`: 허용 확장자(chip add/remove) + 파일당 최대 용량(MB 입력) 편집·저장.
- `admin/api.ts`에 `getUploadPolicy`/`setUploadPolicy`, `AdminApp` 네비게이션에 항목 추가.
- `Security`(읽기전용)와 분리 — 업로드 정책은 편집 가능하므로 별도 스크린.
- SVG 추가 시 경고 문구 노출.

## 7. 보안 요약

- src 내부 한정(②) + 서버측 확장자·용량 강제(클라 검사는 UX용).
- 서빙: `X-Content-Type-Options: nosniff`, 이미지 inline, 그 외 `Content-Disposition: attachment`. SVG 기본 제외.
- 저장 경로 id 기반 생성 → path traversal 차단. 원본 파일명은 메타로만, 헤더 출력 시 sanitize.
- 공유 첨부 엔드포인트는 "첨부가 그 토큰 노트 소속인지" 검증 + 감사 로그. 무효 접근 404 단일화.
- 업로드/삭제 write, 열람 read 권한(server 모드).

## 8. 비범위 (YAGNI)

- 이미지 리사이즈/썸네일/EXIF 제거, 첨부 버전 관리, 드래그 리오더, 클립보드 외 외부 URL 임포트.
- 노트당/전체 저장 총량 쿼터(③에서 제외, 2단계 공용 서버 단계에서 재검토).
- 첨부 전용 휴지통(노트 purge에 종속).

## 9. 테스트 전략

- **백엔드(JUnit)**: `UploadPolicy` 확장자/용량 검사(허용·거부·대소문자·점 제거), 경로 생성(traversal 차단), Controller(업로드 201·확장자 415·용량 413·read 가드 403·삭제·공유 스코프 접근·타 노트 첨부 404), 설정 CRUD, purge 시 첨부 정리.
- **프런트(Vitest)**: `AttachmentApi`(multipart 호출·url 헬퍼), 마크다운 src 제한(외부/data 차단·내부 허용·width 보존), 비이미지 chip 렌더, 드롭 핸들러 분기(이미지/문서/실패).
- **e2e(브라우저)**: 별도 시나리오 문서 `docs/qa-e2e-첨부파일.md`.
