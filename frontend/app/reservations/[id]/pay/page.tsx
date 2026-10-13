import Link from "next/link";

import { api } from "@/lib/api";
import { dateTime, won } from "@/lib/format";
import type { ReservationDetail } from "@/lib/types";
import { getUserId } from "@/lib/user";

import { RefundPolicyTable } from "@/app/RefundPolicyTable";
import { StatusBadge } from "@/app/StatusBadge";

import { PayPanel } from "./PayPanel";

export default async function PayPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  // 예약·결제 상태는 매번 새로 읽는다 (캐시 없음). 결제 후 router.refresh() 로 이 컴포넌트가 다시 그려진다.
  const result = await api<ReservationDetail>(`/api/reservations/${id}`, { userId: await getUserId() });
  if (!result.ok) {
    return <p className="notice notice-error">{result.error.message}</p>;
  }
  const r = result.data;

  return (
    <>
      <h1>결제</h1>
      <div className="card">
        <div className="item-head">
          <div>
            <h2>{r.propertyName}</h2>
            <div className="muted">{r.roomTypeName}</div>
          </div>
          <StatusBadge status={r.status} />
        </div>
        <dl className="kv mt-12">
          <dt>예약번호</dt>
          <dd>{r.reservationNo}</dd>
          <dt>일정</dt>
          <dd>
            {r.checkIn} ~ {r.checkOut} ({r.nights}박)
          </dd>
          <dt>인원 · 예약자</dt>
          <dd>
            {r.guestCount}명 · {r.guestName}
          </dd>
        </dl>
        <div className="total">
          <span>결제 금액</span>
          <strong>{won(r.totalAmount)}</strong>
        </div>
      </div>

      {r.status === "PENDING" && (
        <>
          <p className="notice notice-warn">
            {dateTime(r.holdExpiresAt)} 까지 결제하지 않으면 예약이 취소됩니다.
          </p>
          {r.payment?.status === "FAILED" && (
            <p className="notice notice-error">직전 결제 실패: {r.payment.failReason}</p>
          )}
          <PayPanel reservationId={r.id} />
          {/* 결제 전에 환불 규정을 보여준다 (T24) */}
          <RefundPolicyTable checkIn={r.checkIn} totalAmount={r.totalAmount} />
        </>
      )}
      {r.status === "CONFIRMED" && (
        <p className="notice notice-success">예약이 확정되었습니다. (확정 {dateTime(r.confirmedAt)})</p>
      )}
      {r.status === "CANCELED" && (
        <p className="notice notice-error">
          취소된 예약입니다 ({r.cancelReason === "HOLD_EXPIRED" ? "선점 시간 만료" : r.cancelReason}).
          {r.payment?.status === "CANCELED" && " 결제된 금액은 자동으로 취소(환불)되었습니다."}
        </p>
      )}
      <Link href={`/me/reservations/${r.id}`} className="btn btn-secondary btn-block">
        예약 상세 보기
      </Link>
    </>
  );
}
