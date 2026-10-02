import { legendLabel, powerTier, tierColor, tierLabel } from './power-tier';

describe('powerTier', () => {
  it('uses the slider steps as boundaries and grey for an unknown rating', () => {
    expect([3.7, 22, 49.9, 50, 150, 299, 300, 350].map(powerTier)).toEqual(['slow', 'standard', 'standard', 'fast', 'highPower', 'highPower', 'ultra', 'ultra']);
    expect(powerTier(undefined)).toBeNull();
    expect(tierColor(null)).toBe('#8a8f8c');
  });

  it('labels tiers and legend steps', () => {
    expect(tierLabel('ultra')).toBe('Ultraschnell (HPC)');
    expect(['slow', 'standard', 'fast', 'highPower', 'ultra'].map((tier) => legendLabel(tier as never))).toEqual(['<22', '22', '50', '150', '300+']);
  });
});
