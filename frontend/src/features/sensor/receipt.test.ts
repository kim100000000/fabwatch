import { describe, expect, it } from 'vitest'
import { noteReading } from './receipt'
import type { ReceiptBook } from './receipt'

describe('noteReading (센서 수신 판정)', () => {
  it('처음 보는 센서는 새 값이다', () => {
    const book: ReceiptBook = new Map()
    expect(noteReading(book, 1, '2026-10-09T03:00:00Z')).toBe(true)
  })

  it('같은 measuredAt 이 다시 오면 새 값이 아니다 (서버가 새 값을 못 만든 폴링 응답)', () => {
    const book: ReceiptBook = new Map()
    noteReading(book, 1, '2026-10-09T03:00:00Z')
    expect(noteReading(book, 1, '2026-10-09T03:00:00Z')).toBe(false)
  })

  it('measuredAt 이 바뀌면 새 값이고 센서별로 따로 추적한다', () => {
    const book: ReceiptBook = new Map()
    noteReading(book, 1, '2026-10-09T03:00:00Z')
    expect(noteReading(book, 1, '2026-10-09T03:00:02Z')).toBe(true)
    expect(noteReading(book, 2, '2026-10-09T03:00:00Z')).toBe(true)
  })

  it('측정값이 아직 없는(null) 센서는 처음 한 번만 수신으로 친다', () => {
    const book: ReceiptBook = new Map()
    expect(noteReading(book, 1, null)).toBe(true)
    expect(noteReading(book, 1, null)).toBe(false)
  })
})
