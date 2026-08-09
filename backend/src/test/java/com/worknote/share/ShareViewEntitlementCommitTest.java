package com.worknote.share;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import com.worknote.vault.VaultException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 열람 자격(ShareViewSession 표식)은 HttpSession에 있어 트랜잭션에 참여하지 않는다.
 * 열람수 증가와 나란히 트랜잭션 안에서 남기면 롤백 시 view_count만 되돌아가고 표식은 살아남아,
 * 열람을 소비하지 않은 세션이 소진된 링크의 첨부를 계속 가져가는 자격이 된다 —
 * 롤백 경계를 넘어 살아남는 권한 부여다.
 *
 * <p>그래서 표식은 커밋된 뒤에만 남아야 하고, 되돌아갈 트랜잭션이 없을 때는 즉시 남아야 한다.
 *
 * <p>익명 file::memory:는 JVM 전역 단일 DB라 공통 노드 id를 심는 클래스끼리 PK가 충돌한다 — 클래스 전용 이름으로 격리
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file:sharecommitmem?mode=memory&cache=shared")
class ShareViewEntitlementCommitTest {
    @Autowired ShareLinkService service;
    @Autowired ShareLinkMapper mapper;
    @Autowired NodeMapper nodes;
    @Autowired ShareViewSession viewed;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    static final String NOW = "2026-06-12T10:00:00";

    private TransactionTemplate tx;
    private MockHttpServletRequest viewerSession;

    @BeforeEach
    void seed() {
        tx = new TransactionTemplate(txManager);
        jdbc.update("DELETE FROM share_link");
        jdbc.update("DELETE FROM node WHERE id LIKE 'sc-%'");
        nodes.insert(new NodeRow("sc-n1", null, "note", "N-sc-n1", 1, "body-sc-n1", NOW, null, null));
        viewerSession = enter(new MockHttpServletRequest());
    }

    @AfterEach
    void resetContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** 이 브라우저 세션에서 이후 호출이 일어나게 한다. */
    private static MockHttpServletRequest enter(MockHttpServletRequest req) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        return req;
    }

    /**
     * 1. 호출부가 롤백하면 열람도 자격도 남지 않는다.
     * 소비하지 않은 열람의 자격이 남으면, 다른 열람자가 상한을 소진한 뒤에도 첨부를 계속 가져간다.
     */
    @Test
    void 롤백된_열람은_첨부_자격을_남기지_않는다() {
        ShareLinkRow link = service.create("sc-n1", "emp1", null, 1, null);

        assertThatThrownBy(() -> tx.execute(status -> {
            service.resolve(link.token(), "emp1");
            throw new IllegalStateException("의도적 롤백");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(mapper.findById(link.id()).viewCount()).isZero();   // 열람은 소비되지 않았다
        assertThat(viewed.hasViewed(link.id(), "emp1")).isFalse();     // 자격도 남으면 안 된다

        // 실제 피해 — 다른 열람자가 상한을 소진하면, 소비한 적 없는 이 세션은 첨부도 거부돼야 한다
        enter(new MockHttpServletRequest());
        service.resolve(link.token(), "emp2");
        assertThat(mapper.findById(link.id()).viewCount()).isEqualTo(1);   // 소진

        enter(viewerSession);
        assertThrows(() -> service.nodeIdForAttachment(link.token(), "emp1"),
            VaultException.Status.NOT_FOUND, "유효하지 않습니다");
    }

    /**
     * 2. 커밋되면 자격이 남는다 — 소진된 링크의 첨부 서빙(기존 동작)이 회귀하면 안 된다.
     * afterCommit이 요청 스레드가 아직 바인딩된 상태에서 실행된다는 것도 여기서 확인된다
     * (표식은 RequestContextHolder를 통해서만 세션에 닿는다).
     */
    @Test
    void 커밋된_열람은_소진된_링크의_첨부_자격을_남긴다() {
        ShareLinkRow link = service.create("sc-n1", "emp1", null, 1, null);

        ShareView view = tx.execute(status -> service.resolve(link.token(), "emp1"));

        assertThat(view).isNotNull();
        assertThat(mapper.findById(link.id()).viewCount()).isEqualTo(1);
        assertThat(viewed.hasViewed(link.id(), "emp1")).isTrue();
        assertThat(service.nodeIdForAttachment(link.token(), "emp1")).isEqualTo("sc-n1");
    }

    /** 3. 바깥 트랜잭션 없이(서비스 자신의 트랜잭션만) 열람해도 자격은 남는다. */
    @Test
    void 바깥_트랜잭션_없는_열람도_자격을_남긴다() {
        ShareLinkRow link = service.create("sc-n1", "emp1", null, 1, null);

        service.resolve(link.token(), "emp1");

        assertThat(viewed.hasViewed(link.id(), "emp1")).isTrue();
        assertThat(service.nodeIdForAttachment(link.token(), "emp1")).isEqualTo("sc-n1");
    }

    /**
     * 4. 트랜잭션 동기화 자체가 없을 때(프록시를 거치지 않는 직접 호출) — 등록할 곳이 없으니 즉시 남긴다.
     * 여기서 빠져나가면 자격이 조용히 사라진다.
     */
    @Test
    void 트랜잭션_동기화가_없으면_즉시_기록한다() {
        ShareLinkRow link = service.create("sc-n1", "emp1", null, 1, null);
        ShareLinkService bare = new ShareLinkService(mapper, nodes, json, clock, viewed);
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();

        bare.resolve(link.token(), "emp1");

        assertThat(viewed.hasViewed(link.id(), "emp1")).isTrue();
    }

    private static void assertThrows(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                     VaultException.Status status, String messagePart) {
        assertThatThrownBy(call)
            .isInstanceOf(VaultException.class)
            .hasMessageContaining(messagePart)
            .satisfies(e -> assertThat(((VaultException) e).status()).isEqualTo(status));
    }
}
