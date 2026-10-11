import { deletePgError } from "@/app/actions";
import { api } from "@/lib/api";
import { dateTime, seoulDate } from "@/lib/format";
import type { PgErrorKind, PgErrorRecord } from "@/lib/types";

type Search = { from?: string; to?: string; kind?: string; code?: string };

const KIND_LABEL: Record<PgErrorKind, string> = { APPROVE: "결제 승인", CANCEL: "환불" };

// 오류 코드와 뜻 (설계 8.4 · 8.5) — 필터 선택지와 화면 하단 설명에 함께 쓴다
const CODES: [string, string][] = [
  ["PG_DECLINED", "PG 가 승인을 거절 (결제만). 고객은 다시 결제할 수 있다"],
  ["PG_5XX", "PG 일시 장애 (5xx 응답)"],
  ["PG_TIMEOUT", "응답 시간 초과 — PG 에선 처리됐을 수도 있어 결과를 다시 확인한다"],
  ["PG_CONNECTION", "PG 에 연결하지 못함"],
  ["PG_REJECTED", "PG 가 요청을 거절 (4xx). 다시 보내도 같은 결과"],
  ["PG_UNKNOWN", "그 밖의 통신 오류"],
];

// 현재 상태 = 원래 건(결제 · 환불)의 지금 상태. 기록 자체에 저장하지 않고 조회할 때 계산한다
const STATUS_LABEL: Record<PgErrorKind, Record<string, string>> = {
  APPROVE: {
    READY: "결과 확인 중",
    APPROVED: "승인됨 (응답만 늦음)",
    FAILED: "결제 실패 확정",
    CANCELED: "승인 후 취소됨",
  },
  CANCEL: {
    PENDING: "자동 재시도 중",
    SUCCEEDED: "환불 완료",
    MANUAL_REVIEW: "운영자 확인 필요",
  },
};

/**
 * PG 오류 (설계 8.5 T23): 결제 승인 실패와 환불 실패를 한 목록으로 본다.
 * 필터는 URL searchParams (GET 폼) → 서버 컴포넌트에서 매번 새로 조회 (no-store).
 */
export default async function PgErrorsPage({ searchParams }: { searchParams: Promise<Search> }) {
  const search = await searchParams;
  const from = search.from || seoulDate(-29);
  const to = search.to || seoulDate();
  const query = new URLSearchParams({ from, to });
  if (search.kind) query.set("kind", search.kind);
  if (search.code) query.set("code", search.code);
  const errors = await api<PgErrorRecord[]>(`/api/admin/pg-errors?${query}`);

  return (
    <>
      <h1>PG 오류</h1>
      <p className="muted">
        결제 승인과 환불에서 PG 호출이 실패할 때마다 남는 기록입니다. 나중에 결과가 정상으로 확정돼도 기록은 남습니다.
        &quot;현재 상태&quot;는 그 결제·환불의 지금 상태이며, 결과가 확정된(해결된) 기록만 삭제할 수 있습니다.
      </p>

      <form className="inline card">
        <label>
          발생일 <input type="date" name="from" defaultValue={from} />
        </label>
        <label>
          ~ <input type="date" name="to" defaultValue={to} />
        </label>
        <label>
          종류{" "}
          <select name="kind" defaultValue={search.kind ?? ""}>
            <option value="">전체</option>
            <option value="APPROVE">결제 승인</option>
            <option value="CANCEL">환불</option>
          </select>
        </label>
        <label>
          오류 코드{" "}
          <select name="code" defaultValue={search.code ?? ""}>
            <option value="">전체</option>
            {CODES.map(([code]) => (
              <option key={code} value={code}>
                {code}
              </option>
            ))}
          </select>
        </label>
        <button type="submit">조회</button>
      </form>

      {!errors.ok ? (
        <p className="error">{errors.error.message}</p>
      ) : errors.data.length === 0 ? (
        <p>없음</p>
      ) : (
        <>
          <Summary errors={errors.data} />
          <table>
            <thead>
              <tr>
                <th>발생 시각</th>
                <th>종류</th>
                <th>예약</th>
                <th>오류 코드</th>
                <th>메시지</th>
                <th>현재 상태</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {errors.data.map((e) => (
                <tr key={`${e.kind}-${e.id}`}>
                  <td>{dateTime(e.occurredAt)}</td>
                  <td>
                    {KIND_LABEL[e.kind]}
                    {e.attemptNo && <div className="muted">{e.attemptNo}회차</div>}
                  </td>
                  <td>{e.reservationNo}</td>
                  <td>
                    {e.errorCode}
                    {e.httpStatus && <div className="muted">HTTP {e.httpStatus}</div>}
                  </td>
                  <td className="muted">{e.errorMessage ?? "-"}</td>
                  <td>
                    {STATUS_LABEL[e.kind][e.currentStatus] ?? e.currentStatus}
                    <div className={resolutionClass(e)}>{resolutionLabel(e)}</div>
                  </td>
                  <td>
                    {e.resolved && (
                      <form action={deletePgError}>
                        <input type="hidden" name="kind" value={e.kind} />
                        <input type="hidden" name="id" value={e.id} />
                        <button type="submit">삭제</button>
                      </form>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {errors.data.length >= 500 && <p className="muted">최근 500건만 보입니다. 기간을 좁혀 주세요.</p>}
        </>
      )}

      <h2>오류 코드</h2>
      <table>
        <tbody>
          {CODES.map(([code, meaning]) => (
            <tr key={code}>
              <td>{code}</td>
              <td className="muted">{meaning}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}

/** 조회 결과의 오류 코드별 건수와 미해결 건수 — 어떤 장애가 몰렸는지 먼저 보이게 한다. */
function Summary({ errors }: { errors: PgErrorRecord[] }) {
  const byCode = new Map<string, number>();
  for (const e of errors) byCode.set(e.errorCode, (byCode.get(e.errorCode) ?? 0) + 1);
  const unresolved = errors.filter((e) => !e.resolved).length;
  return (
    <p className="card">
      총 {errors.length}건 ·{" "}
      {[...byCode.entries()].map(([code, n]) => `${code} ${n}`).join(" · ")} ·{" "}
      <span className={unresolved > 0 ? "error strong" : undefined}>미해결 {unresolved}건</span>
    </p>
  );
}

// 운영자 확인이 필요한 환불만 빨간색. 결과 확인 중(READY)·자동 재시도 중(PENDING)은 시스템이 처리 중
function resolutionLabel(e: PgErrorRecord): string {
  if (e.resolved) return "해결됨";
  return e.currentStatus === "MANUAL_REVIEW" ? "미해결" : "처리 중";
}

function resolutionClass(e: PgErrorRecord): string {
  if (e.resolved) return "success";
  return e.currentStatus === "MANUAL_REVIEW" ? "error strong" : "muted";
}
