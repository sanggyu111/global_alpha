"use client";

// 클라이언트 컴포넌트인 이유 (README Q5):
//  - 결제 멱등 키를 브라우저(sessionStorage)에 보관해야 새로고침·재클릭에도 같은 키를 보낼 수 있다
//  - 결제 중 버튼 잠금(연타 방지)과 진행 상태 표시
//  - PROCESSING 이면 잠시 뒤 상태를 다시 읽는다 (router.refresh → 서버 컴포넌트가 새 상태로 다시 그려짐)

import { useRouter } from "next/navigation";
import { useEffect, useState, useTransition } from "react";

import { payReservation } from "@/app/actions";
import type { PaymentResponse } from "@/lib/types";

const REFRESH_INTERVAL_MS = 2000;
const MAX_REFRESHES = 15;

export function PayPanel({ reservationId }: { reservationId: number }) {
  const router = useRouter();
  const storageKey = `pay-key:${reservationId}`;
  const [idempotencyKey, setIdempotencyKey] = useState<string | null>(null);
  const [failRate, setFailRate] = useState("0");
  const [delayMs, setDelayMs] = useState("300");
  const [result, setResult] = useState<PaymentResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [refreshes, setRefreshes] = useState(0);
  const [pending, startTransition] = useTransition();

  // 결제 키: 이 예약의 결제 시도 하나에 하나. 이미 있으면 재사용 (타임아웃 후 재시도 → 같은 결제)
  useEffect(() => {
    const saved = sessionStorage.getItem(storageKey);
    if (saved) {
      setIdempotencyKey(saved);
    } else {
      const created = crypto.randomUUID();
      sessionStorage.setItem(storageKey, created);
      setIdempotencyKey(created);
    }
  }, [storageKey]);

  // 결과를 모르는 동안(PROCESSING) 주기적으로 상태를 다시 읽는다
  useEffect(() => {
    if (result?.result !== "PROCESSING" || refreshes >= MAX_REFRESHES) return;
    const timer = setTimeout(() => {
      setRefreshes((n) => n + 1);
      router.refresh();
    }, REFRESH_INTERVAL_MS);
    return () => clearTimeout(timer);
  }, [result, refreshes, router]);

  function pay() {
    if (!idempotencyKey) return;
    setError(null);
    startTransition(async () => {
      const response = await payReservation(reservationId, idempotencyKey, Number(failRate), Number(delayMs));
      if (!response.ok) {
        setError(response.error.message);
        router.refresh();
        return;
      }
      setResult(response.data);
      setRefreshes(0);
      if (response.data.result === "FAILED") {
        // 같은 키로 다시 보내면 백엔드가 같은 "실패" 를 돌려주므로, 다시 결제하려면 새 키가 필요하다
        const next = crypto.randomUUID();
        sessionStorage.setItem(storageKey, next);
        setIdempotencyKey(next);
      }
      router.refresh();
    });
  }

  return (
    <div className="card">
      <fieldset>
        <legend>데모: 모의 PG 장애 주입</legend>
        <label>
          실패율
          <select value={failRate} onChange={(e) => setFailRate(e.target.value)}>
            <option value="0">0% (항상 승인)</option>
            <option value="0.5">50%</option>
            <option value="1">100% (항상 거절)</option>
          </select>
        </label>{" "}
        <label>
          응답 지연
          <select value={delayMs} onChange={(e) => setDelayMs(e.target.value)}>
            <option value="300">0.3초</option>
            <option value="2000">2초</option>
            <option value="5000">5초 (타임아웃 → 처리 중)</option>
          </select>
        </label>
      </fieldset>
      <p>
        <button type="button" onClick={pay} disabled={pending || !idempotencyKey}>
          {pending ? "결제 중…" : "결제하기"}
        </button>
      </p>
      {result?.result === "FAILED" && <p className="error">결제가 거절되었습니다. 다시 시도할 수 있습니다.</p>}
      {result?.result === "PROCESSING" && (
        <p className="muted">
          결제 결과를 확인하는 중입니다… (버튼을 다시 눌러도 같은 결제로 처리되어 이중 결제되지 않습니다)
        </p>
      )}
      {result?.result === "COMPENSATED" && (
        <p className="error">결제는 승인됐지만 예약을 확정할 수 없어(선점 만료 등) 결제를 자동 취소했습니다.</p>
      )}
      {error && <p className="error">{error}</p>}
      <p className="muted">결제 키: {idempotencyKey ?? "…"}</p>
    </div>
  );
}
