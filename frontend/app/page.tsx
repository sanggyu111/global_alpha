import Link from "next/link";

import { DateRangeFields } from "@/app/DateRangeFields";
import { api } from "@/lib/api";
import { seoulDate, won } from "@/lib/format";
import type { PropertySearch } from "@/lib/types";

type Search = { region?: string; checkIn?: string; checkOut?: string; guests?: string };

export default async function HomePage({ searchParams }: { searchParams: Promise<Search> }) {
  const params = await searchParams;
  // 처음 들어오면 기본 조건(일주일 뒤 1박, 2명)으로 바로 검색해 보여준다
  const region = params.region ?? "";
  const checkIn = params.checkIn || seoulDate(7);
  const checkOut = params.checkOut || seoulDate(8);
  const guests = params.guests || "2";

  // 잔여 객실 수를 보여주므로 캐시하지 않는다 (README Q5). 숙소 정보만 보이던 T17 이전에는 60초 캐시였다.
  // 예약 가능한 객실이 없는 숙소는 백엔드가 결과에서 뺀다.
  const query = new URLSearchParams({ checkIn, checkOut, guests });
  if (region) query.set("region", region);
  const result = await api<PropertySearch>(`/api/properties/search?${query}`);

  // 숙소 상세로 같은 날짜·인원을 넘겨 다시 입력하지 않게 한다
  const stay = new URLSearchParams({ checkIn, checkOut, guests });

  return (
    <>
      <h1>숙소</h1>
      {/* GET 폼: 검색 조건이 URL 에 남아 새로고침·공유해도 같은 결과 */}
      <form className="inline card">
        <label>
          지역
          <select name="region" defaultValue={region}>
            <option value="">전체</option>
            <option value="서울">서울</option>
            <option value="부산">부산</option>
            <option value="제주">제주</option>
          </select>
        </label>
        <DateRangeFields today={seoulDate()} defaultCheckIn={checkIn} defaultCheckOut={checkOut} />
        <label>
          인원
          <input type="number" name="guests" min={1} max={10} defaultValue={guests} required />
        </label>
        <button type="submit">검색</button>
      </form>

      {!result.ok ? (
        <p className="error">{result.error.message}</p>
      ) : (
        <>
          <h2>
            예약 가능한 숙소 ({result.data.nights}박, {result.data.guests}명)
          </h2>
          {result.data.properties.length === 0 ? (
            <p>조건에 맞는 빈 객실이 있는 숙소가 없습니다.</p>
          ) : (
            result.data.properties.map((p) => (
              <div className="card" key={p.id}>
                <h2>
                  <Link href={`/properties/${p.id}?${stay}`}>{p.name}</Link>
                </h2>
                <div className="muted">
                  {p.region} · {p.address}
                </div>
                {/* 잔여 수는 참고값 — 최종 판단은 예약 생성 시 백엔드의 조건부 UPDATE */}
                <ul>
                  {p.rooms.map((room) => (
                    <li key={room.roomTypeId}>
                      {room.name} <span className="muted">(최대 {room.capacity}명)</span> · 잔여 {room.remaining}실 · 총{" "}
                      {won(room.totalPrice)}
                    </li>
                  ))}
                </ul>
              </div>
            ))
          )}
        </>
      )}
    </>
  );
}
