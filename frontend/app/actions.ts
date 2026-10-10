"use server";

// Server Action: 브라우저의 폼 제출·버튼 클릭이 Next 서버의 이 함수들을 부르고, 여기서 백엔드 API 를 호출한다.

import { cookies } from "next/headers";
import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";

import { api, type ApiError } from "@/lib/api";
import type { PaymentResponse, Reservation } from "@/lib/types";
import { USER_COOKIE, getUserId } from "@/lib/user";

export async function setUser(formData: FormData) {
  const userId = String(formData.get("userId") ?? "").trim().slice(0, 50);
  const store = await cookies();
  if (userId) {
    store.set(USER_COOKIE, userId, { path: "/", sameSite: "lax", httpOnly: true });
  } else {
    store.delete(USER_COOKIE);
  }
  revalidatePath("/", "layout");
}

export type FormState = { error: string | null };

/**
 * 예약 생성. idempotencyKey 는 서버가 폼을 그릴 때 hidden 필드로 넣어 둔 값이라,
 * 같은 폼을 두 번 제출(연타)해도 같은 키 → 백엔드가 예약을 하나만 만든다.
 */
export async function createReservation(_prev: FormState, formData: FormData): Promise<FormState> {
  const field = (name: string) => String(formData.get(name) ?? "").trim();
  const result = await api<Reservation>("/api/reservations", {
    method: "POST",
    userId: await getUserId(),
    idempotencyKey: field("idempotencyKey"),
    body: {
      roomTypeId: Number(field("roomTypeId")),
      checkIn: field("checkIn"),
      checkOut: field("checkOut"),
      guestCount: Number(field("guests")),
      guestName: field("guestName"),
      guestPhone: field("guestPhone"),
    },
  });
  if (!result.ok) {
    return { error: messageOf(result.error) };
  }
  revalidatePath("/properties/[id]", "page"); // 잔여 재고가 바뀜
  redirect(`/reservations/${result.data.id}/pay`);
}

export type PayResult = { ok: true; data: PaymentResponse } | { ok: false; error: ApiError };

/**
 * 결제 요청. idempotencyKey 는 결제 화면(클라이언트)이 만들어 sessionStorage 에 보관하는 값 —
 * 새로고침·재클릭·타임아웃 후 재시도에도 같은 키라 이중 결제가 생기지 않는다.
 * failRate · delayMs 는 데모용 장애 주입 값 (모의 PG 에 전달).
 */
export async function payReservation(
  reservationId: number,
  idempotencyKey: string,
  failRate: number,
  delayMs: number,
): Promise<PayResult> {
  const query = new URLSearchParams({ failRate: String(failRate), delayMs: String(delayMs) });
  const result = await api<PaymentResponse>(`/api/reservations/${reservationId}/payments?${query}`, {
    method: "POST",
    userId: await getUserId(),
    idempotencyKey,
  });
  revalidatePath(`/reservations/${reservationId}/pay`);
  return result.ok ? { ok: true, data: result.data } : { ok: false, error: result.error };
}

export type CancelResponse = {
  reservationId: number;
  status: string;
  refundAmount: number;
  refundStatus: string;
};

export type CancelResult = { ok: true; data: CancelResponse } | { ok: false; error: ApiError };

export type RefundFault = { failTimes: number; failType: "UNAVAILABLE" | "REJECTED" };

/**
 * 예약 취소. 이미 취소된 예약을 다시 취소해도 백엔드가 처음 결과를 돌려주므로(멱등) 별도 키가 필요 없다.
 * 취소되면 재고가 늘어나므로 숙소 화면도 다시 검증한다.
 * refundFault 는 데모용 환불 장애 주입 값 (T20 — 모의 PG 가 이 결제의 환불을 failTimes 번 실패시킨다).
 */
export async function cancelReservation(
  reservationId: number,
  refundFault: RefundFault | null = null,
): Promise<CancelResult> {
  const query = refundFault
    ? `?${new URLSearchParams({ failTimes: String(refundFault.failTimes), failType: refundFault.failType })}`
    : "";
  const result = await api<CancelResponse>(`/api/reservations/${reservationId}/cancel${query}`, {
    method: "POST",
    userId: await getUserId(),
  });
  revalidatePath("/me/reservations");
  revalidatePath(`/me/reservations/${reservationId}`);
  revalidatePath("/properties/[id]", "page");
  return result.ok ? { ok: true, data: result.data } : { ok: false, error: result.error };
}

// ---- 관리자 (인증 없음 — 과제 범위 밖) ----

/** 기간 재고 설정. 예약된 수보다 줄이려는 날짜가 있으면 백엔드가 전체를 거부하고 그 날짜들을 알려준다. */
export async function setInventory(_prev: FormState, formData: FormData): Promise<FormState> {
  const roomTypeId = String(formData.get("roomTypeId"));
  const result = await api(`/api/admin/room-types/${roomTypeId}/inventory`, {
    method: "PUT",
    body: {
      from: formData.get("from"),
      to: formData.get("to"),
      totalCount: Number(formData.get("totalCount")),
    },
  });
  if (!result.ok) return { error: messageOf(result.error) };
  revalidatePath("/admin/inventory");
  return { error: null };
}

/** 기간 요금 설정. 이미 만든 예약의 금액은 바뀌지 않는다. */
export async function setRates(_prev: FormState, formData: FormData): Promise<FormState> {
  const roomTypeId = String(formData.get("roomTypeId"));
  const result = await api(`/api/admin/room-types/${roomTypeId}/rates`, {
    method: "PUT",
    body: { from: formData.get("from"), to: formData.get("to"), price: Number(formData.get("price")) },
  });
  if (!result.ok) return { error: messageOf(result.error) };
  revalidatePath("/admin/inventory");
  return { error: null };
}

/** 재고 재계산: 해당 날짜 booked_count 를 예약 테이블 기준 값으로 맞춘다 (운영자가 원인 확인 후 실행). */
export async function recountInventory(formData: FormData) {
  await api(`/api/admin/room-types/${formData.get("roomTypeId")}/inventory/${formData.get("stayDate")}/recount`, {
    method: "POST",
  });
  revalidatePath("/admin/issues");
}

/** 운영자 수동 재시도: PG 쪽 원인을 확인한 뒤 자동 환불 실패 건을 한 번 더 시도한다. */
export async function retryPaymentCancel(formData: FormData) {
  await api(`/api/admin/payment-cancels/${formData.get("cancelId")}/retry`, { method: "POST" });
  revalidatePath("/admin/issues");
}

function messageOf(error: ApiError): string {
  const fieldMessages = error.details
    ? Object.values(error.details).flatMap((v) => (typeof v === "string" ? [v] : Array.isArray(v) ? [v.join(", ")] : []))
    : [];
  return fieldMessages.length > 0 ? `${error.message} (${fieldMessages.join(", ")})` : error.message;
}
