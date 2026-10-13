import Link from "next/link";
import { notFound } from "next/navigation";

import { DateRangeFields } from "@/app/DateRangeFields";
import { api } from "@/lib/api";
import { seoulDate, shortDate, won } from "@/lib/format";
import type { Availability, PropertyDetail } from "@/lib/types";

type Search = { checkIn?: string; checkOut?: string; guests?: string };

export default async function PropertyPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<Search>;
}) {
  const { id } = await params;
  const { checkIn, checkOut, guests } = await searchParams;

  // 숙소 정보: 거의 안 바뀜 → 60초 캐시
  const property = await api<PropertyDetail>(`/api/properties/${id}`, { revalidate: 60 });
  if (!property.ok) {
    if (property.status === 404) notFound();
    return <p className="notice notice-error">{property.error.message}</p>;
  }

  // 가용 객실: 초 단위로 변함 → 캐시하지 않음. 화면의 잔여 수는 참고값이고,
  // 최종 판단은 예약 생성 시 백엔드의 조건부 UPDATE 가 한다 (그 사이 매진되면 SOLD_OUT 안내).
  const searched = Boolean(checkIn && checkOut && guests);
  const availability = searched
    ? await api<Availability>(
        `/api/properties/${id}/availability?${new URLSearchParams({ checkIn: checkIn!, checkOut: checkOut!, guests: guests! })}`,
      )
    : null;

  return (
    <>
      <Link href="/" className="back">
        ← 숙소 목록
      </Link>
      <h1>{property.data.name}</h1>
      <p className="muted">
        {property.data.region} · {property.data.address}
      </p>
      {property.data.description && <p>{property.data.description}</p>}

      {/* GET 폼: 검색 조건이 URL 에 남아 새로고침·공유해도 같은 결과 */}
      <form className="card form-grid">
        <DateRangeFields today={seoulDate()} defaultCheckIn={checkIn ?? ""} defaultCheckOut={checkOut ?? ""} />
        <label>
          인원
          <input type="number" name="guests" min={1} max={10} defaultValue={guests ?? "2"} required />
        </label>
        <div className="actions">
          <button type="submit" className="btn">
            객실 검색
          </button>
        </div>
      </form>

      {availability && !availability.ok && <p className="notice notice-error">{availability.error.message}</p>}
      {availability?.ok && (
        <>
          <div className="section-title">
            <h2>예약 가능한 객실</h2>
            <span className="muted">
              {availability.data.nights}박 · {availability.data.guests}명
            </span>
          </div>
          {availability.data.rooms.length === 0 ? (
            <p className="card empty">조건에 맞는 빈 객실이 없습니다.</p>
          ) : (
            <div className="list list-2">
              {availability.data.rooms.map((room) => (
                <div className="card" key={room.roomTypeId}>
                  <div className="item-head">
                    <h3>{room.name}</h3>
                    <span className="badge">잔여 {room.remaining}실</span>
                  </div>
                  <div className="muted">최대 {room.capacity}명</div>
                  {/* 날짜별 요금(주말·성수기 차이)은 접어 두고 총액을 먼저 보여준다 */}
                  <details className="fold mt-12">
                    <summary>날짜별 요금 {room.nightlyRates.length}박</summary>
                    <ul className="rows">
                      {room.nightlyRates.map((rate) => (
                        <li key={rate.date}>
                          <span>{shortDate(rate.date)}</span>
                          <span>{won(rate.price)}</span>
                        </li>
                      ))}
                    </ul>
                  </details>
                  <div className="room-foot">
                    <div>
                      <div className="muted">총 요금</div>
                      <strong className="amount">{won(room.totalPrice)}</strong>
                    </div>
                    <Link
                      className="btn"
                      href={`/reservations/new?${new URLSearchParams({
                        propertyId: id,
                        roomTypeId: String(room.roomTypeId),
                        checkIn: availability.data.checkIn,
                        checkOut: availability.data.checkOut,
                        guests: String(availability.data.guests),
                      })}`}
                    >
                      예약하기
                    </Link>
                  </div>
                </div>
              ))}
            </div>
          )}
        </>
      )}
    </>
  );
}
