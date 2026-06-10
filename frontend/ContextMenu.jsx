/* ContextMenu — positioned menu with optional submenus.
   props: x, y, items, onClose
   item: { icon, label, onClick, danger, kbd } | { sep:true } | { icon,label,submenu:[...] } */
(function () {
  const { useState, useRef, useEffect } = React;
  const Icon = window.Icon;

  function MenuList({ items, onClose, style }) {
    const [openSub, setOpenSub] = useState(null);
    const ref = useRef(null);

    return React.createElement(
      "div",
      { className: "ctx", style, ref, onContextMenu: (e) => e.preventDefault() },
      items.map((it, i) => {
        if (it.sep) return React.createElement("div", { className: "ctx-sep", key: "s" + i });
        const hasSub = !!it.submenu;
        return React.createElement(
          "div",
          {
            key: i,
            className: "ctx-item" + (it.danger ? " danger" : ""),
            onMouseEnter: () => setOpenSub(hasSub ? i : null),
            onClick: (e) => {
              if (hasSub) return;
              e.stopPropagation();
              onClose();
              it.onClick && it.onClick();
            },
          },
          it.icon && React.createElement("span", { className: "ic" }, React.createElement(Icon, { name: it.icon })),
          React.createElement("span", null, it.label),
          it.kbd && React.createElement("span", { className: "kbd" }, it.kbd),
          hasSub && React.createElement("span", { className: "chev" }, React.createElement(Icon, { name: "chevron" })),
          hasSub && openSub === i &&
            React.createElement(MenuList, {
              items: it.submenu,
              onClose,
              style: { position: "absolute", left: "100%", top: -5, marginLeft: 3 },
            })
        );
      })
    );
  }

  function ContextMenu({ x, y, items, onClose }) {
    const ref = useRef(null);
    const [pos, setPos] = useState({ left: x, top: y, visibility: "hidden" });

    useEffect(() => {
      const el = ref.current;
      if (!el) return;
      const r = el.getBoundingClientRect();
      let left = x, top = y;
      if (left + r.width > window.innerWidth - 8) left = window.innerWidth - r.width - 8;
      if (top + r.height > window.innerHeight - 8) top = Math.max(8, window.innerHeight - r.height - 8);
      setPos({ left, top, visibility: "visible" });
    }, [x, y]);

    useEffect(() => {
      const close = () => onClose();
      const onKey = (e) => { if (e.key === "Escape") onClose(); };
      window.addEventListener("mousedown", close);
      window.addEventListener("blur", close);
      window.addEventListener("resize", close);
      document.addEventListener("keydown", onKey);
      return () => {
        window.removeEventListener("mousedown", close);
        window.removeEventListener("blur", close);
        window.removeEventListener("resize", close);
        document.removeEventListener("keydown", onKey);
      };
    }, []);

    return React.createElement(
      "div",
      { ref, style: { position: "fixed", zIndex: 1000, ...pos }, onMouseDown: (e) => e.stopPropagation() },
      React.createElement(MenuList, { items, onClose })
    );
  }

  window.ContextMenu = ContextMenu;
})();
