import { describe, expect, it } from 'vitest';
import {
  extractGarmentImportDataFromHtml,
  normalizeImportUrl,
} from './url-import-service';

describe('normalizeImportUrl', () => {
  it('adds https to bare hostnames', () => {
    expect(normalizeImportUrl('shop.mango.com/product')).toBe('https://shop.mango.com/product');
  });

  it('rejects unsupported protocols', () => {
    expect(() => normalizeImportUrl('ftp://example.com/file')).toThrow('Only http and https URLs are supported.');
  });
});

describe('extractGarmentImportDataFromHtml', () => {
  it('extracts open graph images and metadata', () => {
    const html = `
      <html>
        <head>
          <meta property="og:title" content="Round-neck knit cape" />
          <meta property="og:site_name" content="Mango" />
          <meta property="og:image" content="https://static.mango.com/product-main.jpg" />
        </head>
      </html>
    `;

    const result = extractGarmentImportDataFromHtml(html, 'https://shop.mango.com/product');

    expect(result.title).toBe('Round-neck knit cape');
    expect(result.brand).toBe('Mango');
    expect(result.imageUrls).toEqual(['https://static.mango.com/product-main.jpg']);
    expect(result.parser).toBe('open-graph');
  });

  it('extracts product images from JSON-LD and normalizes relative URLs', () => {
    const html = `
      <html>
        <head>
          <script type="application/ld+json">
            {
              "@context": "https://schema.org",
              "@type": "Product",
              "name": "Soft cardigan",
              "brand": { "@type": "Brand", "name": "Mango" },
              "image": ["/images/cardigan-front.jpg", "https://cdn.example.com/cardigan-back.jpg"]
            }
          </script>
        </head>
      </html>
    `;

    const result = extractGarmentImportDataFromHtml(html, 'https://shop.mango.com/us/product?id=1');

    expect(result.title).toBe('Soft cardigan');
    expect(result.brand).toBe('Mango');
    expect(result.imageUrls).toEqual([
      'https://shop.mango.com/images/cardigan-front.jpg',
      'https://cdn.example.com/cardigan-back.jpg',
    ]);
    expect(result.parser).toBe('json-ld');
  });

  it('dedupes repeated images across parsers and ignores obvious logos', () => {
    const html = `
      <html>
        <head>
          <meta property="og:image" content="https://cdn.example.com/products/jacket.jpg" />
        </head>
        <body>
          <img src="https://cdn.example.com/assets/logo.png" />
          <img src="https://cdn.example.com/products/jacket.jpg" />
          <img data-src="/products/jacket-side.jpg" />
        </body>
      </html>
    `;

    const result = extractGarmentImportDataFromHtml(html, 'https://example.com/store/item');

    expect(result.imageUrls).toEqual([
      'https://cdn.example.com/products/jacket.jpg',
      'https://example.com/products/jacket-side.jpg',
    ]);
    expect(result.parser).toBe('mixed');
  });

  it('falls back to generic img tags when structured data is missing', () => {
    const html = `
      <html>
        <head><title>Test product</title></head>
        <body>
          <img srcset="/small.jpg 400w, /large.jpg 1200w" />
        </body>
      </html>
    `;

    const result = extractGarmentImportDataFromHtml(html, 'https://example.com/p/123');

    expect(result.title).toBe('Test product');
    expect(result.imageUrls).toEqual(['https://example.com/large.jpg']);
    expect(result.parser).toBe('html-images');
  });
});
