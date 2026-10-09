import Link from "next/link";

import { api } from "@/lib/api";
import { seoulDate, won } from "@/lib/format";
import type { CalendarDay, PropertyDetail, PropertySummary } from "@/lib/types";

import { PeriodForm } from "./PeriodForm";

type Search = { roomTypeId?: string; from?: string; to?: string };

export default async function AdminInventoryPage({ searchParams }: { searchParams: Promise<Search> }) {
  const search = await searchParams;
  const from = search.from ?? seoulDate(0);
  const to = search.to ?? seoulDate(13);

  // 객실 타입 선택지: 숙소별 객실 타입 (숙소 정보는 거의 안 바뀜 → 60초 캐시)
  const properties = await api<PropertySummary[]>("/api/properties", { revalidate: 60 });
  const details = properties.ok
    ? await Promise.all(properties.data.map((p) => api<PropertyDetail>(`/api/properties/${p.id}`, { revalidate: 60 })))
    : [];
  const roomTypes = details.flatMap((d) =>
    d.ok ? d.data.roomTypes.map((rt) => ({ id: rt.id, label: `${d.data.name} / ${rt.name}` })) : [],
  );
  const roomTypeId = search.roomTypeId ?? (roomTypes[0] ? String(roomTypes[0].id) : undefined);

  // 재고·예약 수는 계속 바뀌므로 캐시하지 않는다
  const calendar = roomTypeId
    ? await api<CalendarDay[]>(`/api/admin/room-types/${roomTypeId}/calendar?${new URLSearchParams({ from, to })}`)
    : null;

  return (
    <>
      <h1>재고 · 요금</h1>
      <form className="inline card">
        <label>
          객실 타입
          <select name="roomTypeId" defaultValue={roomTypeId}>
            {roomTypes.map((rt) => (
              <option key={rt.id} value={rt.id}>
                {rt.label}
              </option>
            ))}
          </select>
        </label>
        <label>
          from
          <input type="date" name="from" defaultValue={from} required />
        </label>
        <label>
          to
          <input type="date" name="to" defaultValue={to} required />
        </label>
        <button type="submit">보기</button>
      </form>

      {roomTypeId && (
        <div className="card">
          <h2>기간 일괄 수정</h2>
          <p className="muted">
            기간은 양 끝 포함. 재고는 이미 예약된 수보다 줄일 수 없고(하나라도 해당하면 전체 거부), 요금 변경은 이미 만든
            예약 금액에 영향을 주지 않습니다.
          </p>
          <PeriodForm kind="inventory" roomTypeId={roomTypeId} from={from} to={to} />
          <PeriodForm kind="rates" roomTypeId={roomTypeId} from={from} to={to} />
        </div>
      )}

      {calendar && !calendar.ok && <p className="error">{calendar.error.message}</p>}
      {calendar?.ok && (
        <table>
          <thead>
            <tr>
              <th>날짜</th>
              <th>재고</th>
              <th>예약</th>
              <th>잔여</th>
              <th>1박 요금</th>
            </tr>
          </thead>
          <tbody>
            {calendar.data.map((day) => (
              <tr key={day.date}>
                <td>{day.date}</td>
                <td>{day.totalCount ?? <span className="muted">미등록</span>}</td>
                <td>{day.bookedCount ?? "-"}</td>
                <td>{day.available ?? "-"}</td>
                <td>{day.price == null ? <span className="muted">미등록</span> : won(day.price)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <p>
        <Link href="/admin/issues">재고 불일치 · 확인 필요 →</Link>
      </p>
    </>
  );
}
