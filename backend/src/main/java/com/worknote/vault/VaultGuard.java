package com.worknote.vault;

import com.worknote.acl.PermissionService;
import com.worknote.auth.UserRow;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Vault API 권한 가드 — 컨트롤러 앞단. local 모드(user=null)와 관리자는 전체 허용. */
@Component
public class VaultGuard {

    private final PermissionService perm;
    private final NodeMapper nodes;

    public VaultGuard(PermissionService perm, NodeMapper nodes) {
        this.perm = perm;
        this.nodes = nodes;
    }

    /** local 모드(user=null) 또는 관리자 — 검사 전부 통과. */
    private boolean bypass(UserRow user) {
        return user == null ? !perm.serverMode() : perm.isAdmin(user);
    }

    /** server 모드 user=null 2차 방어 — AuthFilter가 1차로 막지만 비-HTTP 경로 대비 403 (PermissionService 컨벤션과 대칭). */
    private void requireUser(UserRow user) {
        if (user == null) {
            throw VaultException.forbidden("인증이 필요합니다");
        }
    }

    /** create(F) = roleHas(res.create) ∧ edit(F). F=null(루트)은 관리자만 — canEdit이 처리. */
    public void requireCreate(UserRow user, String parentId) {
        if (bypass(user)) return;
        requireUser(user);
        if (!perm.roleHas(user, "res.create") || !perm.canEdit(user, parentId)) {
            throw VaultException.forbidden("생성 권한이 없습니다");
        }
    }

    public void requireEdit(UserRow user, String id) {
        if (bypass(user)) return;
        requireUser(user);
        if (!perm.canEdit(user, id)) {
            throw VaultException.forbidden("편집 권한이 없습니다: " + id);
        }
    }

    /** read(N) — 첨부 열람 가드. local/관리자 bypass. */
    public void requireRead(UserRow user, String id) {
        if (bypass(user)) return;
        requireUser(user);
        if (!perm.canRead(user, id)) {
            throw VaultException.forbidden("열람 권한이 없습니다: " + id);
        }
    }

    /** move = edit(원본) ∧ edit(대상). 대상 null(루트)은 관리자만 — canEdit이 처리. */
    public void requireMove(UserRow user, String id, String newParentId) {
        requireEdit(user, id);
        if (bypass(user)) return;
        if (!perm.canEdit(user, newParentId)) {
            throw VaultException.forbidden("대상 폴더 편집 권한이 없습니다");
        }
    }

    /** delete = roleHas(res.delete) ∧ edit(N). */
    public void requireDelete(UserRow user, String id) {
        if (bypass(user)) return;
        requireUser(user);
        if (!perm.roleHas(user, "res.delete") || !perm.canEdit(user, id)) {
            throw VaultException.forbidden("삭제 권한이 없습니다: " + id);
        }
    }

    /** restore = 삭제자 본인 또는 관리자 (스펙 §4.3). 미존재 id도 403 — 존재 여부 비노출. */
    public void requireRestore(UserRow user, String id) {
        if (bypass(user)) return;
        requireUser(user);
        NodeRow row = nodes.findById(id);
        if (row == null || !who(user).equals(row.deletedBy())) {
            throw VaultException.forbidden("복구 권한이 없습니다: " + id);
        }
    }

    /** purge = 관리자 전용 (스펙 §4.3). */
    public void requirePurge(UserRow user) {
        if (bypass(user)) return;
        requireUser(user);
        throw VaultException.forbidden("영구 삭제는 관리자만 가능합니다");
    }

    /** share(N) = roleHas(res.share) ∧ read(N) — 스펙 §5.2. 생성·노드별 목록 가드. */
    public void requireShare(UserRow user, String id) {
        if (bypass(user)) return;
        requireUser(user);
        if (!perm.roleHas(user, "res.share") || !perm.canRead(user, id)) {
            throw VaultException.forbidden("공유 권한이 없습니다: " + id);
        }
    }

    /** 관리자/local 특권 여부 — 공유 링크 취소·전체 목록 분기용. */
    public boolean privileged(UserRow user) {
        return bypass(user);
    }

    /** GET /tree 필터 — null = 무필터(local/관리자). */
    public Set<String> readableIds(UserRow user) {
        if (bypass(user)) return null;
        requireUser(user);
        return perm.readableIds(user, nodes.findActive());
    }

    /** 휴지통 가시성 — null = 전체(local/관리자), 아니면 본인(emp) 삭제분만. */
    public String trashFilter(UserRow user) {
        if (bypass(user)) return null;
        requireUser(user);
        return who(user);
    }

    /** deleted_by 값 — server 모드는 사번(emp), local 모드는 "local". */
    public String who(UserRow user) {
        return user == null ? "local" : user.emp();
    }
}
