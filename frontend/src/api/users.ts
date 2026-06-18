/* 사용자 디렉토리 API — 공유 대상(@) 선택용. active 사용자 emp+name. */
import { req } from "./http";

export interface DirectoryUser {
  emp: string;
  name: string;
}

export const UserApi = {
  directory: () => req<DirectoryUser[]>("/users/directory"),
};
