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
      <h1>어디로 떠나세요?</h1>
      {/* GET 폼: 검색 조건이 URL 에 남아 새로고침·공유해도 같은 결과 */}
      <form className="card form-grid">
        <label>
          지역
          <select name="region" defaultValue={region}>
            <option value="">전체</option>
            <option value="서울">서울</option>
            <option value="부산">부산</option>
            <option value="제주">제주</option>
          </select>
        </label>
        <label>
          인원
          <input type="number" name="guests" min={1} max={10} defaultValue={guests} required />
        </label>
        <DateRangeFields today={seoulDate()} defaultCheckIn={checkIn} defaultCheckOut={checkOut} />
        <div className="actions">
          <button type="submit" className="btn">
            검색
          </button>
        </div>
      </form>

      {!result.ok ? (
        <p className="notice notice-error">{result.error.message}</p>
      ) : (
        <>
          <div className="section-title">
            <h2>예약 가능한 숙소 {result.data.properties.length}곳</h2>
            <span className="muted">
              {result.data.nights}박 · {result.data.guests}명
            </span>
          </div>
          {result.data.properties.length === 0 ? (
            <p className="card empty">조건에 맞는 빈 객실이 있는 숙소가 없습니다.</p>
          ) : (
            <div className="list">
              {result.data.properties.map((p) => (
                <div className="card" key={p.id}>
                  <div className="item-head">
                    <div>
                      <h2>
                        <Link href={`/properties/${p.id}?${stay}`}>{p.name}</Link>
                      </h2>
                      <div className="muted">
                        {p.region} · {p.address}
                      </div>
                    </div>
                    <Link href={`/properties/${p.id}?${stay}`} className="btn btn-secondary btn-sm">
                      객실 보기
                    </Link>
                  </div>
                  {/* 잔여 수는 참고값 — 최종 판단은 예약 생성 시 백엔드의 조건부 UPDATE */}
                  <ul className="rows">
                    {p.rooms.map((room) => (
                      <li key={room.roomTypeId}>
                        <div>
                          {room.name}
                          <div className="muted">
                            최대 {room.capacity}명 · 잔여 {room.remaining}실
                          </div>
                        </div>
                        <div className="price">
                          {won(room.totalPrice)}
                          <div className="muted">{result.data.nights}박 총액</div>
                        </div>
                      </li>
                    ))}
                  </ul>
                </div>
              ))}
            </div>
          )}
        </>
      )}
    </>
  );
}
