import Link from "next/link";

export default function AdminLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <>
      <p className="muted">
        관리자 (인증 없음 — 과제 범위 밖) · <Link href="/admin/reservations">예약 목록</Link> ·{" "}
        <Link href="/admin/inventory">재고 · 요금</Link> · <Link href="/admin/issues">확인 필요</Link>
      </p>
      {children}
    </>
  );
}
