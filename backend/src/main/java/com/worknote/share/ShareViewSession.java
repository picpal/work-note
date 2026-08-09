package com.worknote.share;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * "이 계정이 이 세션에서 이 링크의 열람을 이미 소비했다"는 표식.
 *
 * <p>첨부는 열람수를 소모하지 않는다(이미지 N개 = 열람 1회). 그래서 마지막 열람에서
 * 본문은 보이는데 이미지 요청이 전부 열람수 상한에 걸려 깨졌다. 소비 자체(resolve)와
 * 이미 소비한 열람의 콘텐츠 접근을 나누되, 상한을 약화시키지 않으려면 후자가 그 열람에
 * 묶여 있어야 한다 — 그 묶음이 이 표식이다.
 *
 * <p>묶음의 키는 (브라우저 세션 × 계정 × 링크)다. 세션만으로는 부족한데, 로그인은
 * changeSessionId()로 id만 바꾸고 세션 내용은 유지하기 때문이다 — 공용 PC에서 계정을
 * 바꿔 로그인하면 새 계정이 전 계정의 자격을 물려받는다. 그래서 주체가 달라지는 순간
 * 표식 전체를 폐기한다(개별 대조가 아니라 폐기 — 세션은 한 번에 한 주체의 것이다).
 *
 * <p>수명은 개수가 아니라 시간으로 끊는다. 개수 상한은 링크를 몇 개 더 열었다는 이유만으로
 * 정당한 열람자의 이미지가 조용히 깨지게 만들어 자격의 모양에 맞지 않는다. 표식이 살아 있는
 * 동안만 소진 링크의 첨부가 서빙되므로, 남은 유효기간(일 단위) 대신 한 번의 열람 세션
 * 길이만큼만 열어 두면 된다.
 */
@Component
public class ShareViewSession {

    static final String ATTR = "worknote.share.viewed";

    /**
     * 표식 수명. 한 번 연 노트를 읽는 동안(스크롤에 따라 지연 로딩되는 이미지 포함) 끊기지
     * 않을 만큼 넉넉하되, 링크의 남은 유효기간(기본 7일)에 비하면 무시할 만한 창이다.
     * 렌더+이미지 요청 자체는 수 초면 끝나므로 30분은 "읽는 중" 꼬리까지 덮는 여유값이다.
     */
    static final Duration TTL = Duration.ofMinutes(30);

    private final Clock clock;

    public ShareViewSession(Clock clock) {
        this.clock = clock;
    }

    /** 열람 소비 기록. 요청 밖(스케줄러·단위 호출)이면 조용히 무시한다. */
    public void markViewed(String linkId, String viewerEmp) {
        HttpSession session = session(true);
        if (session == null) {
            return;
        }
        synchronized (session) {
            Entitlements held = read(session);
            Map<String, Long> until = held != null && Objects.equals(held.principal(), viewerEmp)
                ? new HashMap<>(held.until()) : new HashMap<>();   // 주체가 다르면 승계하지 않는다
            long now = clock.millis();
            until.values().removeIf(exp -> exp <= now);   // 만료분 청소 — 세션 무한 증식 방지
            until.put(linkId, now + TTL.toMillis());
            session.setAttribute(ATTR, new Entitlements(viewerEmp, until));
        }
    }

    /** 세션이 아직 없으면 만들지 않는다 — 첨부 요청만으로 세션이 생기지 않게. */
    public boolean hasViewed(String linkId, String viewerEmp) {
        HttpSession session = session(false);
        if (session == null) {
            return false;
        }
        synchronized (session) {
            Entitlements held = read(session);
            if (held == null) {
                return false;
            }
            if (!Objects.equals(held.principal(), viewerEmp)) {
                session.removeAttribute(ATTR);   // 주체 전환 — 조회 시점에 즉시 폐기
                return false;
            }
            Long until = held.until().get(linkId);
            return until != null && clock.millis() < until;
        }
    }

    /**
     * 세션에 담기는 자격 묶음 — 소유 주체(local 모드는 null) + linkId별 만료 시각(epoch ms).
     * 세션 직렬화(컨테이너 재시작 복원) 대상이라 Serializable.
     */
    record Entitlements(String principal, Map<String, Long> until) implements Serializable {}

    private static Entitlements read(HttpSession session) {
        Object raw = session.getAttribute(ATTR);
        return raw instanceof Entitlements e ? e : null;
    }

    private static HttpSession session(boolean create) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes servlet
            ? servlet.getRequest().getSession(create) : null;
    }
}
