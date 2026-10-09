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
          <Link href="/" className="logo">
            STAYPOINT
          </Link>
          <nav>
            <Link href="/">숙소</Link>
            <Link href="/me/reservations">내 예약</Link>
            <Link href="/admin/reservations">관리자</Link>
          </nav>
          {/* 인증 대신 사용자 ID 를 쿠키로 바꿔 끼운다 (과제: X-User-Id 로 충분) */}
          <form action={setUser} className="user-form">
            <label>
              사용자 ID <input name="userId" defaultValue={userId} maxLength={50} size={12} />
            </label>
            <button type="submit">변경</button>
          </form>
        </header>
        <main className="main">{children}</main>
      </body>
    </html>
  );
}
