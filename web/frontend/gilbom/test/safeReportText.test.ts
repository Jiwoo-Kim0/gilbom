import assert from 'node:assert/strict'
import test from 'node:test'
import { renderToStaticMarkup } from 'react-dom/server'
import { SafeReportText } from '../src/features/report/SafeReportText.ts'
import { REPORT_SECURITY_FIXTURES } from './fixtures/reportSecurityFixtures.ts'

test('HTML과 스크립트 문자열을 실행 가능한 요소로 렌더링하지 않는다', () => {
  for (const fixture of REPORT_SECURITY_FIXTURES) {
    const markup = renderToStaticMarkup(SafeReportText({
      text: fixture.content,
    }))

    assert.equal(markup.startsWith('<div class="safe-report-text">'), true)
    assert.equal(markup.includes('<script'), false, fixture.name)
    assert.equal(markup.includes('<img'), false, fixture.name)
    assert.equal(markup.includes('<a '), false, fixture.name)
    assert.equal(markup.includes('<svg'), false, fixture.name)
    assert.equal(markup.includes('&lt;'), true, fixture.name)
  }
})

test('줄바꿈을 text node에 유지한다', () => {
  const markup = renderToStaticMarkup(SafeReportText({
    text: '첫 줄\n둘째 줄',
    as: 'p',
  }))

  assert.equal(
    markup,
    '<p class="safe-report-text">첫 줄\n둘째 줄</p>',
  )
})
