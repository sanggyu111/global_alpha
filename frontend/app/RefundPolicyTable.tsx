import { REFUND_TIERS, addDays, seoulDate, shortDate, won } from "@/lib/format";

/**
 * 결제 화면의 환불 규정 (T24): 이 예약의 체크인 날짜로 구간별 취소 날짜 범위와 환불액을 계산해 보여준다.
 * 안내용이며 실제 환불액은 취소할 때 백엔드 RefundPolicy 가 계산한다 (원 미만 내림은 같게 맞춤).
 * T28: 좁은 화면에서도 한눈에 보이도록 4열 표 대신 구간별 한 줄 목록.
 */
export function RefundPolicyTable({ checkIn, totalAmount }: { checkIn: string; totalAmount: number }) {
  const today = seoulDate();
  const rows = REFUND_TIERS.map((tier, i) => {
    // 구간 = 체크인 − maxDays ~ 체크인 − minDays (D 가 클수록 이른 날짜)
    const upper = i === 0 ? null : REFUND_TIERS[i - 1].minDaysBefore - 1; // 이 구간의 최대 D
    const from = upper === null ? null : addDays(checkIn, -upper);
    const to = Number.isFinite(tier.minDaysBefore) ? addDays(checkIn, -tier.minDaysBefore) : null;
    const range =
      from === null
        ? `${shortDate(to!)} 까지`
        : to === null
          ? `${shortDate(from)} 부터 (당일·이후)`
          : `${shortDate(from)} ~ ${shortDate(to)}`;
    const past = to !== null && to < today; // 이미 지난 구간
    return { ...tier, range, past, amount: Math.floor((totalAmount * tier.percent) / 100) };
  });

  return (
    <div className="card">
      <h2>환불 규정</h2>
      <p className="muted">취소한 날짜(한국 시간)가 체크인({shortDate(checkIn)})으로부터 며칠 전인지로 정해집니다.</p>
      <ul className="rows">
        {rows.map((r) => (
          <li key={r.label} className={r.past ? "muted" : undefined}>
            <div>
              {r.label}
              <div className="muted">
                {r.range}
                {r.past && " · 지남"}
              </div>
            </div>
            <div className="price">
              {r.percent}%<div className="muted">{won(r.amount)}</div>
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}
