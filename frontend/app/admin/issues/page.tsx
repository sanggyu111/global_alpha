import { deletePaymentCancelFailure, recountInventory, retryPaymentCancel } from "@/app/actions";
import { api } from "@/lib/api";
import { dateTime, seoulDate, won } from "@/lib/format";
import type { InventoryMismatch, PaymentCancelFailureRecord, PaymentCancelIssue } from "@/lib/types";

type Search = { from?: string; to?: string };

// 해결 여부 = 그 환불 건의 현재 상태 (T22, 따로 저장하지 않음)
const RESOLUTION: Record<PaymentCancelFailureRecord["cancelStatus"], { label: string; className?: string }> = {
  SUCCEEDED: { label: "해결됨 (환불 완료)", className: "success" },
  PENDING: { label: "자동 재시도 중" },
  MANUAL_REVIEW: { label: "미해결 (운영자 확인)", className: "error strong" },
};

/**
 * 운영자 확인 대상 (FR-UI-5, README Q6).
 * ① 자동 환불(PG 취소)이 재시도를 다 쓰고도 실패했거나 PG 가 거절한 건 — 고객 돈이 아직 돌아가지 않았다
 * ② 자동 환불 실패 기록 (T22) — 재시도로 결국 환불된 건도 실패했던 회차가 남는다. 기간은 URL searchParams (GET 폼)
 * ③ 재고 불일치 — booked_count(판매된 객실 수)가 그 날짜를 쓰는 활성 예약 수와 다른 날짜. 예약 테이블이 기준
 */
export default async function AdminIssuesPage({ searchParams }: { searchParams: Promise<Search> }) {
  const search = await searchParams;
  const from = search.from || seoulDate(-29);
  const to = search.to || seoulDate();
  const [cancels, retrying, failures, mismatches] = await Promise.all([
    api<PaymentCancelIssue[]>("/api/admin/payment-cancels?status=MANUAL_REVIEW"),
    api<PaymentCancelIssue[]>("/api/admin/payment-cancels?status=PENDING"),
    api<PaymentCancelFailureRecord[]>(`/api/admin/payment-cancel-failures?${new URLSearchParams({ from, to })}`),
    api<InventoryMismatch[]>("/api/admin/inventory/mismatches"),
  ]);

  return (
    <>
      <h1>확인 필요</h1>

      <h2>자동 환불 재시도 중 (PENDING)</h2>
      <p className="muted">
        PG 일시 장애로 환불이 실패해 1·2·4·8분 간격으로 자동 재시도 중인 건입니다. 5회째도 실패하면 아래 운영자 확인
        목록으로 넘어갑니다. 이 화면은 새로고침해야 갱신됩니다.
      </p>
      {!retrying.ok ? (
        <p className="error">{retrying.error.message}</p>
      ) : retrying.data.length === 0 ? (
        <p>없음</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>예약</th>
              <th>사유</th>
              <th>금액</th>
              <th>시도</th>
              <th>다음 시도</th>
              <th>마지막 오류</th>
            </tr>
          </thead>
          <tbody>
            {retrying.data.map((c) => (
              <tr key={c.id}>
                <td>{c.reservationNo}</td>
                <td>{c.reason === "COMPENSATION" ? "확정 실패 보상 취소" : "사용자 취소 환불"}</td>
                <td>{won(c.cancelAmount)}</td>
                <td>{c.attemptCount}회</td>
                <td>{dateTime(c.nextRetryAt)}</td>
                <td className="muted">{c.lastError ?? "-"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

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
              <th />
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
                <td>
                  {/* 실패하면 다시 이 목록에 남는다 (자동 재시도는 다시 시작되지 않음) */}
                  <form action={retryPaymentCancel}>
                    <input type="hidden" name="cancelId" value={c.id} />
                    <button type="submit">수동 재시도</button>
                  </form>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h2>자동 환불 실패 기록</h2>
      <p className="muted">
        PG 환불 시도가 실패할 때마다 남는 기록입니다. 재시도로 결국 환불된 건도 실패했던 회차가 남습니다. 해결 여부는
        그 환불 건의 현재 상태이며, 기록 삭제는 해결된(환불 완료) 건만 할 수 있습니다.
      </p>
      <form className="inline card">
        <label>
          발생일 <input type="date" name="from" defaultValue={from} />
        </label>
        <label>
          ~ <input type="date" name="to" defaultValue={to} />
        </label>
        <button type="submit">조회</button>
      </form>
      {!failures.ok ? (
        <p className="error">{failures.error.message}</p>
      ) : failures.data.length === 0 ? (
        <p>없음</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>발생 시각</th>
              <th>예약</th>
              <th>사유</th>
              <th>회차</th>
              <th>오류 코드</th>
              <th>메시지</th>
              <th>해결 여부</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {failures.data.map((f) => (
              <tr key={f.id}>
                <td>{dateTime(f.occurredAt)}</td>
                <td>{f.reservationNo}</td>
                <td>{f.reason === "COMPENSATION" ? "확정 실패 보상 취소" : "사용자 취소 환불"}</td>
                <td>{f.attemptNo}회차</td>
                <td>
                  {f.errorCode}
                  {f.httpStatus && <div className="muted">HTTP {f.httpStatus}</div>}
                </td>
                <td className="muted">{f.errorMessage ?? "-"}</td>
                <td className={RESOLUTION[f.cancelStatus].className}>{RESOLUTION[f.cancelStatus].label}</td>
                <td>
                  {f.cancelStatus === "SUCCEEDED" && (
                    <form action={deletePaymentCancelFailure}>
                      <input type="hidden" name="failureId" value={f.id} />
                      <button type="submit">삭제</button>
                    </form>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h2>재고 불일치 (오늘부터 1년)</h2>
      <p className="muted">
        날짜별 &quot;판매된 객실 수&quot;가 실제 예약 건수와 다른 날짜입니다. 정상이면 항상 비어 있습니다. 실제 예약
        건수(결제 대기·확정·이용 완료)가 기준이며, 원인(DB 직접 수정, 버그 등)을 확인한 뒤 [재계산]을 누르면 판매된
        객실 수를 실제 예약 건수로 고칩니다.
      </p>
      {!mismatches.ok ? (
        <p className="error">{mismatches.error.message}</p>
      ) : mismatches.data.length === 0 ? (
        <p>없음</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>숙소 / 객실</th>
              <th>날짜</th>
              <th>전체 객실</th>
              <th>판매된 객실 수</th>
              <th>실제 예약 건수</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {mismatches.data.map((m) => (
              <tr key={`${m.roomTypeId}-${m.stayDate}`}>
                <td>
                  {m.propertyName} / {m.roomTypeName}
                </td>
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
