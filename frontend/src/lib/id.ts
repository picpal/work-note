let counter = 1000;
export const newId = (): string =>
  "u" + Date.now().toString(36) + (++counter).toString(36) + Math.floor(Math.random() * 1296).toString(36);
