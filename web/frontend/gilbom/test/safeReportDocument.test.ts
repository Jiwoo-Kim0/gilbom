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
