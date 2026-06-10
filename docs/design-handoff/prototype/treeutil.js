/* Pure tree helpers operating on the nested vault array. Attached to window. */
(function () {
  let counter = 1000;
  const newId = () => "u" + Date.now().toString(36) + (++counter).toString(36) + Math.floor(Math.random() * 1296).toString(36);
  window.newId = newId;

  // deep clone (data is plain JSON-able)
  const clone = (t) => JSON.parse(JSON.stringify(t));

  // reassign duplicate/missing ids so every node id is unique (repairs older vaults)
  window.dedupeIds = function (tree) {
    const seen = new Set();
    const walk = (nodes) => {
      nodes.forEach((n) => {
        if (!n.id || seen.has(n.id)) n.id = newId();
        seen.add(n.id);
        if (n.children) walk(n.children);
      });
    };
    walk(tree);
    return tree;
  };

  // walk: cb(node, parent, depth, pathArr)
  function walk(tree, cb, parent = null, depth = 0, path = []) {
    for (const node of tree) {
      cb(node, parent, depth, path);
      if (node.type === "folder" && node.children) {
        walk(node.children, cb, node, depth + 1, path.concat(node.name));
      }
    }
  }
  window.walkTree = walk;

  window.findNode = function (tree, id) {
    let found = null, parentArr = null, parentNode = null, path = [];
    walk(tree, (n, parent, d, p) => {
      if (n.id === id) {
        found = n;
        parentNode = parent;
        parentArr = parent ? parent.children : tree;
        path = p;
      }
    });
    return { node: found, parentArr, parentNode, path };
  };

  // returns NEW tree with mutator applied to node of given id
  window.updateNode = function (tree, id, mutate) {
    const t = clone(tree);
    const { node } = window.findNode(t, id);
    if (node) mutate(node);
    return t;
  };

  // insert child into folder (id) or root if id == null; child at top of notes? put folders/then by insertion
  window.insertChild = function (tree, folderId, child) {
    const t = clone(tree);
    if (folderId == null) { t.push(child); return t; }
    const { node } = window.findNode(t, folderId);
    if (node && node.type === "folder") {
      node.open = true;
      node.children = node.children || [];
      node.children.push(child);
    }
    return t;
  };

  window.removeNode = function (tree, id) {
    const t = clone(tree);
    function rec(arr) {
      const i = arr.findIndex((n) => n.id === id);
      if (i >= 0) { arr.splice(i, 1); return true; }
      for (const n of arr) if (n.type === "folder" && n.children && rec(n.children)) return true;
      return false;
    }
    rec(t);
    return t;
  };

  // flatten all notes with their folder path, for search
  window.flattenNotes = function (tree) {
    const out = [];
    walk(tree, (n, parent, d, p) => {
      if (n.type === "note") out.push({ note: n, path: p });
    });
    return out;
  };

  // count notes inside a folder (recursive)
  window.countNotes = function (folder) {
    let c = 0;
    walk(folder.children || [], (n) => { if (n.type === "note") c++; });
    return c;
  };
})();
