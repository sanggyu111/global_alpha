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

export const STATUS_LABEL: Record<string, string> = {
  PENDING: "결제 대기",
  CONFIRMED: "예약 확정",
  COMPLETED: "이용 완료",
  CANCELED: "취소됨",
};
