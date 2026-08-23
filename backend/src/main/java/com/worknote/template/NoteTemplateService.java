package com.worknote.template;

import com.worknote.vault.VaultException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 노트 템플릿 CRUD. 노트 ACL 엔진은 개입하지 않는다 — 템플릿은 리소스 트리 밖 객체이고
 * 권한 판정은 소유자 일치 여부뿐이다(시스템 템플릿은 AdminGuard가 별도로 막는다).
 *
 * <p>검증은 이 클래스가 단일 출처다. DTO에 Bean Validation을 달면 같은 성격의 실패가
 * 400(MethodArgumentNotValid)과 422(VaultException.invalid)로 갈리므로 쓰지 않는다.
 */
@Service
public class NoteTemplateService {

    private final NoteTemplateMapper mapper;

    public NoteTemplateService(NoteTemplateMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<NoteTemplateRow> listVisible(String ownerId) {
        return mapper.listVisible(ownerId);
    }

    @Transactional(readOnly = true)
    public List<NoteTemplateRow> listSystem() {
        return mapper.listSystem();
    }

    /** ownerId == null 이면 시스템 템플릿을 만든다(관리자 경로 전용 — 개수 상한 없음). */
    @Transactional
    public NoteTemplateRow create(String ownerId, String name, String body) {
        validate(name, body);
        if (ownerId != null && mapper.countByOwner(ownerId) >= TemplateLimits.PER_OWNER_MAX) {
            throw VaultException.invalid(
                "템플릿은 최대 " + TemplateLimits.PER_OWNER_MAX + "개까지 만들 수 있습니다");
        }
        String now = now();
        NoteTemplateRow row = new NoteTemplateRow(
            UUID.randomUUID().toString(), ownerId, strip(name), body, now, now);
        mapper.insert(row);
        return row;
    }

    /** system=true면 시스템 템플릿만, false면 ownerId 소유분만 고칠 수 있다. */
    @Transactional
    public NoteTemplateRow update(String id, String ownerId, boolean system, String name, String body) {
        validate(name, body);
        NoteTemplateRow cur = requireWritable(id, ownerId, system);
        NoteTemplateRow next = new NoteTemplateRow(
            cur.id(), cur.ownerId(), strip(name), body, cur.createdAt(), now());
        mapper.update(next);
        return next;
    }

    /** 삭제 전 행을 돌려준다 — 관리자 경로가 감사 로그에 이름을 남기는 데 쓴다. */
    @Transactional
    public NoteTemplateRow delete(String id, String ownerId, boolean system) {
        NoteTemplateRow cur = requireWritable(id, ownerId, system);
        mapper.delete(id);
        return cur;
    }

    private NoteTemplateRow requireWritable(String id, String ownerId, boolean system) {
        NoteTemplateRow cur = mapper.find(id);
        if (cur == null) {
            throw VaultException.notFound("템플릿을 찾을 수 없습니다");
        }
        if (system) {
            if (cur.ownerId() != null) {
                throw VaultException.forbidden("시스템 템플릿이 아닙니다");
            }
        } else if (!Objects.equals(cur.ownerId(), ownerId)) {
            // 시스템 템플릿(ownerId=null)과 타인 소유 모두 여기서 막힌다.
            // 404가 아니라 403인 이유: id는 UUID라 열거 가치가 없고, 403이 원인을 정확히 알려 준다.
            throw VaultException.forbidden("내 템플릿만 수정할 수 있습니다");
        }
        return cur;
    }

    private static void validate(String name, String body) {
        String n = strip(name);
        if (n.isEmpty()) {
            throw VaultException.invalid("템플릿 이름을 입력하세요");
        }
        if (n.length() > TemplateLimits.NAME_MAX) {
            throw VaultException.invalid("이름은 " + TemplateLimits.NAME_MAX + "자 이하여야 합니다");
        }
        // 저장은 원본 body 그대로 한다(앞뒤 공백이 의미를 가질 수 있다) — 여기서는 빈 여부 판정에만 strip을 쓴다.
        if (body == null || strip(body).isEmpty()) {
            throw VaultException.invalid("템플릿 본문이 비어 있습니다");
        }
        if (body.length() > TemplateLimits.BODY_MAX) {
            throw VaultException.invalid("본문이 너무 깁니다");
        }
    }

    /**
     * 앞뒤 공백을 제거한다. {@code String.trim()}은 U+0020 이하만 걷어내 EM SPACE(U+2003) 같은
     * 전각 공백을 남기고, {@code String.isBlank()}가 쓰는 {@code Character.isWhitespace}는
     * NBSP(U+00A0)를 공백으로 보지 않는다. 유니코드 Zs(SPACE_SEPARATOR) 범주까지 함께 걸러야
     * "공백만 있는 값"을 정확히 빈 값으로 판정할 수 있다. 정규식 대신 문자 루프를 쓴다(ReDoS 회피 관례).
     */
    private static String strip(String s) {
        if (s == null) {
            return "";
        }
        int start = 0;
        int end = s.length();
        while (start < end && isSpace(s.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean isSpace(char c) {
        // U+FEFF(ZERO WIDTH NO-BREAK SPACE)는 Character.isWhitespace도 SPACE_SEPARATOR(Zs) 범주도
        // 아니지만, 프런트가 쓰는 JS String.trim()은 이를 공백으로 보고 걷어낸다 — 서버 판정 범위를
        // 맞추지 않으면 이름/본문이 U+FEFF뿐인 값이 서버 직접 호출로 저장을 통과한다(codex 2회차 B2).
        return Character.isWhitespace(c) || Character.getType(c) == Character.SPACE_SEPARATOR || c == '\uFEFF';
    }

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
