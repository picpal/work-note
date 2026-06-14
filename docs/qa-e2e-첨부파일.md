# work-note 첨부 파일 기능 e2e QA 시나리오

파일 첨부 · 이미지 미리보기 · 업로드 정책 기능의 화면 e2e 테스트 시나리오.
기존 `docs/qa-e2e-scenarios.md` 형식 계승. 각 Batch = 한 번의 `/qa` 호출 단위.

## 자동화 가능성 범례

| 표기 | 의미 |
|---|---|
| ✅ | 브라우저 자동화 가능 (폼·클릭·네비·콘텐츠) |
| ⚠️ | 부분 — drag&drop/paste·다운로드·네트워크 탭 등 보조 필요 |
| 🔧 | 브라우저 자동화 부적합 → API 호출/단위테스트로 커버 |

## 0. 실행 준비

```bash
cd frontend && pnpm build && cd ../backend && ./gradlew bootJar
rm -f /tmp/wn-att.db*; rm -rf /tmp/wn-att-uploads
WORKNOTE_MODE=server WORKNOTE_ADMIN_PASSWORD=qa-admin-1234 \
  WORKNOTE_DB=/tmp/wn-att.db WORKNOTE_UPLOAD_DIR=/tmp/wn-att-uploads \
  java -jar build/libs/worknote-0.1.0.jar &
# 헬스: curl --retry-connrefused --retry 40 --retry-delay 1 http://localhost:8080/api/health
```
- 로그인: 사번 `admin` / 비번 `qa-admin-1234`
- 업로드 디렉토리: `/tmp/wn-att-uploads` (DB는 `/tmp/wn-att.db`)
- **첨부는 http(server) 모드 전용.** 순수 localStorage 모드에는 백엔드가 없어 업로드 비활성(B12).

> 백엔드 통합(업로드→서빙 헤더→확장자/0바이트 거부→관리자 정책 라운드트립→공유 토큰 서빙→404 단일화→디스크 샤딩)은 구현 시 curl 스모크로 라이브 검증 완료. 아래는 **화면(UI) 관점** 재현 시나리오.

---

## Batch 1 — 이미지 drag&drop 업로드 + 인라인 미리보기 ⚠️

**사전조건:** admin 로그인, 노트 1개 열림. 로컬에 테스트 PNG 파일.
**단계:**
1. 노트 본문(에디터)에 PNG 파일을 drag&drop.
2. "업로드 중…" toast 노출 후 "첨부했습니다" 확인.
3. 본문에 `![파일명](/api/attachments/att-...)` 텍스트 삽입 확인.
4. 커서를 다른 줄로 이동(라이브프리뷰가 이미지 렌더).

**기대결과:** 본문에 이미지가 인라인으로 렌더. 네트워크 탭에 `GET /api/attachments/att-...` 200(image/png, `X-Content-Type-Options: nosniff`). `/tmp/wn-att-uploads/xx/yy/...`에 파일 생성.
**비고:** 합성 drop 이벤트(DataTransfer.files)는 브라우저 자동화가 까다로움 → ⚠️. API 업로드는 🔧로 대체 검증됨.

## Batch 2 — `<img>` 태그 수동 작성 미리보기 + width ✅

**사전조건:** B1로 업로드된 첨부 url 확보(`/api/attachments/att-X`).
**단계:**
1. 본문에 `<img src="/api/attachments/att-X" width="100%" />` 직접 입력.
2. 커서를 벗어나 라이브프리뷰 렌더.

**기대결과:** 이미지가 컨테이너 폭 100%로 렌더(`width="100%"` 보존). 공유뷰에서도 동일.

## Batch 3 — 비이미지(문서) 첨부 → 다운로드 칩 ⚠️

**사전조건:** admin 로그인, 노트 열림, 테스트 PDF.
**단계:**
1. PDF를 본문에 drop.
2. `[📎 파일명.pdf](/api/attachments/att-Y)` 삽입 확인.
3. 라이브프리뷰에서 링크가 `.attach-chip` 칩 스타일로 렌더.
4. 칩 클릭 → 다운로드.

**기대결과:** 칩 형태 표시. 다운로드 시 `Content-Disposition: attachment; filename*=UTF-8''파일명.pdf`, `nosniff`.

## Batch 4 — 외부/위험 src 차단 ✅

**사전조건:** 노트 열림.
**단계:** 본문에 아래를 각각 입력 후 프리뷰:
1. `<img src="https://example.com/p.gif">`
2. `![x](data:image/png;base64,AAAA)`
3. `<img src="//evil.example/x.png">`

**기대결과:** 셋 다 이미지 미표시(src 제거됨, 깨진 이미지 placeholder). 외부 네트워크 요청 0건(추적 픽셀 차단). 내부 `/api/attachments/`·상대경로만 렌더됨.

## Batch 5 — 클립보드 paste 업로드 ⚠️

**사전조건:** 캡처/복사된 이미지가 클립보드에.
**단계:** 에디터에 포커스 후 Cmd/Ctrl+V.
**기대결과:** B1과 동일하게 업로드 + `![]()` 삽입 + 미리보기.
**비고:** 합성 paste(ClipboardEvent.files) 자동화 까다로움 → ⚠️.

## Batch 6 — 확장자 미허용 거부 🔧/⚠️

**사전조건:** 정책 기본값(allowlist에 exe 없음).
**단계:** `.exe` 파일 drop.
**기대결과:** "허용하지 않는 파일 형식입니다: .exe" toast(422). 본문 미변경, 디스크 미생성.
**검증됨(🔧):** curl `POST .../attachments` `.exe` → 422 확인.

## Batch 7 — 용량 초과 / 0바이트 거부 🔧

**단계:** (a) 정책 최대 용량 초과 파일 drop. (b) 0바이트 파일 drop.
**기대결과:** (a) "파일이 너무 큽니다 (최대 NMB)" 422. (b) "빈 파일은 업로드할 수 없습니다" 422. 둘 다 본문 미변경.
**검증됨(🔧):** 0바이트 → 422 curl 확인.

## Batch 8 — 관리자 업로드 정책 화면 ✅

**사전조건:** admin 로그인 → `admin.html#uploads`.
**단계:**
1. 현재 허용 확장자 chip 목록·최대 용량(MB) 표시 확인.
2. 확장자 추가(입력 후 Enter)·기존 chip × 삭제.
3. 최대 용량 MB 변경.
4. 저장 → "저장했습니다" toast.
5. 새로고침 → 변경 유지 확인.

**기대결과:** GET/PUT `/api/admin/settings/upload` 반영. 재로드 시 유지. **SVG 추가 시 경고 문구 노출.**

## Batch 9 — 관리자 정책 ↔ 업로드 연동 ⚠️

**사전조건:** B8에서 특정 확장자(예: pdf) 제거 후 저장.
**단계:** 노트에 pdf drop.
**기대결과:** 즉시 거부 toast(422) — 관리자 변경이 업로드에 실시간 반영. 다시 pdf 허용으로 복원 후 업로드되면 정상.

## Batch 10 — 공유 링크 열람 시 첨부 이미지 ⚠️

**사전조건:** 이미지 포함 노트의 공유 링크 생성(`POST /api/nodes/{id}/share`).
**단계:** 공유 URL(`share.html?...토큰`)을 (별도/동일) 인증 세션으로 열람.
**기대결과:** 본문 이미지가 `/api/share/{token}/attachments/{id}`로 로드(네트워크 탭). 노트 read 권한 없는 열람자도 이미지 표시(deny 우회 read 예외 동일 적용). 토큰 무효·만료·취소 시 이미지 404.
**비고:** server 모드 공유 열람은 기존 정책상 인증 세션 필요(ALLOWLIST 미포함).

## Batch 11 — 휴지통 purge 시 첨부 정리 🔧

**사전조건:** 첨부 있는 노트를 휴지통으로 이동.
**단계:** purge(영구 삭제) 실행(또는 보존기한 경과 스케줄러).
**기대결과:** `attachment` 메타 + `/tmp/wn-att-uploads`의 파일 함께 삭제(고아 0). node DELETE 전 정리.
**검증됨(🔧):** `AttachmentService.deleteForNodes` 단위/통합 테스트.

## Batch 12 — local 모드 업로드 비활성 ⚠️

**사전조건:** 프런트 `VITE_STORAGE=local`(백엔드 없음) 또는 백엔드 미기동.
**단계:** 에디터에 파일 drop.
**기대결과:** "서버 모드에서만 첨부할 수 있습니다" toast. 본문 미변경. 📎 버튼은 기존 `![]()` 템플릿 삽입으로 폴백.

---

## 커버리지 요약

| 영역 | Batch | 자동화 |
|---|---|---|
| 업로드(drop/paste/📎) | B1·B3·B5 | ⚠️ (드롭 합성) |
| 미리보기·src 정책 | B2·B4 | ✅ |
| 거부(확장자/용량/0바이트) | B6·B7·B9 | 🔧/⚠️ |
| 관리자 정책 | B8·B9 | ✅ |
| 공유 서빙 | B10 | ⚠️ |
| purge 정리 | B11 | 🔧 |
| 모드 분기 | B12 | ⚠️ |

drag&drop·paste·다운로드·네트워크 탭이 핵심이라 순수 브라우저 자동화는 ⚠️가 많고, 거부·정리 로직은 🔧(API/단위)로 이미 커버. 화면 검증은 B2·B4·B8(✅)을 우선 자동화, 나머지는 수동/반자동 권장.
