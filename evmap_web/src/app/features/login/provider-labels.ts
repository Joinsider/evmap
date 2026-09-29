import { ProviderToken } from '../../core/api/models';

/** Button labels. Provider names stay untranslated; the verb around them does not. */
export function signInLabel(provider: ProviderToken): string {
  switch (provider) {
    case 'apple':
      return $localize`:@@login.withApple:Mit Apple anmelden`;
    case 'google':
      return $localize`:@@login.withGoogle:Mit Google anmelden`;
    case 'github':
      return $localize`:@@login.withGitHub:Mit GitHub anmelden`;
  }
}
