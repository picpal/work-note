import { useState, useEffect, type Dispatch, type SetStateAction } from "react";
import { load, save } from "../storage/local";

export function usePersist<T>(key: string, initial: T): [T, Dispatch<SetStateAction<T>>] {
  const [v, setV] = useState<T>(() => load(key, initial));
  useEffect(() => { save(key, v); }, [key, v]);
  return [v, setV];
}
