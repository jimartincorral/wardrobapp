import { SUBCATEGORY_KEY_MAP } from '../constants/categories';

type Translate = (key: string, options?: Record<string, any>) => string;

export function localizeSubcategory(subcategory: string | null | undefined, t: Translate): string | null {
  if (!subcategory) return null;
  const key = SUBCATEGORY_KEY_MAP[subcategory];
  return key ? t(`subcategories.${key}`) : subcategory;
}

export function localizeSubcategories(subcategories: string[] | null | undefined, t: Translate): string[] {
  return (subcategories ?? [])
    .map(subcategory => localizeSubcategory(subcategory, t))
    .filter((subcategory): subcategory is string => Boolean(subcategory));
}

export function localizeGarmentLabel(
  category: string,
  subcategory: string | string[] | null | undefined,
  t: Translate
): string {
  if (Array.isArray(subcategory)) {
    const localized = localizeSubcategories(subcategory, t);
    return localized[0] ?? t(`categories.${category}`);
  }

  return localizeSubcategory(subcategory, t) ?? t(`categories.${category}`);
}
