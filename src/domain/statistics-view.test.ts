import { describe, expect, it } from 'vitest';

import { MULTI_SWATCH, NO_SUBCATEGORY, statisticsView } from './statistics-view';

const empty = {
  total: 0,
  categories: [],
  colors: [],
  brands: [],
  subcategories: {},
};

describe('statisticsView', () => {
  it('says an empty wardrobe is empty', () => {
    const view = statisticsView(empty);
    expect(view.isEmpty).toBe(true);
    expect(view.distinctCategories).toBe(0);
    expect(view.categories).toEqual([]);
  });

  it('counts distinct values, not garments', () => {
    const view = statisticsView({
      ...empty,
      total: 30,
      categories: [{ key: 'tops', count: 20 }, { key: 'shoes', count: 10 }],
      colors: [{ key: '#000000', count: 30 }],
      brands: [{ key: 'A', count: 1 }, { key: 'B', count: 1 }, { key: 'C', count: 1 }],
    });

    expect(view.distinctCategories).toBe(2);
    expect(view.distinctColors).toBe(1);
    expect(view.distinctBrands).toBe(3);
    expect(view.total).toBe(30);
    expect(view.isEmpty).toBe(false);
  });

  // ---- the arithmetic -------------------------------------------------------

  it('scales each bar against the largest in its own chart', () => {
    const view = statisticsView({
      ...empty,
      total: 10,
      categories: [{ key: 'tops', count: 8 }, { key: 'shoes', count: 2 }],
    });

    expect(view.categories[0].fraction).toBe(1);
    expect(view.categories[1].fraction).toBe(0.25);
  });

  it('scales the charts independently of one another', () => {
    // Brands here are far smaller than categories. Sharing a maximum would make
    // every brand bar a sliver.
    const view = statisticsView({
      ...empty,
      total: 100,
      categories: [{ key: 'tops', count: 100 }],
      brands: [{ key: 'A', count: 2 }, { key: 'B', count: 1 }],
    });

    expect(view.brands[0].fraction).toBe(1);
    expect(view.brands[1].fraction).toBe(0.5);
  });

  it('scales a subcategory group against its own largest, not the category chart', () => {
    const view = statisticsView({
      ...empty,
      total: 100,
      categories: [{ key: 'tops', count: 90 }, { key: 'shoes', count: 10 }],
      subcategories: {
        shoes: [{ key: 'Sneakers', count: 8 }, { key: 'Boots', count: 2 }],
      },
    });

    // Against the 10 in this group, not the 90 in the chart above it.
    expect(view.subcategories.shoes[0].fraction).toBe(1);
    expect(view.subcategories.shoes[1].fraction).toBe(0.25);
  });

  it('prefixes subcategory keys with their category', () => {
    // The same name appears under more than one category; bare keys collapse.
    const view = statisticsView({
      ...empty,
      total: 2,
      subcategories: {
        tops: [{ key: 'Other', count: 1 }],
        shoes: [{ key: 'Other', count: 1 }],
      },
    });

    expect(view.subcategories.tops[0].key).toBe('tops:Other');
    expect(view.subcategories.shoes[0].key).toBe('shoes:Other');
  });

  it('keeps the no-subcategory key for the caller to word', () => {
    const view = statisticsView({
      ...empty,
      total: 1,
      subcategories: { tops: [{ key: NO_SUBCATEGORY, count: 1 }] },
    });

    expect(view.subcategories.tops[0].key).toBe(`tops:${NO_SUBCATEGORY}`);
  });

  it('does not divide by zero when every count is zero', () => {
    const view = statisticsView({
      ...empty,
      total: 1,
      categories: [{ key: 'tops', count: 0 }],
    });

    expect(view.categories[0].fraction).toBe(0);
  });

  it('keeps a bar inside its track whatever the count says', () => {
    const view = statisticsView({
      ...empty,
      total: 1,
      categories: [{ key: 'negative', count: -5 }, { key: 'nan', count: Number.NaN }],
    });

    for (const bar of view.categories) {
      expect(bar.fraction).toBeGreaterThanOrEqual(0);
      expect(bar.fraction).toBeLessThanOrEqual(1);
    }
  });

  // ---- swatches -------------------------------------------------------------

  it('draws a named colour with the palette spelling, not the stored one', () => {
    const view = statisticsView({
      ...empty,
      total: 2,
      colors: [{ key: '#000000', count: 1 }, { key: '#000080', count: 1 }],
    });

    expect(view.colors[0].swatch).toBe('#000000');
    expect(view.colors[1].swatch).toBe('#000080');
  });

  it('matches a stored colour whatever case it was written in', () => {
    // A wardrobe holds both spellings; one swatch, not two. Uses a colour with
    // letters in it, since a hex of digits alone cannot tell the two cases apart.
    const lower = statisticsView({ ...empty, total: 1, colors: [{ key: '#ffffff', count: 1 }] });
    const upper = statisticsView({ ...empty, total: 1, colors: [{ key: '#FFFFFF', count: 1 }] });

    expect(lower.colors[0].swatch).toBe('#FFFFFF');
    expect(upper.colors[0].swatch).toBe('#FFFFFF');
  });

  it('gives the many-coloured swatch its own marker', () => {
    const multi = statisticsView({
      ...empty,
      total: 1,
      colors: [{ key: '#RAINBOW', count: 1 }],
    });
    expect(multi.colors[0].swatch).toBe(MULTI_SWATCH);
  });

  it('keeps an unnamed colour as it was stored', () => {
    const view = statisticsView({
      ...empty,
      total: 1,
      colors: [{ key: '#123456', count: 1 }],
    });
    expect(view.colors[0].swatch).toBe('#123456');
  });

  // ---- brand sort -----------------------------------------------------------

  it('leaves brands in the order they arrived, which is by count', () => {
    const view = statisticsView({
      ...empty,
      total: 3,
      brands: [{ key: 'Zara', count: 2 }, { key: 'Adidas', count: 1 }],
    });
    expect(view.brands.map(b => b.key)).toEqual(['Zara', 'Adidas']);
  });

  it('sorts brands by name when asked', () => {
    const view = statisticsView({
      ...empty,
      total: 3,
      brands: [{ key: 'Zara', count: 2 }, { key: 'Adidas', count: 1 }],
      brandSort: 'alpha',
    });
    expect(view.brands.map(b => b.key)).toEqual(['Adidas', 'Zara']);
  });

  it('sorting by name does not change the bars', () => {
    const byCount = statisticsView({
      ...empty,
      total: 3,
      brands: [{ key: 'Zara', count: 2 }, { key: 'Adidas', count: 1 }],
    });
    const byName = statisticsView({
      ...empty,
      total: 3,
      brands: [{ key: 'Zara', count: 2 }, { key: 'Adidas', count: 1 }],
      brandSort: 'alpha',
    });

    expect(byName.brands.find(b => b.key === 'Zara')!.fraction)
      .toBe(byCount.brands.find(b => b.key === 'Zara')!.fraction);
  });

  it('sorts accented names where a reader expects them', () => {
    const view = statisticsView({
      ...empty,
      total: 2,
      brands: [{ key: 'Zara', count: 1 }, { key: 'Étam', count: 1 }],
      brandSort: 'alpha',
    });
    // A code-point comparison would put É after Z.
    expect(view.brands.map(b => b.key)).toEqual(['Étam', 'Zara']);
  });
});
