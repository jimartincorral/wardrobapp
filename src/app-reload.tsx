import { createContext, useContext } from 'react';

/**
 * Soft app reload. Remounts the whole navigation tree (via a key bump in the
 * root layout), which makes every screen re-run its data fetching. Used after a
 * backup restore so the restored wardrobe shows immediately, without forcing the
 * user to kill and reopen the app. The database connection is a module-level
 * singleton that already points at the restored file, so a remount is enough.
 */
const AppReloadContext = createContext<() => void>(() => {});

export const AppReloadProvider = AppReloadContext.Provider;

export function useAppReload(): () => void {
  return useContext(AppReloadContext);
}
