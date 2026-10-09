"use client";

// 클라이언트 컴포넌트인 이유: 제출 중 버튼 잠금과 실패 메시지 표시(useActionState)가 필요해서.
// 데이터 저장 자체는 Server Action(createReservation)이 서버에서 한다.

import { useActionState } from "react";

import { createReservation, type FormState } from "@/app/actions";

export function ReservationForm({ hidden }: { hidden: Record<string, string> }) {
  const [state, formAction, pending] = useActionState<FormState, FormData>(createReservation, { error: null });

  return (
    <form action={formAction} className="card">
      {Object.entries(hidden).map(([name, value]) => (
        <input key={name} type="hidden" name={name} value={value} />
      ))}
      <p>
        <label>
          예약자 이름
          <input name="guestName" required maxLength={50} />
        </label>
      </p>
      <p>
        <label>
          연락처
          <input name="guestPhone" required maxLength={30} placeholder="010-0000-0000" />
        </label>
      </p>
      {state.error && <p className="error">{state.error}</p>}
      <button type="submit" disabled={pending}>
        {pending ? "예약 중…" : "예약하고 결제하기"}
      </button>
    </form>
  );
}
