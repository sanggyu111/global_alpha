export function won(amount: number | null | undefined): string {
  return amount == null ? "-" : `${amount.toLocaleString("ko-KR")}원`;
}

/** ISO 시각을 한국 시간으로 표시. */
export function dateTime(iso: string | null | undefined): string {
  if (!iso) return "-";
  return new Date(iso).toLocaleString("ko-KR", { timeZone: "Asia/Seoul" });
}

/** yyyy-MM-dd (Asia/Seoul 기준) — 날짜 입력 기본값용. */
export function seoulDate(daysFromToday = 0): string {
  const d = new Date(Date.now() + daysFromToday * 24 * 60 * 60 * 1000);
  return d.toLocaleDateString("sv-SE", { timeZone: "Asia/Seoul" });
}

/** yyyy-MM-dd 날짜에 일수를 더한다 (시간대 영향 없이 날짜만 계산). */
export function addDays(date: string, days: number): string {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/**
 * 환불 규정 구간 (T24) — D = 체크인 − 취소일. minDaysBefore 이상이면 그 환불율.
 * 실제 환불액은 백엔드 RefundPolicy 가 계산한다. 이 값은 화면 안내용이라 백엔드와 같게 유지해야 한다.
 */
export const REFUND_TIERS = [
  { minDaysBefore: 7, percent: 100, label: "7일 전까지" },
  { minDaysBefore: 3, percent: 70, label: "3일 전" },
  { minDaysBefore: 1, percent: 50, label: "1일 전" },
  { minDaysBefore: Number.NEGATIVE_INFINITY, percent: 0, label: "당일·노쇼" },
] as const;

export const REFUND_POLICY_TEXT = `환불 규정: ${REFUND_TIERS.map((t) => `${t.label} ${t.percent}%`).join(" · ")}`;

export const REFUND_STATUS_LABEL: Record<string, string> = {
  NONE: "환불할 금액 없음",
  PENDING: "환불 진행 중 (실패 시 자동 재시도)",
  SUCCEEDED: "환불 완료",
  MANUAL_REVIEW: "운영자 확인 중 (자동 환불 실패)",
};

/** 환불이 끝나지 않은 상태 → 예약 상세에서 빨간색으로 강조하고 붙이는 안내 (T21) */
export const REFUND_PROBLEM_NOTICE: Record<string, string> = {
  PENDING: "PG 장애로 환불이 지연되고 있어 자동으로 다시 시도합니다.",
  MANUAL_REVIEW: "자동 환불에 실패해 운영자가 확인 중입니다.",
};

export const CANCEL_REASON_LABEL: Record<string, string> = {
  USER_CANCEL: "사용자 취소",
  HOLD_EXPIRED: "결제 시간(선점) 만료",
};

export const STATUS_LABEL: Record<string, string> = {
  PENDING: "결제 대기",
  CONFIRMED: "예약 확정",
  COMPLETED: "이용 완료",
  CANCELED: "취소됨",
};

/** 결제 상태 (T25). 예약 취소 직후 환불이 끝나기 전에는 결제가 아직 "승인" 이다 — 환불이 완료되면 "취소(환불)됨". */
export const PAYMENT_STATUS_LABEL: Record<string, string> = {
  READY: "결과 확인 중",
  APPROVED: "승인",
  FAILED: "실패",
  CANCELED: "취소(환불)됨",
};

/** yyyy-MM-dd → "10/20 (화)" — 좁은 화면용 짧은 날짜 (T28). 시간대 영향 없이 날짜만 읽는다. */
export function shortDate(date: string): string {
  const d = new Date(`${date}T00:00:00Z`);
  return `${d.getUTCMonth() + 1}/${d.getUTCDate()} (${"일월화수목금토"[d.getUTCDay()]})`;
}
