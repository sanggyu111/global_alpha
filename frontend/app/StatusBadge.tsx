import { STATUS_LABEL } from "@/lib/format";

/** 예약 상태 배지 (T28) — 색은 globals.css 의 .badge-{상태} */
export function StatusBadge({ status }: { status: string }) {
  return <span className={`badge badge-${status}`}>{STATUS_LABEL[status] ?? status}</span>;
}
