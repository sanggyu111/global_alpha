import Link from "next/link";

import { StatusBadge } from "@/app/StatusBadge";
import { api } from "@/lib/api";
import {
  CANCEL_REASON_LABEL,
  PAYMENT_STATUS_LABEL,
  REFUND_POLICY_TEXT,
  REFUND_PROBLEM_NOTICE,
  REFUND_STATUS_LABEL,
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
        <p className="notice notice-error">{result.error.message}</p>
        <Link href="/me/reservations" className="back">
          ← 내 예약
        </Link>
      </>
    );
  }
  const r = result.data;
  const cancellable = r.status === "PENDING" || r.status === "CONFIRMED";
  // 환불이 아직 끝나지 않은 상태만 강조한다 — 완료·환불 없음까지 빨간색이면 강조가 의미를 잃는다
  const refundProblem = r.refundStatus ? REFUND_PROBLEM_NOTICE[r.refundStatus] : undefined;

  return (
    <>
      <Link href="/me/reservations" className="back">
        ← 내 예약
      </Link>
      <h1>예약 상세</h1>

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
          {r.confirmedAt && (
            <>
              <dt>확정</dt>
              <dd>{dateTime(r.confirmedAt)}</dd>
            </>
          )}
        </dl>
        <div className="total">
          <span>예약 금액</span>
          <strong>{won(r.totalAmount)}</strong>
        </div>
      </div>

      {r.status === "PENDING" && (
        <div className="card">
          <p className="notice notice-warn">{dateTime(r.holdExpiresAt)} 까지 결제하지 않으면 예약이 취소됩니다.</p>
          <Link href={`/reservations/${r.id}/pay`} className="btn btn-block">
            결제하기
          </Link>
        </div>
      )}

      {r.payment && (
        <div className="card">
          <h2>결제</h2>
          <dl className="kv">
            <dt>상태</dt>
            <dd>{PAYMENT_STATUS_LABEL[r.payment.status] ?? r.payment.status}</dd>
            <dt>금액</dt>
            <dd>{won(r.payment.amount)}</dd>
            {r.payment.canceledAmount > 0 && (
              <>
                <dt>취소(환불)된 금액</dt>
                <dd>{won(r.payment.canceledAmount)}</dd>
              </>
            )}
          </dl>
          {r.payment.failReason && <p className="muted mt-12">{r.payment.failReason}</p>}
        </div>
      )}

      {r.status === "CANCELED" && (
        <div className="card">
          <h2>취소 · 환불</h2>
          <dl className="kv">
            <dt>취소</dt>
            <dd>
              {dateTime(r.canceledAt)}
              <div className="muted">{CANCEL_REASON_LABEL[r.cancelReason ?? ""] ?? r.cancelReason}</div>
            </dd>
            <dt>환불 금액</dt>
            <dd className="strong">{won(r.refundAmount ?? 0)}</dd>
            {r.refundStatus && (
              <>
                <dt>환불 상태</dt>
                <dd className={refundProblem ? "error strong" : undefined}>
                  {REFUND_STATUS_LABEL[r.refundStatus] ?? r.refundStatus}
                </dd>
              </>
            )}
          </dl>
          {refundProblem && <p className="notice notice-error mt-12">{refundProblem}</p>}
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
              </strong>
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
