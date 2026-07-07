import { useState, useEffect, useCallback } from 'react';
import { getAllGarments, createGarment, deleteGarment, updateGarment, markUnavailable, markAvailable } from '../services/garment-service';
import type { OccasionOption, SeasonOption, WeatherOption } from '../constants/style-filters';
import type { Garment } from '../types';

export type GarmentSortOption = 'newest' | 'oldest';

export function useGarments(filters?: {
  category?: string;
  search?: string;
  available_only?: boolean;
  season?: SeasonOption;
  weather?: WeatherOption;
  occasion?: OccasionOption;
  brand?: string;
  size?: string;
  color?: string;
  sort?: GarmentSortOption;
}) {
  const [garments, setGarments] = useState<Garment[]>([]);
  const [loading, setLoading] = useState(true);
  const [count, setCount] = useState(0);

  const applyFilters = (items: Garment[]) => {
    return items.filter(item => {
      const normalizedTags = item.tags.map(tag => tag.toLowerCase());
      if (filters?.season && !normalizedTags.includes(filters.season)) return false;
      if (filters?.weather && !normalizedTags.includes(filters.weather)) return false;
      if (filters?.occasion && !normalizedTags.includes(filters.occasion)) return false;
      if (filters?.brand && !(item.brand || '').toLowerCase().includes(filters.brand.toLowerCase().trim())) {
        return false;
      }
      if (filters?.size && !(item.size || '').toLowerCase().includes(filters.size.toLowerCase().trim())) {
        return false;
      }
      if (filters?.color) {
        const palette = item.color_palette.length > 0 ? item.color_palette : [item.color_primary];
        if (!palette.includes(filters.color)) return false;
      }
      return true;
    });
  };

  const applySort = async (items: Garment[]) => {
    const sort = filters?.sort ?? 'newest';
    const sorted = [...items];

    if (sort === 'newest') {
      return sorted.sort((a, b) => b.created_at.localeCompare(a.created_at));
    }

    if (sort === 'oldest') {
      return sorted.sort((a, b) => a.created_at.localeCompare(b.created_at));
    }

    return sorted;
  };

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const items = await getAllGarments({
        category: filters?.category,
        search: filters?.search,
        available_only: filters?.available_only,
      });
      const filtered = applyFilters(items);
      const sorted = await applySort(filtered);
      setGarments(sorted);
      setCount(sorted.length);
    } catch (e) {
      console.error('Failed to load garments:', e);
    } finally {
      setLoading(false);
    }
  }, [
    filters?.available_only,
    filters?.brand,
    filters?.category,
    filters?.color,
    filters?.occasion,
    filters?.search,
    filters?.season,
    filters?.size,
    filters?.sort,
    filters?.weather,
  ]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return { garments, loading, count, refresh };
}

export function useGarmentActions() {
  return {
    create: createGarment,
    update: updateGarment,
    remove: deleteGarment,
    markUnavailable,
    markAvailable,
  };
}

