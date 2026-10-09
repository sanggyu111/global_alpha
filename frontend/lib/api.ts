import "server-only";

// 백엔드 주소는 서버에서만 쓴다 (NEXT_PUBLIC_ 이 아니므로 브라우저 번들에 들어가지 않음).
// 브라우저는 백엔드를 직접 부르지 않고 Next 서버(서버 컴포넌트·Server Action)를 거친다 → CORS 설정 불필요.
const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

export type ApiError = {
  code: string;
  message: string;
  details?: Record<string, unknown>;
};

export type ApiResult<T> =
  | { ok: true; data: T }
  | { ok: false; status: number; error: ApiError };

type Options = {
  method?: "GET" | "POST" | "PUT";
  body?: unknown;
  userId?: string;
  idempotencyKey?: string;
  /**
   * 지정하면 그 초만큼 캐시 후 재검증 (숙소 정보처럼 거의 안 바뀌는 데이터).
   * 지정하지 않으면 캐시하지 않는다 — 재고·예약·결제 상태는 초 단위로 변한다.
   */
  revalidate?: number;
};

export async function api<T>(path: string, options: Options = {}): Promise<ApiResult<T>> {
  const headers: Record<string, string> = {};
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (options.userId) headers["X-User-Id"] = options.userId;
  if (options.idempotencyKey) headers["Idempotency-Key"] = options.idempotencyKey;

  let response: Response;
  try {
    response = await fetch(BACKEND_URL + path, {
      method: options.method ?? "GET",
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      ...(options.revalidate !== undefined
        ? { next: { revalidate: options.revalidate } }
        : { cache: "no-store" as const }),
    });
  } catch {
    return { ok: false, status: 503, error: { code: "BACKEND_UNAVAILABLE", message: "서버에 연결할 수 없습니다." } };
  }

  const text = await response.text();
  let json: unknown = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    // JSON 이 아닌 응답 (게이트웨이 오류 페이지 등)
  }
  if (!response.ok) {
    const error = (json as ApiError | null) ?? {
      code: `HTTP_${response.status}`,
      message: "요청을 처리하지 못했습니다.",
    };
    return { ok: false, status: response.status, error };
  }
  return { ok: true, data: json as T };
}
