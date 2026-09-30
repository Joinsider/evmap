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

/** The provider as a name, for listing linked sign-ins. Proper names, so nothing to translate. */
export function providerName(provider: ProviderToken): string {
  switch (provider) {
    case 'apple':
      return 'Apple';
    case 'google':
      return 'Google';
    case 'github':
      return 'GitHub';
  }
}
