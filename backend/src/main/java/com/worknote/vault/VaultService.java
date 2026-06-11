package com.worknote.vault;

import com.worknote.acl.AclMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 트리 조립·검증·트랜잭션 경계. 영속은 NodeMapper에 위임, FK enforcement off — 검증은 앱 레벨. */
@Service
public class VaultService {

    private static final String FOLDER = "folder";
    private static final String NOTE = "note";

    private final NodeMapper mapper;
    private final AclMapper aclMapper;
    private final Clock clock;

    public VaultService(NodeMapper mapper, AclMapper aclMapper, Clock clock) {
        this.mapper = mapper;
        this.aclMapper = aclMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<VaultNode> tree() {
        return tree(null);
    }

    /** readable=null이면 무필터(local/관리자). 필터 시 포함 노드만 조립 — 스텁 폴더는 readable에 이미 포함. */
    @Transactional(readOnly = true)
    public List<VaultNode> tree(Set<String> readable) {
        // findActive는 parent_id, position, id 정렬 — LinkedHashMap 그룹핑으로 순서 유지
        Map<String, List<NodeRow>> byParent = new LinkedHashMap<>();
        for (NodeRow row : mapper.findActive()) {
            if (readable != null && !readable.contains(row.id())) continue;
            byParent.computeIfAbsent(row.parentId(), k -> new ArrayList<>()).add(row);
        }
        Map<String, List<String>> tagsByNode = new LinkedHashMap<>();
        for (TagRow t : mapper.findAllTags()) {
            tagsByNode.computeIfAbsent(t.nodeId(), k -> new ArrayList<>()).add(t.tag());
        }
        return assemble(null, byParent, tagsByNode);
    }

    @Transactional
    public VaultNode create(String id, String parentId, String type, String name, String content) {
        if (!FOLDER.equals(type) && !NOTE.equals(type)) {
            throw VaultException.invalid("지원하지 않는 type: " + type);
        }
        if (mapper.findById(id) != null) {
            throw VaultException.conflict("이미 존재하는 id: " + id);
        }
        if (parentId != null) {
            requireActiveFolder(parentId);
        }
        int position = mapper.maxPosition(parentId) + 1;
        boolean isNote = NOTE.equals(type);
        String updatedAt = isNote ? nowIso() : null;
        mapper.insert(new NodeRow(id, parentId, type, name, position,
            isNote ? content : null, updatedAt, null, null));
        if (isNote) {
            return new VaultNode(id, NOTE, null, name, null, null, List.of(), toDate(updatedAt), content);
        }
        return new VaultNode(id, FOLDER, name, null, null, List.of(), null, null, null);
    }

    @Transactional
    public void update(String id, String name, String content, List<String> tags) {
        requireActive(id);
        mapper.updateFields(id, name, content, nowIso());
        if (tags != null) {
            mapper.replaceTags(id, tags);
        }
    }

    @Transactional
    public void move(String id, String newParentId) {
        requireActive(id);   // 휴지통 노드는 이동 불가 — trash/update와 동일 패턴 (복구가 유일한 출구)
        if (newParentId != null) {
            requireActiveFolder(newParentId);
            if (mapper.subtreeIds(id).contains(newParentId)) {
                throw VaultException.invalid("자기 자신 또는 하위로 이동할 수 없습니다: " + id);
            }
        }
        mapper.move(id, newParentId, mapper.maxPosition(newParentId) + 1);
    }

    @Transactional
    public void trash(String id, String by) {
        requireActive(id);
        mapper.softDeleteSubtree(id, nowIso(), by);
    }

    @Transactional
    public void restore(String id) {
        NodeRow row = requireExists(id);
        if (row.deletedAt() == null) {
            throw VaultException.invalid("삭제 상태가 아닙니다: " + id);
        }
        // 휴지통 루트만 복구 허용 — 중간 노드를 복구하면 부모가 삭제 상태인 활성 고아가 생겨 트리에서 사라짐
        if (row.parentId() != null) {
            NodeRow parent = mapper.findById(row.parentId());
            if (parent != null && parent.deletedAt() != null) {
                throw VaultException.invalid("휴지통 루트만 복구할 수 있습니다(상위 폴더를 복구하세요): " + id);
            }
        }
        mapper.restoreSubtree(id);
    }

    @Transactional
    public void purge(String id) {
        NodeRow row = requireExists(id);
        if (row.deletedAt() == null) {
            throw VaultException.invalid("활성 노드는 purge할 수 없습니다 (휴지통으로 먼저 이동): " + id);
        }
        // purge = node + 종속행(tag·acl·public_flag·space) 영구 삭제 — 스펙 §4.3.
        // create가 클라이언트 id를 받으므로 잔여 행을 남기면 같은 id 재생성 시 옛 권한이 부활(fail-open)한다.
        List<String> ids = mapper.subtreeIds(id);
        mapper.deleteTagsIn(ids);
        aclMapper.deleteAclIn(ids);
        aclMapper.deletePublicFlagIn(ids);
        aclMapper.deleteSpaceIn(ids);
        mapper.purgeSubtree(id);
    }

    @Transactional(readOnly = true)
    public List<VaultNode> trashList() {
        return trashList(null);
    }

    /** deletedBy=null이면 전체(관리자/local), 아니면 본인 삭제분만 (스펙 §4.3). */
    @Transactional(readOnly = true)
    public List<VaultNode> trashList(String deletedBy) {
        List<VaultNode> out = new ArrayList<>();
        for (NodeRow row : mapper.findTrashRoots()) {
            if (deletedBy != null && !deletedBy.equals(row.deletedBy())) continue;
            if (NOTE.equals(row.type())) {
                out.add(new VaultNode(row.id(), NOTE, null, row.name(), null, null,
                    List.of(), toDate(row.updatedAt()), null));
            } else {
                out.add(new VaultNode(row.id(), FOLDER, row.name(), null, null, null, null, null, null));
            }
        }
        return out;
    }

    // ---- internal ----

    private List<VaultNode> assemble(String parentId, Map<String, List<NodeRow>> byParent,
                                     Map<String, List<String>> tagsByNode) {
        List<VaultNode> nodes = new ArrayList<>();
        for (NodeRow row : byParent.getOrDefault(parentId, List.of())) {
            if (NOTE.equals(row.type())) {
                nodes.add(new VaultNode(row.id(), NOTE, null, row.name(), null, null,
                    tagsByNode.getOrDefault(row.id(), List.of()),
                    toDate(row.updatedAt()), row.content()));
            } else {
                nodes.add(new VaultNode(row.id(), FOLDER, row.name(), null, null,
                    assemble(row.id(), byParent, tagsByNode), null, null, null));
            }
        }
        return nodes;
    }

    private NodeRow requireExists(String id) {
        NodeRow row = mapper.findById(id);
        if (row == null) {
            throw VaultException.notFound("노드를 찾을 수 없습니다: " + id);
        }
        return row;
    }

    private NodeRow requireActive(String id) {
        NodeRow row = requireExists(id);
        if (row.deletedAt() != null) {
            throw VaultException.notFound("삭제된 노드입니다: " + id);
        }
        return row;
    }

    private void requireActiveFolder(String id) {
        NodeRow row = requireActive(id);
        if (!FOLDER.equals(row.type())) {
            throw VaultException.invalid("폴더가 아닙니다: " + id);
        }
    }

    private String nowIso() {
        return LocalDateTime.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /** updated_at(ISO datetime) → 프런트 계약 yyyy-MM-dd. */
    private static String toDate(String updatedAt) {
        return updatedAt == null ? null : updatedAt.substring(0, 10);
    }
}
