# 보안 점검 보고서 — secscan

- 대상: `/Users/picpal/Desktop/workspace/work-note/backend`
- 스캐너: semgrep, trivy, gitleaks

## 요약
- 총 **39건** — 심각 3, 위험 16, 보통 13, 일반 7
- 도달성: 도달 가능 **0** · 도달 불가 **39** · 미상 **0**
- ℹ️ 도달 불가 39건은 우선순위가 낮습니다(노이즈 후보).
- 컴플라이언스: KISA 약점 매핑 **15건** · PCI-DSS 6.2.4 관련 **13건**

> ⚠️ **정적 분석 사각지대**: 도달성 판정은 리플렉션·DI(Spring proxy)·역직렬화·동적 디스패치·애노테이션 라우팅을 놓칠 수 있습니다. '도달 불가'는 *우선순위 강등* 근거일 뿐 안전 보증이 아닙니다. 억제는 사람이 증거를 확인해 확정하세요(자동 억제 없음).

## 우선 조치
_조치 대상 없음._

## 검토 후보 (낮은 신뢰)
_해당 없음._

## 낮은 우선순위 — 도달 불가 (SCA)
### [심각] CVE-2026-41293 — org.apache.tomcat.embed:tomcat-embed-core@10.1.41
- 도달성: **도달 불가** (dep-scan)
- 수정: `9.0.118, 10.1.55, 11.0.22` 이상으로 업그레이드
- CWE-20 · 탐지: trivy (합의 1)

### [심각] CVE-2026-43512 — org.apache.tomcat.embed:tomcat-embed-core@10.1.41
- 도달성: **도달 불가** (dep-scan)
- 수정: `9.0.118, 10.1.55, 11.0.22` 이상으로 업그레이드
- CWE-592 · 탐지: trivy (합의 1)

### [심각] CVE-2026-43515 — org.apache.tomcat.embed:tomcat-embed-core@10.1.41
- 도달성: **도달 불가** (dep-scan)
- 수정: `9.0.118, 10.1.55, 11.0.22` 이상으로 업그레이드
- CWE-285 · 탐지: trivy (합의 1)
- 컴플라이언스: KISA 부적절한 인가 · PCI-DSS 6.2.4 — access control

### [위험] CVE-2026-54512 — com.fasterxml.jackson.core:jackson-databind@2.19.0
- 도달성: **도달 불가** (dep-scan)
- 수정: `2.18.8, 3.1.4, 2.21.4` 이상으로 업그레이드
- CWE-184 CWE-502 · 탐지: trivy (합의 1)
- 컴플라이언스: KISA 신뢰할 수 없는 데이터의 역직렬화 · PCI-DSS 6.2.4 — injection

### [위험] CVE-2026-54513 — com.fasterxml.jackson.core:jackson-databind@2.19.0
- 도달성: **도달 불가** (dep-scan)
- 수정: `2.18.8, 2.21.4, 3.1.4` 이상으로 업그레이드
- CWE-184 · 탐지: trivy (합의 1)

### [위험] CVE-2025-48988 — org.apache.tomcat.embed:tomcat-embed-core@10.1.41
- 도달성: **도달 불가** (dep-scan)
- 수정: `11.0.8, 10.1.42, 9.0.106` 이상으로 업그레이드
- CWE-770 · 탐지: trivy (합의 1)

### [위험] CVE-2025-48989 — org.apache.tomcat.embed:tomcat-embed-core@10.1.41
- 도달성: **도달 불가** (dep-scan)
- 수정: `11.0.10, 10.1.44, 9.0.108` 이상으로 업그레이드
- CWE-404 · 탐지: trivy (합의 1)
- 컴플라이언스: KISA 부적절한 자원 해제

### [위험] CVE-2025-52520 — org.apache.tomcat.embed:tomcat-embed-core@10.1.41
- 도달성: **도달 불가** (dep-scan)
- 수정: `11.0.9, 10.1.43, 9.0.107` 이상으로 업그레이드
- CWE-190 · 탐지: trivy (합의 1)
- 컴플라이언스: KISA 정수형 오버플로우 · PCI-DSS 6.2.4 — data/buffer manipulation

### [위험] CVE-2025-53506 — org.apache.tomcat.embed:tomcat-embed-core@10.1.41
- 도달성: **도달 불가** (dep-scan)
- 수정: `9.0.107, 10.1.43, 11.0.9` 이상으로 업그레이드
- CWE-400 · 탐지: trivy (합의 1)

### [위험] CVE-2025-55752 — org.apache.tomcat.embed:tomcat-embed-core@10.1.41
- 도달성: **도달 불가** (dep-scan)
- 수정: `11.0.11, 10.1.45, 9.0.109` 이상으로 업그레이드
- CWE-23 · 탐지: trivy (합의 1)

### [위험] CVE-2026-24734 — org.apache.tomcat.embed:tomcat-embed-core@10.1.41
- 도달성: **도달 불가** (dep-scan)
- 수정: `11.0.18, 10.1.52, 9.0.115` 이상으로 업그레이드
- CWE-20 CWE-295 · 탐지: trivy (합의 1)
- 컴플라이언스: KISA 부적절한 인증서 유효성 검증 · PCI-DSS 6.2.4 — cryptography

### [위험] CVE-2026-24880 — org.apache.tomcat.embed:tomcat-embed-core@10.1.41
- 도달성: **도달 불가** (dep-scan)
- 수정: `9.0.116, 10.1.52, 11.0.20` 이상으로 업그레이드
- CWE-444 · 탐지: trivy (합의 1)

### [위험] CVE-2026-34483 — org.apache.tomcat.embed:tomcat-embed-core@10.1.41
- 도달성: **도달 불가** (dep-scan)
- 수정: `9.0.116, 10.1.54, 11.0.21` 이상으로 업그레이드
- CWE-116 · 탐지: trivy (합의 1)

### [위험] CVE-2026-41284 — org.apache.tomcat.embed:tomcat-embed-core@10.1.41
- 도달성: **도달 불가** (dep-scan)
- 수정: `9.0.118, 10.1.55, 11.0.22` 이상으로 업그레이드
- CWE-770 · 탐지: trivy (합의 1)

### [위험] CVE-2026-42498 — org.apache.tomcat.embed:tomcat-embed-core@10.1.41
- 도달성: **도달 불가** (dep-scan)
- 수정: `9.0.118, 10.1.55, 11.0.22` 이상으로 업그레이드
- CWE-200 · 탐지: trivy (합의 1)

### [위험] CVE-2026-43513 — org.apache.tomcat.embed:tomcat-embed-core@10.1.41
- 도달성: **도달 불가** (dep-scan)
- 수정: `9.0.118, 10.1.55, 11.0.22` 이상으로 업그레이드
- CWE-178 · 탐지: trivy (합의 1)

### [위험] CVE-2026-24400 — org.assertj:assertj-core@3.27.3
- 도달성: **도달 불가** (dep-scan)
- 수정: `3.27.7` 이상으로 업그레이드
- CWE-611 · 탐지: trivy (합의 1)
- 컴플라이언스: KISA 부적절한 XML 외부 개체 참조(XXE) · PCI-DSS 6.2.4 — injection

### [위험] CVE-2026-40973 — org.springframework.boot:spring-boot@3.5.0
- 도달성: **도달 불가** (dep-scan)
- 수정: `4.0.6, 3.5.14` 이상으로 업그레이드
- CWE-377 · 탐지: trivy (합의 1)

### [위험] CVE-2025-41249 — org.springframework:spring-core@6.2.7
- 도달성: **도달 불가** (dep-scan)
- 수정: `6.2.11` 이상으로 업그레이드
- CWE-285 · 탐지: trivy (합의 1)
- 컴플라이언스: KISA 부적절한 인가 · PCI-DSS 6.2.4 — access control

### [보통] CVE-2025-11226 — ch.qos.logback:logback-core@1.5.18
- 도달성: **도달 불가** (dep-scan)
- 수정: `1.5.19, 1.3.16` 이상으로 업그레이드
- CWE-20 · 탐지: trivy (합의 1)

### [보통] GHSA-72hv-8253-57qq — com.fasterxml.jackson.core:jackson-core@2.19.0
- 도달성: **도달 불가** (dep-scan)
- 수정: `2.21.1, 2.18.6` 이상으로 업그레이드
- 탐지: trivy (합의 1)

### [보통] CVE-2026-54514 — com.fasterxml.jackson.core:jackson-databind@2.19.0
- 도달성: **도달 불가** (dep-scan)
- 수정: `2.18.8, 2.21.4, 3.1.4` 이상으로 업그레이드
- CWE-918 · 탐지: trivy (합의 1)
- 컴플라이언스: KISA 서버사이드 요청 위조(SSRF) · PCI-DSS 6.2.4 — business logic

### [보통] CVE-2026-54515 — com.fasterxml.jackson.core:jackson-databind@2.19.0
- 도달성: **도달 불가** (dep-scan)
- 수정: `3.1.4, 2.18.9, 2.21.5, 2.22.1` 이상으로 업그레이드
- CWE-915 · 탐지: trivy (합의 1)

### [보통] CVE-2025-49124 — org.apache.tomcat.embed:tomcat-embed-core@10.1.41
- 도달성: **도달 불가** (dep-scan)
- 수정: `11.0.8, 10.1.42, 9.0.106` 이상으로 업그레이드
- CWE-426 · 탐지: trivy (합의 1)

### [보통] CVE-2025-49125 — org.apache.tomcat.embed:tomcat-embed-core@10.1.41
- 도달성: **도달 불가** (dep-scan)
- 수정: `11.0.8, 10.1.42, 9.0.106` 이상으로 업그레이드
- CWE-288 · 탐지: trivy (합의 1)

### [보통] CVE-2025-55668 — org.apache.tomcat.embed:tomcat-embed-core@10.1.41
- 도달성: **도달 불가** (dep-scan)
- 수정: `11.0.8, 10.1.42, 9.0.106` 이상으로 업그레이드
- CWE-384 · 탐지: trivy (합의 1)

### [보통] CVE-2025-66614 — org.apache.tomcat.embed:tomcat-embed-core@10.1.41
- 도달성: **도달 불가** (dep-scan)
- 수정: `11.0.15, 10.1.50, 9.0.113` 이상으로 업그레이드
- CWE-20 CWE-295 · 탐지: trivy (합의 1)
- 컴플라이언스: KISA 부적절한 인증서 유효성 검증 · PCI-DSS 6.2.4 — cryptography

### [보통] CVE-2026-25854 — org.apache.tomcat.embed:tomcat-embed-core@10.1.41
- 도달성: **도달 불가** (dep-scan)
- 수정: `9.0.116, 10.1.53, 11.0.20` 이상으로 업그레이드
- CWE-601 · 탐지: trivy (합의 1)
- 컴플라이언스: KISA 신뢰되지 않는 URL 주소로 자동접속 연결 · PCI-DSS 6.2.4 — business logic

### [보통] CVE-2025-41234 — org.springframework:spring-web@6.2.7
- 도달성: **도달 불가** (dep-scan)
- 수정: `6.2.8, 6.1.21` 이상으로 업그레이드
- CWE-113 · 탐지: trivy (합의 1)
- 컴플라이언스: KISA HTTP 응답분할 · PCI-DSS 6.2.4 — injection

### [보통] CVE-2025-41242 — org.springframework:spring-webmvc@6.2.7
- 도달성: **도달 불가** (dep-scan)
- 수정: `6.2.10` 이상으로 업그레이드
- CWE-22 · 탐지: trivy (합의 1)
- 컴플라이언스: KISA 경로 조작 및 자원 삽입 · PCI-DSS 6.2.4 — injection

### [보통] CVE-2026-22737 — org.springframework:spring-webmvc@6.2.7
- 도달성: **도달 불가** (dep-scan)
- 수정: `7.0.6, 6.2.17` 이상으로 업그레이드
- CWE-22 · 탐지: trivy (합의 1)
- 컴플라이언스: KISA 경로 조작 및 자원 삽입 · PCI-DSS 6.2.4 — injection

### [보통] CVE-2026-22745 — org.springframework:spring-webmvc@6.2.7
- 도달성: **도달 불가** (dep-scan)
- 수정: `7.0.7, 6.2.18` 이상으로 업그레이드
- CWE-400 · 탐지: trivy (합의 1)

### [일반] CVE-2026-1225 — ch.qos.logback:logback-core@1.5.18
- 도달성: **도달 불가** (dep-scan)
- 수정: `1.5.25` 이상으로 업그레이드
- CWE-20 · 탐지: trivy (합의 1)

### [일반] CVE-2026-9828 — ch.qos.logback:logback-core@1.5.18
- 도달성: **도달 불가** (dep-scan)
- 수정: `1.5.33` 이상으로 업그레이드
- CWE-502 · 탐지: trivy (합의 1)
- 컴플라이언스: KISA 신뢰할 수 없는 데이터의 역직렬화 · PCI-DSS 6.2.4 — injection

### [일반] CVE-2025-55754 — org.apache.tomcat.embed:tomcat-embed-core@10.1.41
- 도달성: **도달 불가** (dep-scan)
- 수정: `11.0.11, 10.1.45, 9.0.109` 이상으로 업그레이드
- CWE-150 · 탐지: trivy (합의 1)

### [일반] CVE-2025-61795 — org.apache.tomcat.embed:tomcat-embed-core@10.1.41
- 도달성: **도달 불가** (dep-scan)
- 수정: `11.0.12, 10.1.47, 9.0.110` 이상으로 업그레이드
- CWE-404 · 탐지: trivy (합의 1)
- 컴플라이언스: KISA 부적절한 자원 해제

### [일반] CVE-2026-43514 — org.apache.tomcat.embed:tomcat-embed-core@10.1.41
- 도달성: **도달 불가** (dep-scan)
- 수정: `9.0.118, 10.1.55, 11.0.22` 이상으로 업그레이드
- CWE-208 · 탐지: trivy (합의 1)

### [일반] CVE-2026-22735 — org.springframework:spring-webmvc@6.2.7
- 도달성: **도달 불가** (dep-scan)
- 수정: `7.0.6, 6.2.17` 이상으로 업그레이드
- CWE-667 · 탐지: trivy (합의 1)

### [일반] CVE-2026-22741 — org.springframework:spring-webmvc@6.2.7
- 도달성: **도달 불가** (dep-scan)
- 수정: `7.0.7, 6.2.18` 이상으로 업그레이드
- CWE-524 · 탐지: trivy (합의 1)
