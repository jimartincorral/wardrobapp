import { useState, useEffect, useCallback } from 'react';
import { getAllGarments, createGarment, deleteGarment, updateGarment, markUnavailable, markAvailable } from '../services/garment-service';
import type { OccasionOption, SeasonOption } from '../constants/style-filters';
import {
  filterGarments,
  sortGarments,
  type GarmentSortOption,
} from '../domain/garment-filtering';
import type { Garment } from '../types';

export type { GarmentSortOption };

export function useGarments(filters?: {
  category?: string;
  subcategory?: string;
  search?: string;
  available_only?: boolean;
  season?: SeasonOption;
  occasion?: OccasionOption;
  brand?: string;
  size?: string;
  color?: string;
  sort?: GarmentSortOption;
}) {
  const [garments, setGarments] = useState<Garment[]>([]);
  const [loading, setLoading] = useState(true);
  const [count, setCount] = useState(0);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const items = await getAllGarments({
        category: filters?.category,
        search: filters?.search,
        available_only: filters?.available_only,
      });
      // Filtering and ordering live in src/domain/garment-filtering, where they
      // can be tested without React.
      const shown = sortGarments(filterGarments(items, filters ?? {}), filters?.sort);
      setGarments(shown);
      setCount(shown.length);
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
    filters?.subcategory,
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
