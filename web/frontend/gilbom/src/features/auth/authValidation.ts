const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/u

export function unicodeLength(value: string): number {
  return Array.from(value).length
}

export function validateEmail(email: string): string | null {
  if (email.length === 0) {
    return '이메일을 입력해주세요.'
  }
  if (email !== email.trim()) {
    return '이메일 앞뒤에는 공백을 사용할 수 없습니다.'
  }
  if (email.length > 254 || !EMAIL_PATTERN.test(email)) {
    return '올바른 이메일 형식을 입력해주세요.'
  }
  return null
}

export function validatePassword(password: string): string | null {
  const length = unicodeLength(password)
  if (length < 10 || length > 24) {
    return '비밀번호는 10자 이상 24자 이하여야 합니다.'
  }
  return null
}

export function validateDisplayName(displayName: string): string | null {
  const length = unicodeLength(displayName)
  if (length < 1 || length > 100) {
    return '이름은 1자 이상 100자 이하여야 합니다.'
  }
  if (displayName !== displayName.trim()) {
    return '이름 앞뒤에는 공백을 사용할 수 없습니다.'
  }
  return null
}

export function validateTotp(code: string): string | null {
  return /^[0-9]{6}$/u.test(code)
    ? null
    : '인증 앱에 표시된 숫자 6자리를 입력해주세요.'
}

