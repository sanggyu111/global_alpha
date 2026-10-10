package com.staypoint.mockpg;

/**
 * 데모용 취소 장애 종류 (설계 5장 T20).
 * UNAVAILABLE: 503 — 호출한 쪽이 재시도할 일시 장애. REJECTED: 422 — 다시 보내도 같은 거절.
 */
enum MockPgCancelFault {
	UNAVAILABLE,
	REJECTED
}
