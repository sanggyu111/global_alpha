import Link from "next/link";
import { notFound } from "next/navigation";

import { DateRangeFields } from "@/app/DateRangeFields";
import { api } from "@/lib/api";
import { seoulDate, won } from "@/lib/format";
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
    return <p className="error">{property.error.message}</p>;
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
      <p>
        <Link href="/">← 숙소 목록</Link>
      </p>
      <h1>{property.data.name}</h1>
      <p className="muted">
        {property.data.region} · {property.data.address}
      </p>
      {property.data.description && <p>{property.data.description}</p>}

      {/* GET 폼: 검색 조건이 URL 에 남아 새로고침·공유해도 같은 결과 */}
      <form className="inline card">
        <DateRangeFields today={seoulDate()} defaultCheckIn={checkIn ?? ""} defaultCheckOut={checkOut ?? ""} />
        <label>
          인원
          <input type="number" name="guests" min={1} max={10} defaultValue={guests ?? "2"} required />
        </label>
        <button type="submit">예약 가능한 객실 검색</button>
      </form>

      {availability && !availability.ok && <p className="error">{availability.error.message}</p>}
      {availability?.ok && (
        <>
          <h2>
            예약 가능한 객실 ({availability.data.nights}박, {availability.data.guests}명)
          </h2>
          {availability.data.rooms.length === 0 ? (
            <p>조건에 맞는 빈 객실이 없습니다.</p>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>객실</th>
                  <th>날짜별 요금</th>
                  <th>총 요금</th>
                  <th>잔여</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {availability.data.rooms.map((room) => (
                  <tr key={room.roomTypeId}>
                    <td>
                      {room.name}
                      <div className="muted">최대 {room.capacity}명</div>
                    </td>
                    <td>
                      {room.nightlyRates.map((rate) => (
                        <div key={rate.date} className="muted">
                          {rate.date} {won(rate.price)}
                        </div>
                      ))}
                    </td>
                    <td>
                      <strong>{won(room.totalPrice)}</strong>
                    </td>
                    <td>{room.remaining}실</td>
                    <td>
                      <Link
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
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </>
  );
}
