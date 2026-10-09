import { useMemo } from 'react'
import { processSeqFromLines } from '../floorMap'
import { useLineTree } from './useLineTree'

/**
 * 배치도 공정 순서용 processId → seq 맵.
 * 설비 응답에는 공정 순서가 없어, 이미 있는 GET /lines(공정 seq 포함)를 재사용한다 — 새 API 없음.
 * 조회 전/실패 시 빈 맵이라 배치는 processId 순으로 폴백한다.
 */
export function useProcessSeq(): Record<number, number> {
  const { lines } = useLineTree()
  return useMemo(() => processSeqFromLines(lines), [lines])
}
