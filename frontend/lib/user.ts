import "server-only";

import { cookies } from "next/headers";

// 인증은 과제 범위 밖. 상단에서 입력한 사용자 ID 를 쿠키에 두고 서버가 X-User-Id 로 백엔드에 전달한다.
export const USER_COOKIE = "uid";
export const DEFAULT_USER = "demo-user";

export async function getUserId(): Promise<string> {
  const store = await cookies();
  return store.get(USER_COOKIE)?.value || DEFAULT_USER;
}
