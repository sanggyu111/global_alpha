import type { Metadata } from "next";
import Link from "next/link";

import { setUser } from "@/app/actions";
import { getUserId } from "@/lib/user";

import "./globals.css";

export const metadata: Metadata = {
  title: "STAYPOINT",
  description: "호텔 예약 데모",
};

export default async function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  const userId = await getUserId();
  return (
    <html lang="ko">
      <body>
        <header className="header">
          <div className="header-inner">
            <Link href="/" className="logo">
              STAYPOINT
            </Link>
            <nav>
              <Link href="/">숙소 찾기</Link>
              <Link href="/me/reservations">내 예약</Link>
              <Link href="/admin/reservations">관리자</Link>
            </nav>
            {/* 인증 대신 사용자 ID 를 쿠키로 바꿔 끼운다 (과제: X-User-Id 로 충분). 자주 쓰지 않으니 접어 둔다 */}
            <details className="user-menu">
              <summary>{userId} ▾</summary>
              <form action={setUser}>
                <label>
                  사용자 ID (데모용, 인증 없음)
                  <input name="userId" defaultValue={userId} maxLength={50} />
                </label>
                <button type="submit">변경</button>
              </form>
            </details>
          </div>
        </header>
        <main className="main">{children}</main>
      </body>
    </html>
  );
}
