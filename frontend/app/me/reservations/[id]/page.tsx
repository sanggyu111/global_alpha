import Link from "next/link";

import { api } from "@/lib/api";
import {
  CANCEL_REASON_LABEL,
  REFUND_POLICY_TEXT,
  REFUND_PROBLEM_NOTICE,
  REFUND_STATUS_LABEL,
  STATUS_LABEL,
  dateTime,
  won,
} from "@/lib/format";
import type { ReservationDetail } from "@/lib/types";
import { getUserId } from "@/lib/user";

import { CancelButton } from "./CancelButton";

export default async function MyReservationPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  // 환불 예정액은 "오늘" 기준으로 달라지고 상태도 바뀌므로 매번 새로 읽는다
  const result = await api<ReservationDetail>(`/api/reservations/${id}`, { userId: await getUserId() });
  if (!result.ok) {
    return (
      <>
        <p className="error">{result.error.message}</p>
        <Link href="/me/reservations">← 내 예약</Link>
      </>
    );
  }
  const r = result.data;
  const cancellable = r.status === "PENDING" || r.status === "CONFIRMED";

  return (
    <>
      <p>
        <Link href="/me/reservations">← 내 예약</Link>
      </p>
      <h1>예약 {r.reservationNo}</h1>
      <div className="card">
        <p>
          상태 <strong>{STATUS_LABEL[r.status]}</strong>
        </p>
        <div>
          {r.propertyName} / {r.roomTypeName}
        </div>
        <div>
          {r.checkIn} ~ {r.checkOut} ({r.nights}박) · {r.guestCount}명 · {r.guestName}
        </div>
        <div>
          예약 금액 <strong>{won(r.totalAmount)}</strong>
        </div>
        {r.status === "PENDING" && (
          <div className="muted">
            결제 기한 {dateTime(r.holdExpiresAt)} · <Link href={`/reservations/${r.id}/pay`}>결제하기</Link>
          </div>
        )}
        {r.confirmedAt && <div className="muted">확정 {dateTime(r.confirmedAt)}</div>}
      </div>

      {r.payment && (
        <div className="card">
          <h2>결제</h2>
          <div>
            상태 {r.payment.status} · 금액 {won(r.payment.amount)}
            {r.payment.canceledAmount > 0 && <> · 취소(환불)된 금액 {won(r.payment.canceledAmount)}</>}
          </div>
          {r.payment.failReason && <div className="muted">{r.payment.failReason}</div>}
        </div>
      )}

      {r.status === "CANCELED" && (
        <div className="card">
          <h2>취소</h2>
          <div>
            {dateTime(r.canceledAt)} · {CANCEL_REASON_LABEL[r.cancelReason ?? ""] ?? r.cancelReason}
          </div>
          <div>
            환불 금액 <strong>{won(r.refundAmount ?? 0)}</strong>
            {r.refundStatus && (
              <>
                {" · "}
                {/* 환불이 아직 끝나지 않은 상태만 강조한다 — 완료·환불 없음까지 빨간색이면 강조가 의미를 잃는다 */}
                <span className={REFUND_PROBLEM_NOTICE[r.refundStatus] ? "error strong" : undefined}>
                  {REFUND_STATUS_LABEL[r.refundStatus] ?? r.refundStatus}
                </span>
              </>
            )}
          </div>
          {r.refundStatus && REFUND_PROBLEM_NOTICE[r.refundStatus] && (
            <p className="error">{REFUND_PROBLEM_NOTICE[r.refundStatus]}</p>
          )}
        </div>
      )}

      {cancellable && (
        <div className="card">
          <h2>예약 취소</h2>
          {r.refundEstimate ? (
            <p>
              지금 취소하면 체크인 {r.refundEstimate.daysBeforeCheckIn}일 전이라{" "}
              <strong>
                {r.refundEstimate.percent}% ({won(r.refundEstimate.amount)})
              </strong>{" "}
              를 환불받습니다.
            </p>
          ) : (
            <p>결제 전 예약이라 환불할 금액 없이 객실 선점만 해제됩니다.</p>
          )}
          <p className="muted">{REFUND_POLICY_TEXT}</p>
          <CancelButton
            reservationId={r.id}
            hasRefund={(r.refundEstimate?.amount ?? 0) > 0}
            confirmMessage={
              r.refundEstimate
                ? `예약을 취소하고 ${won(r.refundEstimate.amount)}을 환불받을까요?`
                : "예약을 취소할까요?"
            }
          />
        </div>
      )}
    </>
  );
}
