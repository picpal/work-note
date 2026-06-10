import { useState, useCallback } from "react";

export interface MenuItem {
  icon?: string;
  label?: string;
  danger?: boolean;
  sep?: boolean;
  submenu?: MenuItem[];
  onClick?: () => void;
}

export interface MenuState {
  x: number;
  y: number;
  items: MenuItem[];
}

export function useContextMenu() {
  const [menu, setMenu] = useState<MenuState | null>(null); // { x, y, items }
  const openMenu = useCallback((x: number, y: number, items: MenuItem[]) => setMenu({ x, y, items }), []);
  const closeMenu = useCallback(() => setMenu(null), []);
  return { menu, openMenu, closeMenu };
}
