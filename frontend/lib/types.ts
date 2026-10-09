// 백엔드 API 응답 타입 (backend 의 DTO record 와 같은 모양).
// 금액은 JSON 숫자로 오며 화면 표시에만 쓴다 — 계산은 백엔드(BigDecimal)가 한다.

export type PropertySummary = {
  id: number;
  name: string;
  address: string;
  region: string;
};

export type RoomTypeSummary = { id: number; name: string; capacity: number };

export type PropertyDetail = PropertySummary & {
  description: string | null;
  roomTypes: RoomTypeSummary[];
};

export type NightlyRate = { date: string; price: number };

export type AvailableRoom = {
  roomTypeId: number;
  name: string;
  capacity: number;
  remaining: number;
  nightlyRates: NightlyRate[];
  totalPrice: number;
};

export type Availability = {
  propertyId: number;
  checkIn: string;
  checkOut: string;
  nights: number;
  guests: number;
  rooms: AvailableRoom[];
};

export type ReservationStatus = "PENDING" | "CONFIRMED" | "COMPLETED" | "CANCELED";
export type PaymentStatus = "READY" | "APPROVED" | "FAILED" | "CANCELED";

export type Reservation = {
  id: number;
  reservationNo: string;
  status: ReservationStatus;
  totalAmount: number;
  holdExpiresAt: string;
};

export type ReservationSummary = {
  id: number;
  reservationNo: string;
  status: ReservationStatus;
  propertyName: string;
  roomTypeName: string;
  checkIn: string;
  checkOut: string;
  totalAmount: number;
  createdAt: string;
};

export type ReservationDetail = {
  id: number;
  reservationNo: string;
  status: ReservationStatus;
  propertyId: number;
  propertyName: string;
  roomTypeId: number;
  roomTypeName: string;
  checkIn: string;
  checkOut: string;
  nights: number;
  guestCount: number;
  guestName: string;
  totalAmount: number;
  holdExpiresAt: string;
  confirmedAt: string | null;
  canceledAt: string | null;
  cancelReason: string | null;
  refundAmount: number | null;
  refundStatus: string | null;
  payment: {
    paymentId: number;
    status: PaymentStatus;
    amount: number;
    canceledAmount: number;
    failReason: string | null;
  } | null;
  refundEstimate: { daysBeforeCheckIn: number; percent: number; amount: number } | null;
};

export type PaymentResult = "CONFIRMED" | "FAILED" | "PROCESSING" | "COMPENSATED";

export type PaymentResponse = {
  paymentId: number;
  reservationId: number;
  result: PaymentResult;
  paymentStatus: PaymentStatus;
  reservationStatus: ReservationStatus;
  amount: number;
  failReason: string | null;
};
