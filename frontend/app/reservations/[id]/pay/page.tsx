import Link from "next/link";

import { api } from "@/lib/api";
import { STATUS_LABEL, dateTime, won } from "@/lib/format";
import type { ReservationDetail } from "@/lib/types";
import { getUserId } from "@/lib/user";

import { PayPanel } from "./PayPanel";

export default async function PayPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  // 예약·결제 상태는 매번 새로 읽는다 (캐시 없음). 결제 후 router.refresh() 로 이 컴포넌트가 다시 그려진다.
  const result = await api<ReservationDetail>(`/api/reservations/${id}`, { userId: await getUserId() });
  if (!result.ok) {
    return <p className="error">{result.error.message}</p>;
  }
  const r = result.data;

  return (
    <>
      <h1>결제</h1>
      <div className="card">
        <div>
          예약번호 <strong>{r.reservationNo}</strong> · {STATUS_LABEL[r.status]}
        </div>
        <div>
          {r.propertyName} / {r.roomTypeName}
        </div>
        <div>
          {r.checkIn} ~ {r.checkOut} ({r.nights}박) · {r.guestCount}명 · {r.guestName}
        </div>
        <div>
          결제 금액 <strong>{won(r.totalAmount)}</strong>
        </div>
      </div>

      {r.status === "PENDING" && (
        <>
          <p className="muted">선점 만료: {dateTime(r.holdExpiresAt)} — 이 시각이 지나면 결제할 수 없습니다.</p>
          {r.payment?.status === "FAILED" && <p className="error">직전 결제 실패: {r.payment.failReason}</p>}
          <PayPanel reservationId={r.id} />
        </>
      )}
      {r.status === "CONFIRMED" && (
        <p className="success">
          예약이 확정되었습니다. (확정 {dateTime(r.confirmedAt)})
        </p>
      )}
      {r.status === "CANCELED" && (
        <p className="error">
          취소된 예약입니다 ({r.cancelReason === "HOLD_EXPIRED" ? "선점 시간 만료" : r.cancelReason}).
          {r.payment?.status === "CANCELED" && " 결제된 금액은 자동으로 취소(환불)되었습니다."}
        </p>
      )}
      <p>
        <Link href="/me/reservations">내 예약 목록</Link>
      </p>
    </>
  );
}
