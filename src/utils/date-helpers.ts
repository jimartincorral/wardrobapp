import { formatDistanceToNow, differenceInDays, format, parseISO } from 'date-fns';

export function timeAgo(dateString: string): string {
  return formatDistanceToNow(parseISO(dateString), { addSuffix: true });
}

export function daysSince(dateString: string): number {
  return differenceInDays(new Date(), parseISO(dateString));
}

export function formatDate(dateString: string): string {
  return format(parseISO(dateString), 'MMM d, yyyy');
}

export function formatShortDate(dateString: string): string {
  return format(parseISO(dateString), 'MMM d');
}

export function getCurrentSeason(): string {
  const month = new Date().getMonth();
  if (month >= 2 && month <= 4) return 'spring';
  if (month >= 5 && month <= 7) return 'summer';
  if (month >= 8 && month <= 10) return 'fall';
  return 'winter';
}
