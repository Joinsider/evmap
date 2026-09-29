import { base64Url, codeChallenge, randomToken } from './pkce';

describe('pkce', () => {
  it('derives the S256 challenge of RFC 7636, appendix B', async () => {
    expect(await codeChallenge('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk')).toBe('E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
  });

  it('encodes URL-safe without padding', () => {
    expect(base64Url(new Uint8Array([251, 255, 191]))).toBe('-_-_');
    expect(base64Url(new Uint8Array([1]))).toBe('AQ');
  });

  it('makes a fresh, URL-safe token every time', () => {
    const first = randomToken();
    expect(first).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(randomToken()).not.toBe(first);
  });
});
