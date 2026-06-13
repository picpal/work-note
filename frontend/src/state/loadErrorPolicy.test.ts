import { describe, it, expect } from "vitest";
import { isBackendDown } from "./loadErrorPolicy";
import { ApiError } from "../api/http";

describe("isBackendDown", () => {
  it("local 모드는 절대 차단하지 않음", () => {
    expect(isBackendDown(new Error("x"), "local")).toBe(false);
    expect(isBackendDown(new ApiError("e", 500), "local")).toBe(false);
  });
  it("http 401 은 차단 안 함 (on401 리다이렉트가 처리)", () => {
    expect(isBackendDown(new ApiError("unauth", 401), "http")).toBe(false);
  });
  it("http 네트워크 오류·5xx 는 차단", () => {
    expect(isBackendDown(new TypeError("fetch failed"), "http")).toBe(true);
    expect(isBackendDown(new ApiError("boom", 500), "http")).toBe(true);
  });
});
