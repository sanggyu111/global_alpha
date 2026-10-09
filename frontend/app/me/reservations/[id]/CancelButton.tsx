"use client";

// 클라이언트 컴포넌트인 이유: 확인 창(confirm)과 처리 중 버튼 잠금. 취소 자체는 Server Action 이 한다.

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { cancelReservation } from "@/app/actions";

export function CancelButton({ reservationId, confirmMessage }: { reservationId: number; confirmMessage: string }) {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();

  function cancel() {
    if (!window.confirm(confirmMessage)) return;
    setError(null);
    startTransition(async () => {
      const result = await cancelReservation(reservationId);
      if (!result.ok) {
        setError(result.error.message);
      }
      router.refresh(); // 서버 컴포넌트가 취소된 상태·환불 정보로 다시 그려진다
    });
  }

  return (
    <>
      <button type="button" onClick={cancel} disabled={pending}>
        {pending ? "취소 중…" : "예약 취소"}
      </button>
      {error && <p className="error">{error}</p>}
    </>
  );
}
