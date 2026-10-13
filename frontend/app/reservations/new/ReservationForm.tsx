"use client";

// 클라이언트 컴포넌트인 이유: 제출 중 버튼 잠금과 실패 메시지 표시(useActionState)가 필요해서.
// 데이터 저장 자체는 Server Action(createReservation)이 서버에서 한다.

import { useActionState } from "react";

import { createReservation, type FormState } from "@/app/actions";

export function ReservationForm({ hidden }: { hidden: Record<string, string> }) {
  const [state, formAction, pending] = useActionState<FormState, FormData>(createReservation, { error: null });

  return (
    <form action={formAction} className="card form-stack">
      {Object.entries(hidden).map(([name, value]) => (
        <input key={name} type="hidden" name={name} value={value} />
      ))}
      <label>
        예약자 이름
        <input name="guestName" required maxLength={50} autoComplete="name" />
      </label>
      <label>
        연락처
        <input name="guestPhone" required maxLength={30} placeholder="010-0000-0000" type="tel" autoComplete="tel" />
      </label>
      {state.error && <p className="notice notice-error">{state.error}</p>}
      <button type="submit" className="btn-block" disabled={pending}>
        {pending ? "예약 중…" : "예약하고 결제하기"}
      </button>
    </form>
  );
}
