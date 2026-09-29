/** PKCE (RFC 7636) and `state` values for the authorization-code flow, from the Web Crypto API. */

export function randomToken(bytes = 32): string {
  const buffer = new Uint8Array(bytes);
  crypto.getRandomValues(buffer);
  return base64Url(buffer);
}

/** `code_challenge` for `code_challenge_method=S256`. */
export async function codeChallenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return base64Url(new Uint8Array(digest));
}

export function base64Url(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCodePoint(byte);
  // Padding is at most two characters, so strip it without a backtracking pattern.
  const base64 = btoa(binary).replaceAll('+', '-').replaceAll('/', '_');
  let end = base64.length;
  while (end > 0 && base64[end - 1] === '=') end--;
  return base64.slice(0, end);
}
