import assert from 'node:assert/strict'
import test from 'node:test'
import { renderToStaticMarkup } from 'react-dom/server'
import { SafeReportDocument } from '../src/features/report/SafeReportDocument.ts'
import { REPORT_SECURITY_FIXTURES } from './fixtures/reportSecurityFixtures.ts'

test('보고서 제목 중복을 숨기고 제목과 목록을 의미 요소로 렌더링한다', () => {
  const markup = renderToStaticMarkup(SafeReportDocument({
    documentTitle: '점자블록 결함 보고서',
    text: [
      '# 점자블록 결함 보고서',
      '',
      '## 요약',
      '미설치 결함을 확인했습니다.',
      '',
      '## 조치 권고',
      '- 현장을 다시 확인합니다.',
      '- 조치 결과를 기록합니다.',
    ].join('\n'),
  }))

  assert.equal(markup.startsWith('<div class="safe-report-document">'), true)
  assert.equal(markup.includes('# 점자블록 결함 보고서'), false)
  assert.equal(markup.includes('<h4>요약</h4>'), true)
  assert.equal(markup.includes('<h4>조치 권고</h4>'), true)
  assert.equal(
    markup.includes(
      '<ul><li>현장을 다시 확인합니다.</li>'
        + '<li>조치 결과를 기록합니다.</li></ul>',
    ),
    true,
  )
})

test('지원하지 않는 Markdown과 HTML은 실행하지 않고 일반 텍스트로 유지한다', () => {
  const text = [
    '## 근거',
    '[외부 링크](javascript:alert(document.domain))',
    ...REPORT_SECURITY_FIXTURES.map((fixture) => `- ${fixture.content}`),
  ].join('\n')
  const markup = renderToStaticMarkup(SafeReportDocument({ text }))

  assert.equal(markup.includes('<h5>근거</h5>'), true)
  assert.equal(markup.includes('<script'), false)
  assert.equal(markup.includes('<img'), false)
  assert.equal(markup.includes('<a '), false)
  assert.equal(markup.includes('<svg'), false)
  assert.equal(markup.includes('&lt;script&gt;'), true)
  assert.equal(
    markup.includes('[외부 링크](javascript:alert(document.domain))'),
    true,
  )
})

test('일반 문단의 줄바꿈은 보존한다', () => {
  const markup = renderToStaticMarkup(SafeReportDocument({
    text: '첫 줄\n둘째 줄',
  }))

  assert.equal(
    markup,
    '<div class="safe-report-document"><p>첫 줄\n둘째 줄</p></div>',
  )
})

test('숨김 대상으로 지정한 통계 섹션 전체를 렌더링하지 않는다', () => {
  const markup = renderToStaticMarkup(SafeReportDocument({
    documentTitle: '점자블록 결함 보고서',
    hiddenSectionTitles: ['통계'],
    text: [
      '# 점자블록 결함 보고서',
      '',
      '## 요약',
      '점검 결과입니다.',
      '',
      '## 통계',
      '- 임무: 전체 2건',
      '### 상태별 통계',
      '- 실패 1건',
      '',
      '## 주요 결함',
      '- 기간 내 주요 결함이 없습니다.',
    ].join('\n'),
  }))

  assert.equal(markup.includes('통계'), false)
  assert.equal(markup.includes('임무: 전체 2건'), false)
  assert.equal(markup.includes('상태별 통계'), false)
  assert.equal(markup.includes('<h4>주요 결함</h4>'), true)
})

test('주요 결함과 조치 권고의 필드 및 본문을 구분 기호 없는 줄로 렌더링한다', () => {
  const markup = renderToStaticMarkup(SafeReportDocument({
    text: [
      '## 주요 결함',
      '- 결함 ID: INC-20260805-000005 · 결함 유형: 미설치'
        + ' · 위험도: 보통 · 처리 상태: 접수 — 현장 재확인이 필요합니다.',
      '',
      '## 조치 권고',
      '- [조치 우선순위: 일반] 담당: 시설 유지보수 담당'
        + ' | 조치: 설치 상태를 점검하십시오.'
        + ' — 관련 결함 ID: INC-20260805-000005'
        + ' — 사진만으로 전체 상태를 확인하기 어렵습니다.',
    ].join('\n'),
  }))

  assert.equal(
    markup.includes(
      '<span class="safe-report-structured-field">'
        + '결함 ID: INC-20260805-000005</span>'
        + '<span class="safe-report-structured-field">결함 유형: 미설치</span>'
        + '<span class="safe-report-structured-field">위험도: 보통</span>'
        + '<span class="safe-report-structured-field">처리 상태: 접수</span>'
        + '<span class="safe-report-structured-body">현장 재확인이 필요합니다.</span>',
    ),
    true,
  )
  assert.equal(
    markup.includes(
      '<span class="safe-report-structured-field">조치 우선순위: 일반</span>'
        + '<span class="safe-report-structured-field">담당: 시설 유지보수 담당</span>'
        + '<span class="safe-report-structured-field">조치: 설치 상태를 점검하십시오.</span>'
        + '<span class="safe-report-structured-field">'
        + '관련 결함: INC-20260805-000005</span>'
        + '<span class="safe-report-structured-body">'
        + '사진만으로 전체 상태를 확인하기 어렵습니다.</span>',
    ),
    true,
  )
  assert.equal(markup.includes(' · '), false)
  assert.equal(markup.includes(' | '), false)
  assert.equal(markup.includes(' — '), false)
})
