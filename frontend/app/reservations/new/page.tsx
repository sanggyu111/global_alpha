import Link from "next/link";

import { api } from "@/lib/api";
import { won } from "@/lib/format";
import type { Availability } from "@/lib/types";

import { ReservationForm } from "./ReservationForm";

type Search = { propertyId?: string; roomTypeId?: string; checkIn?: string; checkOut?: string; guests?: string };

export default async function NewReservationPage({ searchParams }: { searchParams: Promise<Search> }) {
  const { propertyId, roomTypeId, checkIn, checkOut, guests } = await searchParams;
  if (!propertyId || !roomTypeId || !checkIn || !checkOut || !guests) {
    return <p className="error">예약 조건이 없습니다. 숙소에서 객실을 선택해 주세요.</p>;
  }
  const back = `/properties/${propertyId}?${new URLSearchParams({ checkIn, checkOut, guests })}`;

  // 선택한 객실이 지금도 예약 가능한지, 금액이 얼마인지 다시 확인 (캐시 없음)
  const availability = await api<Availability>(
    `/api/properties/${propertyId}/availability?${new URLSearchParams({ checkIn, checkOut, guests })}`,
  );
  const room = availability.ok ? availability.data.rooms.find((r) => String(r.roomTypeId) === roomTypeId) : undefined;
  if (!room) {
    return (
      <>
        <p className="error">
          {availability.ok ? "선택한 객실은 이제 예약할 수 없습니다 (매진 또는 조건 변경)." : availability.error.message}
        </p>
        <Link href={back}>← 다른 객실 보기</Link>
      </>
    );
  }

  // 이 폼 하나 = 예약 의도 하나. 서버가 그릴 때 키를 만들어 넣어 두므로 연타해도 같은 키가 간다.
  const idempotencyKey = crypto.randomUUID();

  return (
    <>
      <p>
        <Link href={back}>← 객실 다시 선택</Link>
      </p>
      <h1>예약 정보 입력</h1>
      <div className="card">
        <div>
          <strong>{room.name}</strong> · {checkIn} ~ {checkOut} ({availability.ok && availability.data.nights}박) ·{" "}
          {guests}명
        </div>
        <div>
          총 요금 <strong>{won(room.totalPrice)}</strong>
        </div>
        <div className="muted">예약하면 10분 동안 객실이 선점되고, 그 안에 결제해야 확정됩니다.</div>
      </div>
      <ReservationForm
        hidden={{ roomTypeId, checkIn, checkOut, guests, idempotencyKey }}
      />
    </>
  );
}
