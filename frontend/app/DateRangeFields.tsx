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
 * - min 을 무시하는 브라우저(iOS Safari)에서 이전 날짜를 고르면 입력을 마칠 때(blur) min 으로 보정하고 alert 로 알린다.
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

  // yyyy-MM-dd 는 문자열 비교가 곧 날짜 비교.
  function changeCheckIn(value: string) {
    setCheckIn(value);
    if (value && (!checkOut || checkOut <= value)) {
      setCheckOut(nextDay(value));
    }
  }

  // iOS Safari 의 날짜 피커는 min 을 무시해 이전 날짜도 골라진다. 그래서 입력을 마친 시점(blur)에 min 으로
  // 끌어올리고, 말없이 바뀌면 사용자가 헷갈리므로 alert 로 이유를 알린다.
  // onChange 에서 하지 않는 이유: 키보드로 월·일·연도를 한 칸씩 치면 칠 때마다 onChange 가 오고,
  // 중간 값(예: 월만 바꾼 2026-01-17)이 과거라서 입력 도중 알림이 여러 번 뜬다.
  function blurCheckIn() {
    if (checkIn && checkIn < today) {
      alert("지난 날짜는 체크인으로 선택할 수 없습니다. 오늘 날짜로 바꿨습니다.");
      changeCheckIn(today);
    }
  }

  function blurCheckOut() {
    const min = nextDay(checkIn || today);
    if (checkOut && checkOut < min) {
      alert("체크아웃은 체크인 다음 날부터 선택할 수 있습니다. 체크인 다음 날로 바꿨습니다.");
      setCheckOut(min);
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
          onBlur={blurCheckIn}
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
          onBlur={blurCheckOut}
          required
        />
      </label>
    </>
  );
}
