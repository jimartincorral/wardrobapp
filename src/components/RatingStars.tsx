import React from 'react';
import { View, Pressable, Text, StyleSheet } from 'react-native';
import { Spacing } from '../constants/theme';
import { useTheme } from '../theme';

interface RatingStarsProps {
  rating: number;
  onRate?: (rating: number) => void;
  size?: number;
  readonly?: boolean;
}

export function RatingStars({ rating, onRate, size = 28, readonly = false }: RatingStarsProps) {
  const { colors } = useTheme();
  return (
    <View style={styles.container}>
      {[1, 2, 3, 4, 5].map((star) => (
        <Pressable
          key={star}
          onPress={() => !readonly && onRate?.(star)}
          disabled={readonly}
          hitSlop={8}
        >
          <Text style={[styles.star, { fontSize: size, color: star <= rating ? '#FFD700' : colors.border }]}> 
            {star <= rating ? '\u2605' : '\u2606'}
          </Text>
        </Pressable>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    gap: Spacing.xs,
    alignItems: 'center',
  },
  star: {
    lineHeight: undefined,
  },
});
