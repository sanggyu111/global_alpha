"use client";

// 클라이언트 컴포넌트인 이유: 확인 창(confirm)과 처리 중 버튼 잠금. 취소 자체는 Server Action 이 한다.

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { cancelReservation, type RefundFault } from "@/app/actions";

// 데모: 모의 PG 환불 장애 주입 (T20). 값은 모의 PG 가 이 결제에 기억하므로 자동 재시도에도 이어진다.
const REFUND_FAULTS: { label: string; fault: RefundFault | null }[] = [
  { label: "정상 환불", fault: null },
  { label: "PG 일시 장애 1회 → 약 1분 뒤 자동 재시도로 환불", fault: { failTimes: 1, failType: "UNAVAILABLE" } },
  {
    label: "PG 장애 5회 지속 → 자동 재시도 소진, 약 15분 뒤 운영자 확인 대상",
    fault: { failTimes: 5, failType: "UNAVAILABLE" },
  },
  { label: "PG 거절(4xx) → 즉시 운영자 확인 대상", fault: { failTimes: 1, failType: "REJECTED" } },
];

export function CancelButton({
  reservationId,
  confirmMessage,
  hasRefund,
}: {
  reservationId: number;
  confirmMessage: string;
  hasRefund: boolean;
}) {
  const router = useRouter();
  const [faultIndex, setFaultIndex] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();

  function cancel() {
    if (!window.confirm(confirmMessage)) return;
    setError(null);
    startTransition(async () => {
      const result = await cancelReservation(reservationId, hasRefund ? REFUND_FAULTS[faultIndex].fault : null);
      if (!result.ok) {
        setError(result.error.message);
      }
      router.refresh(); // 서버 컴포넌트가 취소된 상태·환불 정보로 다시 그려진다
    });
  }

  return (
    <>
      {hasRefund && (
        <fieldset>
          <legend>데모: 모의 PG 환불 결과</legend>
          <select value={faultIndex} onChange={(e) => setFaultIndex(Number(e.target.value))}>
            {REFUND_FAULTS.map((f, i) => (
              <option key={f.label} value={i}>
                {f.label}
              </option>
            ))}
          </select>
          <p className="muted">
            운영자 확인 대상은 <Link href="/admin/issues">관리자 › 확인 필요</Link> 에서 보고 수동 재시도할 수 있습니다 (장애
            횟수를 다 쓴 뒤라 수동 재시도는 성공).
          </p>
        </fieldset>
      )}
      <button type="button" onClick={cancel} disabled={pending}>
        {pending ? "취소 중…" : "예약 취소"}
      </button>
      {error && <p className="error">{error}</p>}
    </>
  );
}
