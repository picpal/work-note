export const SPLIT_THRESHOLD = 900;
export function splitDirection(width: number): "row" | "column" {
  return width >= SPLIT_THRESHOLD ? "row" : "column";
}
