import { describe, it, expect } from "vitest";
import { formatBytes } from "./AttachmentBar";

describe("formatBytes", () => {
  it("1KB 미만은 B 단위", () => {
    expect(formatBytes(0)).toBe("0 B");
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(1023)).toBe("1023 B");
  });
  it("1MB 미만은 KB 단위(소수 1자리)", () => {
    expect(formatBytes(1024)).toBe("1.0 KB");
    expect(formatBytes(2048)).toBe("2.0 KB");
    expect(formatBytes(1536)).toBe("1.5 KB");
  });
  it("1MB 이상은 MB 단위(소수 1자리)", () => {
    expect(formatBytes(1024 * 1024)).toBe("1.0 MB");
    expect(formatBytes(5 * 1024 * 1024)).toBe("5.0 MB");
  });
});
