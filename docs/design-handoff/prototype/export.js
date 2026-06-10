/* Export commands (command pattern). Each command: { id, label, icon, run(note, ctx) }
   ctx = { openNote, toast } */
(function () {
  function buildMarkdown(note) {
    let s = "# " + (note.title || "제목 없음") + "\n\n";
    if (note.tags && note.tags.length) s += note.tags.map((t) => "#" + t).join(" ") + "\n\n";
    s += (note.content || "") + "\n";
    return s;
  }

  function download(filename, text) {
    const blob = new Blob([text], { type: "text/markdown;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url; a.download = filename;
    document.body.appendChild(a); a.click();
    setTimeout(() => { document.body.removeChild(a); URL.revokeObjectURL(url); }, 100);
  }

  async function copyClipboard(text) {
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) { await navigator.clipboard.writeText(text); return true; }
    } catch (e) {}
    try {
      const ta = document.createElement("textarea");
      ta.value = text; ta.style.position = "fixed"; ta.style.opacity = "0";
      document.body.appendChild(ta); ta.select();
      const ok = document.execCommand("copy");
      document.body.removeChild(ta);
      return ok;
    } catch (e) { return false; }
  }

  const safeName = (s) => (s || "note").replace(/[\\/:*?"<>|]/g, "_").trim() || "note";

  const exportCommands = [
    {
      id: "pdf", label: "PDF로 내보내기", icon: "pdf",
      run: (note, ctx) => {
        ctx.openNote && ctx.openNote(note);
        // wait for the note + diagrams to render, then print
        setTimeout(() => {
          ctx.toast && ctx.toast("인쇄 대화상자에서 'PDF로 저장'을 선택하세요", "pdf");
          window.print();
        }, 450);
      },
    },
    {
      id: "markdown", label: "Markdown(.md)으로 내보내기", icon: "markdown",
      run: (note, ctx) => {
        download(safeName(note.title) + ".md", buildMarkdown(note));
        ctx.toast && ctx.toast("Markdown 파일을 내려받았습니다", "markdown");
      },
    },
    {
      id: "clipboard", label: "클립보드에 복사", icon: "clipboard",
      run: async (note, ctx) => {
        const ok = await copyClipboard(buildMarkdown(note));
        ctx.toast && ctx.toast(ok ? "클립보드에 복사했습니다" : "복사에 실패했습니다", ok ? "check" : "x");
      },
    },
  ];

  window.exportCommands = exportCommands;
  window.buildMarkdown = buildMarkdown;
})();
