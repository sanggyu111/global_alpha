"use client";

import { useState } from "react";

/** yyyy-MM-dd 에 하루를 더한다. 문자열을 UTC 자정으로 읽어 브라우저 시간대와 무관하게 계산. */
function nextDay(date: string): string {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().slice(0, 10);
}

/**
 * 체크인·체크아웃 날짜 입력 (숙소 목록·상세 검색 폼 공용, T18).
 * - 체크인은 오늘부터, 체크아웃은 체크인 다음 날부터만 고를 수 있다 (min → 달력에서 이전 날짜가 비활성).
 * - 체크인을 바꿔 체크아웃이 체크인 이전·같은 날이 되면 체크아웃을 체크인 다음 날로 옮긴다.
 * - today 는 서버가 Asia/Seoul 기준으로 계산해 넘긴다. 브라우저에서 계산하면 기기 시간대에 따라
 *   하루 어긋나거나 서버 렌더 결과와 달라질 수 있다.
 * 화면 제한은 편의일 뿐이고, 최종 검증은 백엔드가 한다 (URL 을 직접 고치면 우회되므로).
 */
export function DateRangeFields({
  today,
  defaultCheckIn,
  defaultCheckOut,
}: {
  today: string;
  defaultCheckIn: string;
  defaultCheckOut: string;
}) {
  const [checkIn, setCheckIn] = useState(defaultCheckIn);
  const [checkOut, setCheckOut] = useState(defaultCheckOut);

  function changeCheckIn(value: string) {
    setCheckIn(value);
    if (value && (!checkOut || checkOut <= value)) {
      setCheckOut(nextDay(value)); // yyyy-MM-dd 는 문자열 비교가 곧 날짜 비교
    }
  }

  return (
    <>
      <label>
        체크인
        <input
          type="date"
          name="checkIn"
          min={today}
          value={checkIn}
          onChange={(e) => changeCheckIn(e.target.value)}
          required
        />
      </label>
      <label>
        체크아웃
        <input
          type="date"
          name="checkOut"
          min={nextDay(checkIn || today)}
          value={checkOut}
          onChange={(e) => setCheckOut(e.target.value)}
          required
        />
      </label>
    </>
  );
}
