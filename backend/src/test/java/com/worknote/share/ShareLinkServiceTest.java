package com.worknote.share;

import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import com.worknote.vault.VaultException;
import com.worknote.vault.VaultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
class ShareLinkServiceTest {
    @Autowired ShareLinkService service;
    @Autowired ShareLinkMapper mapper;
    @Autowired NodeMapper nodes;
    @Autowired VaultService vault;
    @Autowired JdbcTemplate jdbc;

    static final String NOW = "2026-06-12T10:00:00";

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM share_link");
        jdbc.update("DELETE FROM node WHERE id LIKE 'ss-%'");
    }

    private void note(String id) {
        nodes.insert(new NodeRow(id, null, "note", "N-" + id, 1, "body-" + id, NOW, null, null));
    }

    private void folder(String id) {
        nodes.insert(new NodeRow(id, null, "folder", "F-" + id, 1, null, NOW, null, null));
    }

    private static void assertThrows(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                     VaultException.Status status, String messagePart) {
        assertThatThrownBy(call)
            .isInstanceOf(VaultException.class)
            .hasMessageContaining(messagePart)
            .satisfies(e -> assertThat(((VaultException) e).status()).isEqualTo(status));
    }

    // 1. 생성 기본값
    @Test
    void 생성_기본값은_토큰43자_무제한열람_전직원_7일만료다() {
        note("ss-n1");
        ShareLinkRow row = service.create("ss-n1", "emp1", null, null, null);

        assertThat(row.token()).hasSize(43);
        assertThat(row.maxViews()).isNull();
        assertThat(row.pinEmps()).isNull();
        assertThat(row.viewCount()).isZero();
        assertThat(row.revokedAt()).isNull();
        assertThat(LocalDateTime.parse(row.expiresAt()).toLocalDate())
            .isEqualTo(LocalDateTime.parse(row.createdAt()).plusDays(7).toLocalDate());
        assertThat(mapper.findById(row.id())).isEqualTo(row);
    }

    // 2. 폴더 공유 422
    @Test
    void 폴더는_공유할_수_없다() {
        folder("ss-f1");
        assertThrows(() -> service.create("ss-f1", "emp1", null, null, null),
            VaultException.Status.INVALID, "노트만");
    }

    // 3. 잘못된 파라미터 422
    @Test
    void 만료일수_범위와_최대열람수를_검증한다() {
        note("ss-n1");
        assertThrows(() -> service.create("ss-n1", "emp1", 0, null, null),
            VaultException.Status.INVALID, "1~365");
        assertThrows(() -> service.create("ss-n1", "emp1", 366, null, null),
            VaultException.Status.INVALID, "1~365");
        assertThrows(() -> service.create("ss-n1", "emp1", null, 0, null),
            VaultException.Status.INVALID, "1 이상");
    }

    // 4. 열람 성공
    @Test
    void 열람_성공시_열람수가_증가하고_노트내용을_반환한다() {
        note("ss-n1");
        ShareLinkRow row = service.create("ss-n1", "emp1", null, null, null);

        ShareView view = service.resolve(row.token(), "anyone");

        assertThat(view.linkId()).isEqualTo(row.id());
        assertThat(view.nodeId()).isEqualTo("ss-n1");
        assertThat(view.name()).isEqualTo("N-ss-n1");
        assertThat(view.content()).isEqualTo("body-ss-n1");
        assertThat(view.updatedAt()).isEqualTo("2026-06-12");
        assertThat(mapper.findById(row.id()).viewCount()).isEqualTo(1);
    }

    // 5. 무효 사유 전부 404 단일화 (결정 S2)
    @Test
    void 무효_사유는_전부_404_단일이다() {
        String msg = "공유 링크가 유효하지 않습니다";
        note("ss-n1");

        // 미존재 토큰
        assertThrows(() -> service.resolve("no-such-token", "emp1"),
            VaultException.Status.NOT_FOUND, msg);

        // 만료
        ShareLinkRow expired = service.create("ss-n1", "emp1", null, null, null);
        jdbc.update("UPDATE share_link SET expires_at = '2020-01-01T00:00:00' WHERE id = ?", expired.id());
        assertThrows(() -> service.resolve(expired.token(), "emp1"),
            VaultException.Status.NOT_FOUND, msg);

        // 취소 후
        ShareLinkRow revoked = service.create("ss-n1", "emp1", null, null, null);
        service.revoke(revoked.id(), "emp1", false);
        assertThrows(() -> service.resolve(revoked.token(), "emp1"),
            VaultException.Status.NOT_FOUND, msg);

        // maxViews 1 소진 후
        ShareLinkRow once = service.create("ss-n1", "emp1", null, 1, null);
        service.resolve(once.token(), "emp1");
        assertThrows(() -> service.resolve(once.token(), "emp1"),
            VaultException.Status.NOT_FOUND, msg);
        // 실패한 시도는 카운트를 소모하지 않음 — 404 폭탄으로 정당 열람수를 못 태움
        assertThat(mapper.findById(once.id()).viewCount()).isEqualTo(1);

        // pin 불일치 — 일치자는 성공
        ShareLinkRow pinned = service.create("ss-n1", "emp1", null, null, List.of("emp2"));
        assertThrows(() -> service.resolve(pinned.token(), "emp3"),
            VaultException.Status.NOT_FOUND, msg);
        assertThat(mapper.findById(pinned.id()).viewCount()).isZero();   // 불일치 시도 미소모
        assertThat(service.resolve(pinned.token(), "emp2").nodeId()).isEqualTo("ss-n1");
    }

    // pin 정리 — trim·빈 항목 제거·비면 NULL (결정 S11)
    @Test
    void pin은_trim되고_빈_항목이_제거되며_비면_NULL이다() {
        note("ss-n1");
        ShareLinkRow trimmed = service.create("ss-n1", "emp1", null, null,
            List.of(" emp2 ", "", "  "));
        assertThat(trimmed.pinEmps()).isEqualTo("[\"emp2\"]");
        assertThat(service.resolve(trimmed.token(), "emp2").nodeId()).isEqualTo("ss-n1");

        ShareLinkRow empty = service.create("ss-n1", "emp1", null, null, List.of("", " "));
        assertThat(empty.pinEmps()).isNull();   // 전부 빈 항목 → 전 직원
    }

    // 6. 휴지통 = suspend (결정 S3)
    @Test
    void 휴지통_노드의_링크는_suspend되고_restore시_부활한다() {
        note("ss-n1");
        ShareLinkRow row = service.create("ss-n1", "emp1", null, null, null);

        vault.trash("ss-n1", "emp1");
        assertThrows(() -> service.resolve(row.token(), "emp1"),
            VaultException.Status.NOT_FOUND, "유효하지 않습니다");

        vault.restore("ss-n1");
        assertThat(service.resolve(row.token(), "emp1").name()).isEqualTo("N-ss-n1");
    }

    // 7. viewer=null(local 모드)은 pin 검사 생략 (결정 S5)
    @Test
    void local모드_viewer_null은_pin_검사를_생략한다() {
        note("ss-n1");
        ShareLinkRow pinned = service.create("ss-n1", "emp1", null, null, List.of("emp2"));

        assertThat(service.resolve(pinned.token(), null).nodeId()).isEqualTo("ss-n1");
    }

    // 8. 취소 권한 (결정 S10)
    @Test
    void 취소는_생성자_또는_privileged만_가능하고_재취소는_409다() {
        note("ss-n1");
        ShareLinkRow row = service.create("ss-n1", "emp1", null, null, null);

        assertThrows(() -> service.revoke(row.id(), "emp2", false),
            VaultException.Status.FORBIDDEN, "취소 권한이 없습니다");

        ShareLinkRow revoked = service.revoke(row.id(), "admin", true);
        assertThat(revoked.id()).isEqualTo(row.id());
        assertThat(mapper.findById(row.id()).revokedAt()).isNotNull();

        assertThrows(() -> service.revoke(row.id(), "emp1", false),
            VaultException.Status.CONFLICT, "이미 취소된 링크입니다");
    }

    // 9. listForNode 본인 필터
    @Test
    void listForNode는_byEmp가_있으면_본인_생성분만_반환한다() {
        note("ss-n1");
        service.create("ss-n1", "emp1", null, null, null);
        service.create("ss-n1", "emp2", null, null, null);

        assertThat(service.listForNode("ss-n1", "emp1"))
            .extracting(ShareLinkRow::createdBy).containsExactly("emp1");
        assertThat(service.listForNode("ss-n1", null)).hasSize(2);
    }

    // 10. 첨부 이미지 서빙용 — 유효 토큰이면 노드 id를 반환하되 열람수는 증가시키지 않는다
    @Test
    void nodeIdForAttachment_validToken_returnsNodeId_withoutIncrement() {
        note("ss-n1");
        ShareLinkRow link = service.create("ss-n1", "local", 7, null, null);
        int before = service.listForNode("ss-n1", null).get(0).viewCount();

        String nodeId = service.nodeIdForAttachment(link.token(), null);

        assertThat(nodeId).isEqualTo("ss-n1");
        int after = service.listForNode("ss-n1", null).get(0).viewCount();
        assertThat(after).isEqualTo(before); // 이미지 로드는 열람수 미증가
    }

    @Test
    void nodeIdForAttachment_invalidToken_throws() {
        assertThrows(() -> service.nodeIdForAttachment("bogus", null),
            VaultException.Status.NOT_FOUND, "유효하지 않습니다");
    }
}
