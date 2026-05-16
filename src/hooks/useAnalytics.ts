import { useState, useEffect, useCallback } from 'react';
import {
  getCategoryDistribution,
  getGarmentLifespan,
} from '../services/analytics-service';
import { getGarmentCount, getUnavailableGarmentCount } from '../services/garment-service';
import type { Garment } from '../types';

export function useAnalytics() {
  const [loading, setLoading] = useState(true);
  const [data, setData] = useState<{
    categoryDistribution: { category: string; count: number }[];
    totalItems: number;
    archivedItems: number;
    garmentLifespan: { garment: Garment; days: number }[];
  }>({
    categoryDistribution: [],
    totalItems: 0,
    archivedItems: 0,
    garmentLifespan: [],
  });

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const [
        categoryDistribution, totalItems, archivedItems, garmentLifespan,
      ] = await Promise.all([
        getCategoryDistribution(),
        getGarmentCount(),
        getUnavailableGarmentCount(),
        getGarmentLifespan(),
      ]);

      setData({
        categoryDistribution,
        totalItems,
        archivedItems,
        garmentLifespan,
      });
    } catch (e) {
      console.error('Failed to load analytics:', e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return { ...data, loading, refresh };
}
