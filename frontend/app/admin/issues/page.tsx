import { recountInventory } from "@/app/actions";
import { api } from "@/lib/api";
import { dateTime, won } from "@/lib/format";
import type { InventoryMismatch, PaymentCancelIssue } from "@/lib/types";

/**
 * 운영자 확인 대상 (FR-UI-5, README Q6).
 * ① 자동 환불(PG 취소)이 재시도를 다 쓰고도 실패했거나 PG 가 거절한 건 — 고객 돈이 아직 돌아가지 않았다
 * ② 재고 불일치 — booked_count 가 그 날짜를 쓰는 활성 예약 수와 다른 날짜
 */
export default async function AdminIssuesPage() {
  const [cancels, mismatches] = await Promise.all([
    api<PaymentCancelIssue[]>("/api/admin/payment-cancels?status=MANUAL_REVIEW"),
    api<InventoryMismatch[]>("/api/admin/inventory/mismatches"),
  ]);

  return (
    <>
      <h1>확인 필요</h1>

      <h2>자동 환불 실패 (MANUAL_REVIEW)</h2>
      {!cancels.ok ? (
        <p className="error">{cancels.error.message}</p>
      ) : cancels.data.length === 0 ? (
        <p>없음</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>예약</th>
              <th>사유</th>
              <th>금액</th>
              <th>시도</th>
              <th>마지막 오류</th>
              <th>PG</th>
            </tr>
          </thead>
          <tbody>
            {cancels.data.map((c) => (
              <tr key={c.id}>
                <td>
                  {c.reservationNo}
                  <div className="muted">{dateTime(c.createdAt)}</div>
                </td>
                <td>{c.reason === "COMPENSATION" ? "확정 실패 보상 취소" : "사용자 취소 환불"}</td>
                <td>{won(c.cancelAmount)}</td>
                <td>{c.attemptCount}회</td>
                <td className="muted">{c.lastError}</td>
                <td className="muted">
                  {c.pgOrderId}
                  <div>{c.pgTid}</div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h2>재고 불일치 (오늘부터 1년)</h2>
      <p className="muted">
        정답은 예약 테이블입니다. 원인을 확인한 뒤 재계산하면 해당 날짜의 예약 수를 활성 예약(결제 대기·확정·이용
        완료) 수로 맞춥니다.
      </p>
      {!mismatches.ok ? (
        <p className="error">{mismatches.error.message}</p>
      ) : mismatches.data.length === 0 ? (
        <p>없음</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>객실 타입</th>
              <th>날짜</th>
              <th>재고</th>
              <th>booked_count</th>
              <th>실제 활성 예약</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {mismatches.data.map((m) => (
              <tr key={`${m.roomTypeId}-${m.stayDate}`}>
                <td>{m.roomTypeId}</td>
                <td>{m.stayDate}</td>
                <td>{m.totalCount}</td>
                <td className="error">{m.bookedCount}</td>
                <td>{m.expectedBookedCount}</td>
                <td>
                  <form action={recountInventory}>
                    <input type="hidden" name="roomTypeId" value={m.roomTypeId} />
                    <input type="hidden" name="stayDate" value={m.stayDate} />
                    <button type="submit">재계산</button>
                  </form>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
