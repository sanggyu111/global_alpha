import Link from "next/link";

import { api } from "@/lib/api";
import { STATUS_LABEL, dateTime, won } from "@/lib/format";
import type { AdminReservation, Page, PropertySummary } from "@/lib/types";

type Search = { status?: string; propertyId?: string; from?: string; to?: string; page?: string };

const PAGE_SIZE = 20;

/**
 * 필터와 페이지는 URL searchParams 로 받아 서버에서 조회한다 (클라이언트 상태 없음).
 * → 새로고침·뒤로가기·링크 공유에도 같은 화면, 화면 코드는 HTML 폼(GET)과 링크뿐.
 */
export default async function AdminReservationsPage({ searchParams }: { searchParams: Promise<Search> }) {
  const search = await searchParams;
  const page = Math.max(0, Number(search.page ?? 0) || 0);

  const query = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
  for (const key of ["status", "propertyId", "from", "to"] as const) {
    if (search[key]) query.set(key, search[key]!);
  }
  const [reservations, properties] = await Promise.all([
    api<Page<AdminReservation>>(`/api/admin/reservations?${query}`),
    api<PropertySummary[]>("/api/properties", { revalidate: 60 }),
  ]);

  const pageLink = (target: number) => {
    const params = new URLSearchParams(query);
    params.set("page", String(target));
    params.delete("size");
    return `/admin/reservations?${params}`;
  };

  return (
    <>
      <h1>예약 목록</h1>
      <form className="inline card">
        <label>
          상태
          <select name="status" defaultValue={search.status ?? ""}>
            <option value="">전체</option>
            {Object.entries(STATUS_LABEL).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label>
          숙소
          <select name="propertyId" defaultValue={search.propertyId ?? ""}>
            <option value="">전체</option>
            {properties.ok &&
              properties.data.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
          </select>
        </label>
        <label>
          체크인 from
          <input type="date" name="from" defaultValue={search.from} />
        </label>
        <label>
          체크인 to
          <input type="date" name="to" defaultValue={search.to} />
        </label>
        <button type="submit">조회</button>
        <Link href="/admin/reservations">초기화</Link>
      </form>

      {!reservations.ok ? (
        <p className="error">{reservations.error.message}</p>
      ) : (
        <>
          <p className="muted">
            총 {reservations.data.totalElements}건 · {page + 1} / {Math.max(1, reservations.data.totalPages)} 페이지
          </p>
          <table>
            <thead>
              <tr>
                <th>예약번호</th>
                <th>사용자</th>
                <th>숙소 / 객실</th>
                <th>일정</th>
                <th>금액</th>
                <th>상태</th>
                <th>생성</th>
              </tr>
            </thead>
            <tbody>
              {reservations.data.content.map((r) => (
                <tr key={r.id}>
                  <td>{r.reservationNo}</td>
                  <td>
                    {r.userId}
                    <div className="muted">{r.guestName}</div>
                  </td>
                  <td>
                    {r.propertyName}
                    <div className="muted">{r.roomTypeName}</div>
                  </td>
                  <td>
                    {r.checkIn} ~ {r.checkOut}
                  </td>
                  <td>{won(r.totalAmount)}</td>
                  <td>
                    {STATUS_LABEL[r.status]}
                    {r.cancelReason && <div className="muted">{r.cancelReason}</div>}
                  </td>
                  <td className="muted">{dateTime(r.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p>
            {page > 0 && <Link href={pageLink(page - 1)}>← 이전</Link>}{" "}
            {page + 1 < reservations.data.totalPages && <Link href={pageLink(page + 1)}>다음 →</Link>}
          </p>
        </>
      )}
    </>
  );
}
