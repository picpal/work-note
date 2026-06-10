import { useState, useEffect } from "react";
import { load, save } from "../storage/local";

export function usePersist<T>(key: string, initial: T): [T, React.Dispatch<React.SetStateAction<T>>] {
  const [v, setV] = useState<T>(() => load(key, initial));
  useEffect(() => { save(key, v); }, [key, v]);
  return [v, setV];
}
