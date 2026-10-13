import Link from "next/link";

import { StatusBadge } from "@/app/StatusBadge";
import { api } from "@/lib/api";
import { dateTime, won } from "@/lib/format";
import type { ReservationSummary } from "@/lib/types";
import { getUserId } from "@/lib/user";

export default async function MyReservationsPage() {
  const userId = await getUserId();
  // 예약 상태는 결제·만료·취소로 계속 바뀌므로 캐시하지 않는다
  const result = await api<ReservationSummary[]>("/api/reservations/me", { userId });

  return (
    <>
      <div className="section-title">
        <h1>내 예약</h1>
        <span className="muted">{userId}</span>
      </div>
      {!result.ok ? (
        <p className="notice notice-error">{result.error.message}</p>
      ) : result.data.length === 0 ? (
        <p className="card empty">예약이 없습니다.</p>
      ) : (
        <div className="list">
          {result.data.map((r) => (
            // 카드 전체가 상세로 가는 링크. 결제 대기 건은 상세에서 결제로 이어진다
            <Link key={r.id} href={`/me/reservations/${r.id}`} className="card card-link">
              <div className="item-head">
                <div>
                  <h3>{r.propertyName}</h3>
                  <div className="muted">{r.roomTypeName}</div>
                </div>
                <StatusBadge status={r.status} />
              </div>
              <div className="room-foot">
                <div>
                  {r.checkIn} ~ {r.checkOut}
                  <div className="muted">
                    {r.reservationNo} · 예약 {dateTime(r.createdAt)}
                  </div>
                </div>
                <div className="price">{won(r.totalAmount)}</div>
              </div>
            </Link>
          ))}
        </div>
      )}
    </>
  );
}
