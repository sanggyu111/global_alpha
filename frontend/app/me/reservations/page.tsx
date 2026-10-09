import Link from "next/link";

import { api } from "@/lib/api";
import { STATUS_LABEL, dateTime, won } from "@/lib/format";
import type { ReservationSummary } from "@/lib/types";
import { getUserId } from "@/lib/user";

export default async function MyReservationsPage() {
  const userId = await getUserId();
  // 예약 상태는 결제·만료·취소로 계속 바뀌므로 캐시하지 않는다
  const result = await api<ReservationSummary[]>("/api/reservations/me", { userId });

  return (
    <>
      <h1>내 예약</h1>
      <p className="muted">사용자: {userId}</p>
      {!result.ok ? (
        <p className="error">{result.error.message}</p>
      ) : result.data.length === 0 ? (
        <p>예약이 없습니다.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>예약번호</th>
              <th>숙소 / 객실</th>
              <th>일정</th>
              <th>금액</th>
              <th>상태</th>
              <th>예약일</th>
            </tr>
          </thead>
          <tbody>
            {result.data.map((r) => (
              <tr key={r.id}>
                <td>
                  <Link href={`/me/reservations/${r.id}`}>{r.reservationNo}</Link>
                </td>
                <td>
                  {r.propertyName}
                  <div className="muted">{r.roomTypeName}</div>
                </td>
                <td>
                  {r.checkIn} ~ {r.checkOut}
                </td>
                <td>{won(r.totalAmount)}</td>
                <td>
                  {STATUS_LABEL[r.status]}
                  {r.status === "PENDING" && (
                    <div>
                      <Link href={`/reservations/${r.id}/pay`}>결제하기</Link>
                    </div>
                  )}
                </td>
                <td className="muted">{dateTime(r.createdAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
