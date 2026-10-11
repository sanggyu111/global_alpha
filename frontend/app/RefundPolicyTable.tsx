import { REFUND_TIERS, addDays, seoulDate, won } from "@/lib/format";

/**
 * 결제 화면의 환불 규정 (T24): 이 예약의 체크인 날짜로 구간별 취소 날짜 범위와 환불액을 계산해 보여준다.
 * 안내용이며 실제 환불액은 취소할 때 백엔드 RefundPolicy 가 계산한다 (원 미만 내림은 같게 맞춤).
 */
export function RefundPolicyTable({ checkIn, totalAmount }: { checkIn: string; totalAmount: number }) {
  const today = seoulDate();
  const rows = REFUND_TIERS.map((tier, i) => {
    // 구간 = 체크인 − maxDays ~ 체크인 − minDays (D 가 클수록 이른 날짜)
    const upper = i === 0 ? null : REFUND_TIERS[i - 1].minDaysBefore - 1; // 이 구간의 최대 D
    const from = upper === null ? null : addDays(checkIn, -upper);
    const to = Number.isFinite(tier.minDaysBefore) ? addDays(checkIn, -tier.minDaysBefore) : null;
    const range = from === null ? `${to} 까지` : to === null ? `${from} 부터 (체크인 당일·이후)` : `${from} ~ ${to}`;
    const past = to !== null && to < today; // 이미 지난 구간
    return { ...tier, range, past, amount: Math.floor((totalAmount * tier.percent) / 100) };
  });

  return (
    <div className="card">
      <h2>환불 규정</h2>
      <p className="muted">취소한 날짜(한국 시간)가 체크인 {checkIn} 으로부터 며칠 전인지에 따라 환불율이 정해집니다.</p>
      <table>
        <thead>
          <tr>
            <th>취소 시점</th>
            <th>취소 날짜</th>
            <th>환불율</th>
            <th>환불액</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.label} className={r.past ? "muted" : undefined}>
              <td>{r.label}</td>
              <td>
                {r.range}
                {r.past && " (지남)"}
              </td>
              <td>{r.percent}%</td>
              <td>{won(r.amount)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
