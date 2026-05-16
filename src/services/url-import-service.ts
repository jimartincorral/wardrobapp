export interface ImportedGarmentPreview {
  sourceUrl: string;
  title: string | null;
  brand: string | null;
  imageUrls: string[];
  downloadedImageUris: string[];
  warnings: string[];
  parser: 'open-graph' | 'json-ld' | 'html-images' | 'mixed' | 'none';
}

type ParserKind = ImportedGarmentPreview['parser'];

type ExtractedImportData = Omit<ImportedGarmentPreview, 'downloadedImageUris'>;

type JsonLike = Record<string, unknown>;

const IMAGE_BLOCKLIST_PATTERNS = [
  /\/logo/i,
  /\/icon/i,
  /favicon/i,
  /sprite/i,
  /avatar/i,
  /placeholder/i,
];

const SUPPORTED_IMAGE_EXTENSIONS = new Set([
  '.jpg',
  '.jpeg',
  '.png',
  '.webp',
  '.gif',
  '.bmp',
  '.avif',
]);

export function normalizeImportUrl(input: string): string {
  const trimmed = input.trim();
  if (!trimmed) {
    throw new Error('A URL is required.');
  }

  const withProtocol = /^[a-z][a-z0-9+.-]*:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`;
  const normalized = new URL(withProtocol);

  if (!['http:', 'https:'].includes(normalized.protocol)) {
    throw new Error('Only http and https URLs are supported.');
  }

  normalized.hash = '';
  return normalized.toString();
}

export async function importGarmentFromUrl(inputUrl: string): Promise<ImportedGarmentPreview> {
  const { downloadRemoteImageToTempFile } = await import('./image-service');
  const sourceUrl = normalizeImportUrl(inputUrl);
  const response = await fetch(sourceUrl, {
    headers: {
      Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
      'Accept-Language': 'en-US,en;q=0.8',
    },
  });

  if (!response.ok) {
    throw new Error(`Could not load page (${response.status}).`);
  }

  const html = await response.text();
  const extracted = extractGarmentImportDataFromHtml(html, sourceUrl);

  if (extracted.imageUrls.length === 0) {
    throw new Error('No garment images were found on that page.');
  }

  const downloadedResults = await Promise.allSettled(
    extracted.imageUrls.map(imageUrl => downloadRemoteImageToTempFile(imageUrl))
  );

  const downloadedImageUris = downloadedResults.flatMap(result =>
    result.status === 'fulfilled' ? [result.value] : []
  );
  const failedDownloads = downloadedResults.length - downloadedImageUris.length;

  const warnings = [...extracted.warnings];
  if (failedDownloads > 0) {
    warnings.push(`${failedDownloads} image${failedDownloads === 1 ? '' : 's'} could not be downloaded.`);
  }

  if (downloadedImageUris.length === 0) {
    throw new Error('Images were found, but none could be downloaded.');
  }

  return {
    ...extracted,
    downloadedImageUris,
    warnings,
  };
}

export function extractGarmentImportDataFromHtml(html: string, pageUrl: string): ExtractedImportData {
  const metaTags = extractTags(html, 'meta');
  const imageSet = new Set<string>();
  const parserSources = new Set<Exclude<ParserKind, 'none'>>();
  const warnings: string[] = [];

  const addImages = (images: string[], parser: Exclude<ParserKind, 'none'>) => {
    for (const imageUrl of images) {
      if (!imageSet.has(imageUrl)) {
        imageSet.add(imageUrl);
        parserSources.add(parser);
      }
    }
  };

  const openGraphImages = extractOpenGraphImages(metaTags, pageUrl);
  addImages(openGraphImages, 'open-graph');

  const jsonLdResult = extractJsonLdProductData(html, pageUrl);
  addImages(jsonLdResult.images, 'json-ld');
  warnings.push(...jsonLdResult.warnings);

  const imgTagImages = extractImageTagImages(html, pageUrl);
  addImages(imgTagImages, 'html-images');

  const title = firstNonEmpty(
    jsonLdResult.title,
    getMetaContent(metaTags, 'property', 'og:title'),
    getMetaContent(metaTags, 'name', 'twitter:title'),
    extractDocumentTitle(html)
  );

  const brand = firstNonEmpty(
    jsonLdResult.brand,
    getMetaContent(metaTags, 'property', 'product:brand'),
    getMetaContent(metaTags, 'property', 'og:site_name'),
    hostnameToBrand(pageUrl)
  );

  return {
    sourceUrl: normalizeImportUrl(pageUrl),
    title: title ? decodeHtmlEntities(title) : null,
    brand: brand ? prettifyBrand(brand) : null,
    imageUrls: [...imageSet],
    warnings,
    parser: resolveParser(parserSources),
  };
}

function resolveParser(sources: Set<Exclude<ParserKind, 'none'>>): ParserKind {
  if (sources.size === 0) return 'none';
  if (sources.size > 1) return 'mixed';
  return [...sources][0];
}

function extractOpenGraphImages(metaTags: string[], pageUrl: string): string[] {
  const candidates = [
    getMetaContent(metaTags, 'property', 'og:image'),
    getMetaContent(metaTags, 'property', 'og:image:secure_url'),
    getMetaContent(metaTags, 'name', 'twitter:image'),
    getMetaContent(metaTags, 'name', 'twitter:image:src'),
  ].filter(Boolean) as string[];

  return normalizeImageUrls(candidates, pageUrl);
}

function extractJsonLdProductData(html: string, pageUrl: string): {
  title: string | null;
  brand: string | null;
  images: string[];
  warnings: string[];
} {
  const warnings: string[] = [];
  let title: string | null = null;
  let brand: string | null = null;
  const images: string[] = [];

  const scriptRegex = /<script\b[^>]*type=(["'])application\/ld\+json\1[^>]*>([\s\S]*?)<\/script>/gi;
  let match: RegExpExecArray | null;

  while ((match = scriptRegex.exec(html)) !== null) {
    const rawJson = decodeHtmlEntities(match[2]).trim();
    if (!rawJson) continue;

    try {
      const parsed = JSON.parse(rawJson) as unknown;
      const nodes = flattenJsonLdNodes(parsed);

      for (const node of nodes) {
        if (!looksLikeProductNode(node)) continue;

        title = title ?? stringOrNull(node.name);
        brand = brand ?? extractBrand(node.brand);

        const nodeImages = normalizeImageUrls(extractImageValues(node.image), pageUrl);
        for (const imageUrl of nodeImages) {
          if (!images.includes(imageUrl)) images.push(imageUrl);
        }
      }
    } catch {
      warnings.push('Some structured product data could not be parsed.');
    }
  }

  return { title, brand, images, warnings };
}

function flattenJsonLdNodes(value: unknown): JsonLike[] {
  if (Array.isArray(value)) {
    return value.flatMap(item => flattenJsonLdNodes(item));
  }

  if (!isRecord(value)) {
    return [];
  }

  const graph = value['@graph'];
  if (Array.isArray(graph)) {
    return [value, ...graph.flatMap(item => flattenJsonLdNodes(item))];
  }

  return [value];
}

function looksLikeProductNode(node: JsonLike): boolean {
  const typeValue = node['@type'];
  if (typeof typeValue === 'string') {
    return typeValue.toLowerCase().includes('product');
  }

  if (Array.isArray(typeValue)) {
    return typeValue.some(item => typeof item === 'string' && item.toLowerCase().includes('product'));
  }

  return false;
}

function extractBrand(value: unknown): string | null {
  if (typeof value === 'string') return value;
  if (isRecord(value)) {
    return stringOrNull(value.name) ?? stringOrNull(value['@id']);
  }
  if (Array.isArray(value)) {
    for (const item of value) {
      const extracted = extractBrand(item);
      if (extracted) return extracted;
    }
  }
  return null;
}

function extractImageValues(value: unknown): string[] {
  if (typeof value === 'string') return [value];
  if (Array.isArray(value)) {
    return value.flatMap(item => extractImageValues(item));
  }
  if (isRecord(value)) {
    return [
      stringOrNull(value.url),
      stringOrNull(value.contentUrl),
      stringOrNull(value['@id']),
    ].filter(Boolean) as string[];
  }
  return [];
}

function extractImageTagImages(html: string, pageUrl: string): string[] {
  const imageTags = extractTags(html, 'img');
  const imageUrls: string[] = [];

  for (const tag of imageTags) {
    const attrs = parseTagAttributes(tag);
    const candidates = [
      attrs.src,
      attrs['data-src'],
      attrs['data-original'],
      attrs['data-image'],
      attrs['data-zoom'],
      pickLargestSrcsetCandidate(attrs.srcset),
      pickLargestSrcsetCandidate(attrs['data-srcset']),
    ].filter(Boolean) as string[];

    for (const imageUrl of normalizeImageUrls(candidates, pageUrl)) {
      if (!imageUrls.includes(imageUrl)) {
        imageUrls.push(imageUrl);
      }
    }
  }

  return imageUrls;
}

function pickLargestSrcsetCandidate(srcset?: string): string | null {
  if (!srcset) return null;

  const candidates = srcset
    .split(',')
    .map(item => item.trim())
    .map(item => {
      const [url, descriptor] = item.split(/\s+/, 2);
      const width = descriptor?.endsWith('w') ? Number.parseInt(descriptor, 10) : 0;
      return { url, width: Number.isFinite(width) ? width : 0 };
    })
    .filter(item => item.url);

  if (candidates.length === 0) return null;
  candidates.sort((a, b) => b.width - a.width);
  return candidates[0].url;
}

function normalizeImageUrls(candidates: string[], pageUrl: string): string[] {
  const normalized: string[] = [];

  for (const candidate of candidates) {
    const normalizedCandidate = normalizeImageUrl(candidate, pageUrl);
    if (!normalizedCandidate) continue;
    if (!normalized.includes(normalizedCandidate)) {
      normalized.push(normalizedCandidate);
    }
  }

  return normalized;
}

function normalizeImageUrl(candidate: string, pageUrl: string): string | null {
  const trimmed = candidate.trim();
  if (!trimmed || trimmed.startsWith('data:')) return null;

  try {
    const absolute = new URL(trimmed, pageUrl);
    if (!['http:', 'https:'].includes(absolute.protocol)) return null;
    if (IMAGE_BLOCKLIST_PATTERNS.some(pattern => pattern.test(absolute.pathname))) return null;

    const extension = absolute.pathname.match(/\.[a-z0-9]+$/i)?.[0]?.toLowerCase();
    if (extension && !SUPPORTED_IMAGE_EXTENSIONS.has(extension)) return null;

    absolute.hash = '';
    return absolute.toString();
  } catch {
    return null;
  }
}

function extractTags(html: string, tagName: string): string[] {
  const regex = new RegExp(`<${tagName}\\b[^>]*>`, 'gi');
  return html.match(regex) ?? [];
}

function parseTagAttributes(tag: string): Record<string, string> {
  const attrs: Record<string, string> = {};
  const attrRegex = /([:@a-zA-Z0-9_-]+)\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))/g;
  let match: RegExpExecArray | null;

  while ((match = attrRegex.exec(tag)) !== null) {
    const key = match[1].toLowerCase();
    const value = match[3] ?? match[4] ?? match[5] ?? '';
    attrs[key] = decodeHtmlEntities(value);
  }

  return attrs;
}

function getMetaContent(metaTags: string[], attribute: 'property' | 'name', key: string): string | null {
  const loweredKey = key.toLowerCase();

  for (const tag of metaTags) {
    const attrs = parseTagAttributes(tag);
    if ((attrs[attribute] ?? '').toLowerCase() === loweredKey) {
      return attrs.content ?? null;
    }
  }

  return null;
}

function extractDocumentTitle(html: string): string | null {
  const match = html.match(/<title\b[^>]*>([\s\S]*?)<\/title>/i);
  return match?.[1]?.trim() || null;
}

function hostnameToBrand(pageUrl: string): string | null {
  try {
    const hostname = new URL(pageUrl).hostname.toLowerCase();
    const parts = hostname.split('.').filter(Boolean);
    if (parts.length < 2) return hostname;
    return parts[parts.length - 2];
  } catch {
    return null;
  }
}

function prettifyBrand(brand: string): string {
  return brand
    .trim()
    .replace(/^www\./i, '')
    .split(/[\s._-]+/)
    .filter(Boolean)
    .map(part => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ');
}

function firstNonEmpty(...values: Array<string | null | undefined>): string | null {
  for (const value of values) {
    if (typeof value === 'string' && value.trim()) {
      return value.trim();
    }
  }
  return null;
}

function stringOrNull(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value.trim() : null;
}

function isRecord(value: unknown): value is JsonLike {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function decodeHtmlEntities(value: string): string {
  return value
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&nbsp;/g, ' ');
}
