/**
 * Charging-speed bands of the pin colour, the same as the iOS `ChargingPowerTier` (ADR 0009): blue below 22 kW,
 * green from 22, yellow from 50, orange from 150, red from 300. A station without a rating has no tier and is grey —
 * "unknown" is not "slow".
 */
export type PowerTier = 'slow' | 'standard' | 'fast' | 'highPower' | 'ultra';

export const POWER_TIERS: readonly { tier: PowerTier; lowerBoundKw: number }[] = [
  { tier: 'slow', lowerBoundKw: 0 },
  { tier: 'standard', lowerBoundKw: 22 },
  { tier: 'fast', lowerBoundKw: 50 },
  { tier: 'highPower', lowerBoundKw: 150 },
  { tier: 'ultra', lowerBoundKw: 300 },
];

export function powerTier(powerKw: number | undefined | null): PowerTier | null {
  if (powerKw === undefined || powerKw === null) return null;
  let tier: PowerTier = 'slow';
  for (const step of POWER_TIERS) if (powerKw >= step.lowerBoundKw) tier = step.tier;
  return tier;
}

/** Pin colours; readable on both the light and the dark map. Grey stands for "no rating reported". */
export function tierColor(tier: PowerTier | null): string {
  switch (tier) {
    case 'slow':
      return '#2f6fde';
    case 'standard':
      return '#2e9d4f';
    case 'fast':
      return '#d9a400';
    case 'highPower':
      return '#e8711a';
    case 'ultra':
      return '#d42a2a';
    case null:
      return '#8a8f8c';
  }
}

export function tierLabel(tier: PowerTier): string {
  switch (tier) {
    case 'slow':
      return $localize`:@@power.tier.slow:Langsamladen (AC)`;
    case 'standard':
      return $localize`:@@power.tier.standard:Normalladen (AC)`;
    case 'fast':
      return $localize`:@@power.tier.fast:Schnellladen (DC)`;
    case 'highPower':
      return $localize`:@@power.tier.highPower:Highpower (DC)`;
    case 'ultra':
      return $localize`:@@power.tier.ultra:Ultraschnell (HPC)`;
  }
}

/** Legend thresholds as bare numerals; the legend prints the unit once. */
export function legendLabel(tier: PowerTier): string {
  switch (tier) {
    case 'slow':
      return '<22';
    case 'ultra':
      return '300+';
    default:
      return String(POWER_TIERS.find((step) => step.tier === tier)!.lowerBoundKw);
  }
}
