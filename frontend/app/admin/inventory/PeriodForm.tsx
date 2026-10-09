"use client";

// 클라이언트 컴포넌트인 이유: 제출 중 잠금과 거부 사유(예: 예약 수보다 적은 재고 날짜) 표시.

import { useActionState } from "react";

import { type FormState, setInventory, setRates } from "@/app/actions";

type Props = { kind: "inventory" | "rates"; roomTypeId: string; from: string; to: string };

export function PeriodForm({ kind, roomTypeId, from, to }: Props) {
  const action = kind === "inventory" ? setInventory : setRates;
  const [state, formAction, pending] = useActionState<FormState, FormData>(action, { error: null });

  return (
    <form action={formAction} className="inline">
      <input type="hidden" name="roomTypeId" value={roomTypeId} />
      <label>
        from
        <input type="date" name="from" defaultValue={from} required />
      </label>
      <label>
        to
        <input type="date" name="to" defaultValue={to} required />
      </label>
      {kind === "inventory" ? (
        <label>
          재고(실)
          <input type="number" name="totalCount" min={0} required />
        </label>
      ) : (
        <label>
          1박 요금(원)
          <input type="number" name="price" min={0} step={1000} required />
        </label>
      )}
      <button type="submit" disabled={pending}>
        {kind === "inventory" ? "재고 적용" : "요금 적용"}
      </button>
      {state.error && <span className="error">{state.error}</span>}
    </form>
  );
}
