import { describe, expect, it } from 'vitest';
import { localizeGarmentLabel, localizeSubcategories, localizeSubcategory } from './localization-helpers';

const t = (key: string) => `translated:${key}`;

describe('localization helpers', () => {
  it('localizes known subcategories through the i18n key map', () => {
    expect(localizeSubcategory('T-Shirt', t)).toBe('translated:subcategories.tshirt');
  });

  it('localizes newly added underwear subcategories through the i18n key map', () => {
    expect(localizeSubcategory('Bodysuit', t)).toBe('translated:subcategories.bodysuit');
  });

  it('localizes newly added loungewear subcategories through the i18n key map', () => {
    expect(localizeSubcategory('Pajama Set', t)).toBe('translated:subcategories.pajama_set');
  });

  it('returns raw subcategory when no key map is found', () => {
    expect(localizeSubcategory('Unmapped Item', t)).toBe('Unmapped Item');
  });

  it('falls back to localized category label when subcategory is missing', () => {
    expect(localizeGarmentLabel('tops', null, t)).toBe('translated:categories.tops');
  });

  it('localizes multiple subcategories and preserves order', () => {
    expect(localizeSubcategories(['T-Shirt', 'Hoodie'], t)).toEqual([
      'translated:subcategories.tshirt',
      'translated:subcategories.hoodie',
    ]);
  });

  it('uses the first localized subcategory for garment labels when given a list', () => {
    expect(localizeGarmentLabel('tops', ['Hoodie', 'T-Shirt'], t)).toBe('translated:subcategories.hoodie');
  });
});
