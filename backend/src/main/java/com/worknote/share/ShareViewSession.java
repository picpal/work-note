package com.worknote.share;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * "이 브라우저 세션이 이 링크의 열람을 이미 소비했다"는 표식.
 *
 * <p>첨부는 열람수를 소모하지 않는다(이미지 N개 = 열람 1회). 그래서 마지막 열람에서
 * 본문은 보이는데 이미지 요청이 전부 열람수 상한에 걸려 깨졌다. 소비 자체(resolve)와
 * 이미 소비한 열람의 콘텐츠 접근을 나누되, 상한을 약화시키지 않으려면 후자가 그 열람에
 * 묶여 있어야 한다 — 그 묶음이 이 표식이다. DB가 아니라 세션에 두므로 다른 세션·다른
 * 사람에게는 소진된 링크가 여전히 404다.
 */
@Component
public class ShareViewSession {

    static final String ATTR = "worknote.share.viewed";
    private static final int MAX = 32;   // 세션 무한 증식 방지 — 오래된 표식부터 축출

    /** 열람 소비 기록. 요청 밖(스케줄러·단위 호출)이면 조용히 무시한다. */
    public void markViewed(String linkId) {
        HttpSession session = session(true);
        if (session == null) {
            return;
        }
        synchronized (session) {
            Set<String> viewed = read(session);
            Set<String> next = viewed == null ? new LinkedHashSet<>() : new LinkedHashSet<>(viewed);
            next.remove(linkId);   // 재삽입으로 최신 순서 유지 (축출은 오래된 것부터)
            next.add(linkId);
            Iterator<String> it = next.iterator();
            while (next.size() > MAX) {
                it.next();
                it.remove();
            }
            session.setAttribute(ATTR, next);
        }
    }

    /** 세션이 아직 없으면 만들지 않는다 — 첨부 요청만으로 세션이 생기지 않게. */
    public boolean hasViewed(String linkId) {
        HttpSession session = session(false);
        if (session == null) {
            return false;
        }
        synchronized (session) {
            Set<String> viewed = read(session);
            return viewed != null && viewed.contains(linkId);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> read(HttpSession session) {
        Object raw = session.getAttribute(ATTR);
        return raw instanceof Set ? (Set<String>) raw : null;
    }

    private static HttpSession session(boolean create) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes servlet
            ? servlet.getRequest().getSession(create) : null;
    }
}
