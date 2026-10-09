/**
 * 센서 수신 시각 추적 — '끊김(stale)' 판정을 서버 measuredAt 과 브라우저 시계 비교가 아니라
 * **브라우저가 새 값을 받은 시각**으로 하기 위한 순수 로직.
 * (두 시계의 오차가 크면 정상 설비도 끊김으로 보이던 문제를 없앤다.)
 *
 * book 은 sensorId → 마지막으로 본 measuredAt. 같은 measuredAt 이 또 오면(서버가 새 값을 못 만든 폴링 응답 등)
 * 새로 받은 값이 아니므로 수신으로 치지 않는다.
 */
export type ReceiptBook = Map<number, string | null>

/** 이 측정값이 이전에 본 적 없는 새 값이면 true 를 돌려주고 book 을 갱신한다 */
export function noteReading(book: ReceiptBook, sensorId: number, measuredAt: string | null): boolean {
  const fresh = !book.has(sensorId) || book.get(sensorId) !== measuredAt
  if (fresh) book.set(sensorId, measuredAt)
  return fresh
}
