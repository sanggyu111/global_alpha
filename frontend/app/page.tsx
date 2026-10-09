import Link from "next/link";

import { api } from "@/lib/api";
import { seoulDate } from "@/lib/format";
import type { PropertySummary } from "@/lib/types";

// 숙소 목록은 거의 바뀌지 않으므로 60초 캐시 후 재검증 (README Q5).
// 반면 객실 가용 재고는 숙소 상세 페이지에서 매번 새로 조회한다.
const PROPERTY_REVALIDATE_SECONDS = 60;

export default async function HomePage({ searchParams }: { searchParams: Promise<{ region?: string }> }) {
  const { region } = await searchParams;
  const query = region ? `?region=${encodeURIComponent(region)}` : "";
  const result = await api<PropertySummary[]>(`/api/properties${query}`, {
    revalidate: PROPERTY_REVALIDATE_SECONDS,
  });

  // 기본 검색 조건: 일주일 뒤 1박, 2명
  const defaults = new URLSearchParams({ checkIn: seoulDate(7), checkOut: seoulDate(8), guests: "2" });

  return (
    <>
      <h1>숙소</h1>
      <form className="inline">
        <label>
          지역
          <select name="region" defaultValue={region ?? ""}>
            <option value="">전체</option>
            <option value="서울">서울</option>
            <option value="부산">부산</option>
            <option value="제주">제주</option>
          </select>
        </label>
        <button type="submit">보기</button>
      </form>
      <p />
      {!result.ok ? (
        <p className="error">{result.error.message}</p>
      ) : result.data.length === 0 ? (
        <p>숙소가 없습니다.</p>
      ) : (
        result.data.map((p) => (
          <div className="card" key={p.id}>
            <h2>
              <Link href={`/properties/${p.id}?${defaults}`}>{p.name}</Link>
            </h2>
            <div className="muted">
              {p.region} · {p.address}
            </div>
          </div>
        ))
      )}
    </>
  );
}
