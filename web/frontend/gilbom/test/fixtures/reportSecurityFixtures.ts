export const REPORT_SECURITY_FIXTURES = [
  {
    name: 'script element',
    content: '<script>globalThis.__reportXssExecuted = true</script>',
  },
  {
    name: 'image error handler',
    content: '<img src=x onerror="globalThis.__reportXssExecuted = true">',
  },
  {
    name: 'javascript protocol link',
    content: '<a href="javascript:alert(document.domain)">unsafe link</a>',
  },
  {
    name: 'svg event handler',
    content: '<svg onload="globalThis.__reportXssExecuted = true"></svg>',
  },
] as const
