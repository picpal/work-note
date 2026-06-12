import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { AdminApi } from "./api";

function mockJson(body: unknown, status = 200) {
  (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
    ok: true, status, json: () => Promise.resolve(body),
  });
}
function mock204() {
  (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
    ok: true, status: 204, json: () => Promise.reject(new Error()),
  });
}

describe("AdminApi", () => {
  beforeEach(() => { vi.stubGlobal("fetch", vi.fn()); });
  afterEach(() => { vi.unstubAllGlobals(); });

  // ── users ──
  it("users는 GET /api/admin/users", async () => {
    mockJson([{ id: "u1", emp: "S1", email: null, name: "n", roleId: "admin", status: "active", lastLogin: null }]);
    const out = await AdminApi.users();
    expect(out[0].id).toBe("u1");
    expect(fetch).toHaveBeenCalledWith("/api/admin/users", expect.anything());
  });

  it("createUser는 POST /api/admin/users", async () => {
    mockJson({ id: "u2", emp: "S2", email: null, name: "신규", roleId: "member", status: "active", lastLogin: null }, 201);
    const out = await AdminApi.createUser({ emp: "S2", name: "신규", roleId: "member", password: "pw123456" });
    expect(out.id).toBe("u2");
    expect(fetch).toHaveBeenCalledWith("/api/admin/users", expect.objectContaining({
      method: "POST", body: JSON.stringify({ emp: "S2", name: "신규", roleId: "member", password: "pw123456" }),
    }));
  });

  it("updateUser는 PATCH /api/admin/users/{id}", async () => {
    mockJson({ id: "u1", emp: "S1", email: null, name: "변경", roleId: "admin", status: "active", lastLogin: null });
    const out = await AdminApi.updateUser("u1", { name: "변경" });
    expect(out.name).toBe("변경");
    expect(fetch).toHaveBeenCalledWith("/api/admin/users/u1", expect.objectContaining({
      method: "PATCH", body: JSON.stringify({ name: "변경" }),
    }));
  });

  it("approveUser는 POST /api/admin/users/{id}/approve", async () => {
    mockJson({ id: "u3", emp: "S3", email: null, name: "n", roleId: "member", status: "active", lastLogin: null });
    const out = await AdminApi.approveUser("u3");
    expect(out.status).toBe("active");
    expect(fetch).toHaveBeenCalledWith("/api/admin/users/u3/approve", expect.objectContaining({ method: "POST" }));
  });

  it("resetPassword는 POST /api/admin/users/{id}/reset-password (204)", async () => {
    mock204();
    await expect(AdminApi.resetPassword("u1", "newpw1234")).resolves.toBeUndefined();
    expect(fetch).toHaveBeenCalledWith("/api/admin/users/u1/reset-password", expect.objectContaining({
      method: "POST", body: JSON.stringify({ password: "newpw1234" }),
    }));
  });

  // ── roles ──
  it("roles는 GET /api/admin/roles", async () => {
    mockJson([{ id: "admin", name: "관리자", system: true, caps: ["admin.users"], userCount: 1 }]);
    const out = await AdminApi.roles();
    expect(out[0].system).toBe(true);
    expect(fetch).toHaveBeenCalledWith("/api/admin/roles", expect.anything());
  });

  it("createRole은 POST /api/admin/roles", async () => {
    mockJson({ id: "viewer", name: "열람자", system: false, caps: ["res.read"], userCount: 0 }, 201);
    const out = await AdminApi.createRole({ id: "viewer", name: "열람자", caps: ["res.read"] });
    expect(out.id).toBe("viewer");
    expect(fetch).toHaveBeenCalledWith("/api/admin/roles", expect.objectContaining({
      method: "POST", body: JSON.stringify({ id: "viewer", name: "열람자", caps: ["res.read"] }),
    }));
  });

  it("updateRole은 PATCH /api/admin/roles/{id}", async () => {
    mockJson({ id: "viewer", name: "열람", system: false, caps: ["res.read"], userCount: 0 });
    const out = await AdminApi.updateRole("viewer", { name: "열람" });
    expect(out.name).toBe("열람");
    expect(fetch).toHaveBeenCalledWith("/api/admin/roles/viewer", expect.objectContaining({
      method: "PATCH", body: JSON.stringify({ name: "열람" }),
    }));
  });

  it("deleteRole은 DELETE /api/admin/roles/{id} (204)", async () => {
    mock204();
    await expect(AdminApi.deleteRole("viewer")).resolves.toBeUndefined();
    expect(fetch).toHaveBeenCalledWith("/api/admin/roles/viewer", expect.objectContaining({ method: "DELETE" }));
  });

  // ── teams ──
  it("teams는 GET /api/admin/teams", async () => {
    mockJson([{ id: "t1", name: "결제팀", members: [] }]);
    const out = await AdminApi.teams();
    expect(out[0].name).toBe("결제팀");
    expect(fetch).toHaveBeenCalledWith("/api/admin/teams", expect.anything());
  });

  it("createTeam은 POST /api/admin/teams", async () => {
    mockJson({ id: "t2", name: "운영팀" }, 201);
    const out = await AdminApi.createTeam("운영팀");
    expect(out.id).toBe("t2");
    expect(fetch).toHaveBeenCalledWith("/api/admin/teams", expect.objectContaining({
      method: "POST", body: JSON.stringify({ name: "운영팀" }),
    }));
  });

  it("renameTeam은 PATCH /api/admin/teams/{id} (204)", async () => {
    mock204();
    await expect(AdminApi.renameTeam("t1", "새이름")).resolves.toBeUndefined();
    expect(fetch).toHaveBeenCalledWith("/api/admin/teams/t1", expect.objectContaining({
      method: "PATCH", body: JSON.stringify({ name: "새이름" }),
    }));
  });

  it("deleteTeam은 DELETE /api/admin/teams/{id} (204)", async () => {
    mock204();
    await expect(AdminApi.deleteTeam("t1")).resolves.toBeUndefined();
    expect(fetch).toHaveBeenCalledWith("/api/admin/teams/t1", expect.objectContaining({ method: "DELETE" }));
  });

  it("addMember는 POST /api/admin/teams/{id}/members (204)", async () => {
    mock204();
    await expect(AdminApi.addMember("t1", "u1")).resolves.toBeUndefined();
    expect(fetch).toHaveBeenCalledWith("/api/admin/teams/t1/members", expect.objectContaining({
      method: "POST", body: JSON.stringify({ userId: "u1" }),
    }));
  });

  it("removeMember는 DELETE /api/admin/teams/{id}/members/{userId} (204)", async () => {
    mock204();
    await expect(AdminApi.removeMember("t1", "u1")).resolves.toBeUndefined();
    expect(fetch).toHaveBeenCalledWith("/api/admin/teams/t1/members/u1", expect.objectContaining({ method: "DELETE" }));
  });

  // ── spaces ──
  it("spaces는 GET /api/admin/spaces", async () => {
    mockJson([{ nodeId: "n1", teamId: "t1" }]);
    const out = await AdminApi.spaces();
    expect(out[0].nodeId).toBe("n1");
    expect(fetch).toHaveBeenCalledWith("/api/admin/spaces", expect.anything());
  });

  it("setSpace는 PUT /api/admin/spaces/{nodeId} (204) — teamId null 허용", async () => {
    mock204();
    await expect(AdminApi.setSpace("n1", null)).resolves.toBeUndefined();
    expect(fetch).toHaveBeenCalledWith("/api/admin/spaces/n1", expect.objectContaining({
      method: "PUT", body: JSON.stringify({ teamId: null }),
    }));
  });

  it("unsetSpace는 DELETE /api/admin/spaces/{nodeId} (204)", async () => {
    mock204();
    await expect(AdminApi.unsetSpace("n1")).resolves.toBeUndefined();
    expect(fetch).toHaveBeenCalledWith("/api/admin/spaces/n1", expect.objectContaining({ method: "DELETE" }));
  });

  // ── acl / public ──
  it("aclAll은 GET /api/admin/acl", async () => {
    mockJson([{ principalType: "team", principalId: "t1", nodeId: "n1", grantType: "read" }]);
    const out = await AdminApi.aclAll();
    expect(out[0].grantType).toBe("read");
    expect(fetch).toHaveBeenCalledWith("/api/admin/acl", expect.anything());
  });

  it("aclForNode는 GET /api/admin/nodes/{id}/acl", async () => {
    mockJson([{ principalType: "user", principalId: "u1", nodeId: "n1", grantType: "deny" }]);
    const out = await AdminApi.aclForNode("n1");
    expect(out[0].principalType).toBe("user");
    expect(fetch).toHaveBeenCalledWith("/api/admin/nodes/n1/acl", expect.anything());
  });

  it("setAcl은 PUT replace-all", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({ ok: true, status: 204, json: () => Promise.reject(new Error()) });
    await AdminApi.setAcl("n1", [{ principalType: "team", principalId: "t1", grantType: "read" }]);
    expect(fetch).toHaveBeenCalledWith("/api/admin/nodes/n1/acl", expect.objectContaining({
      method: "PUT", body: JSON.stringify({ entries: [{ principalType: "team", principalId: "t1", grantType: "read" }] }),
    }));
  });

  it("publicFlags는 GET /api/admin/public", async () => {
    mockJson([{ nodeId: "n1", mode: "public" }]);
    const out = await AdminApi.publicFlags();
    expect(out[0].mode).toBe("public");
    expect(fetch).toHaveBeenCalledWith("/api/admin/public", expect.anything());
  });

  it("setPublic은 PUT /api/admin/nodes/{id}/public (204)", async () => {
    mock204();
    await expect(AdminApi.setPublic("n1", "exclude")).resolves.toBeUndefined();
    expect(fetch).toHaveBeenCalledWith("/api/admin/nodes/n1/public", expect.objectContaining({
      method: "PUT", body: JSON.stringify({ mode: "exclude" }),
    }));
  });

  it("unsetPublic은 DELETE /api/admin/nodes/{id}/public (204)", async () => {
    mock204();
    await expect(AdminApi.unsetPublic("n1")).resolves.toBeUndefined();
    expect(fetch).toHaveBeenCalledWith("/api/admin/nodes/n1/public", expect.objectContaining({ method: "DELETE" }));
  });

  // ── shares ──
  it("shares는 GET /api/admin/shares", async () => {
    mockJson([{ id: "s1", token: "tok1", nodeId: "n1", nodeName: "노트", suspended: false, createdBy: "S1", createdAt: "c", expiresAt: "e", maxViews: null, viewCount: 3, pinEmps: null }]);
    const out = await AdminApi.shares();
    expect(out[0].nodeName).toBe("노트");
    expect(out[0].suspended).toBe(false);
    expect(fetch).toHaveBeenCalledWith("/api/admin/shares", expect.anything());
  });

  it("revokeShare는 DELETE /api/shares/{id} (204)", async () => {
    mock204();
    await expect(AdminApi.revokeShare("s1")).resolves.toBeUndefined();
    expect(fetch).toHaveBeenCalledWith("/api/shares/s1", expect.objectContaining({ method: "DELETE" }));
  });

  // ── audit ──
  it("audit은 빈 필터를 쿼리에서 생략한다", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve({ total: 0, rows: [] }) });
    await AdminApi.audit({ who: "", act: "login.fail", limit: 50, offset: 0 });
    expect(fetch).toHaveBeenCalledWith("/api/admin/audit?act=login.fail&limit=50&offset=0", expect.anything());
  });

  it("audit은 필터 전부 비면 쿼리스트링 없이 호출한다", async () => {
    mockJson({ total: 1, rows: [{ id: 1, at: "2026-06-12 09:00:00", who: "S1", act: "login.success", target: null, ip: "10.0.0.1" }] });
    const out = await AdminApi.audit({});
    expect(out.total).toBe(1);
    expect(fetch).toHaveBeenCalledWith("/api/admin/audit", expect.anything());
  });
});
