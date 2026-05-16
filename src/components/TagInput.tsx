import React, { useEffect, useMemo, useState } from 'react';
import { View, Text, TextInput, Pressable, ScrollView, StyleSheet } from 'react-native';
import { Spacing, BorderRadius, FontSize } from '../constants/theme';
import { COMMON_TAGS, COMMON_TAGS_ES } from '../constants/categories';
import { useTranslation } from '../i18n';
import { useTheme } from '../theme';
import { getExistingTags } from '../services/garment-service';
import type { ThemeColors } from '../theme';

interface TagInputProps {
  tags: string[];
  onTagsChange: (tags: string[]) => void;
  placeholder?: string;
}

export function TagInput({ tags, onTagsChange, placeholder }: TagInputProps) {
  const { t, language } = useTranslation();
  const { colors } = useTheme();
  const styles = createStyles(colors);
  const [input, setInput] = useState('');
  const [showSuggestions, setShowSuggestions] = useState(false);
  const [existingSuggestions, setExistingSuggestions] = useState<string[]>([]);

  const effectivePlaceholder = placeholder ?? t('tagInput.placeholder');

  useEffect(() => {
    let cancelled = false;

    const loadExistingTags = async () => {
      try {
        const existingTags = await getExistingTags();
        if (!cancelled) setExistingSuggestions(existingTags);
      } catch (error) {
        console.warn('Failed to load tag suggestions:', error);
      }
    };

    loadExistingTags();

    return () => {
      cancelled = true;
    };
  }, []);

  const availableSuggestions = useMemo(() => {
    const defaults = language === 'es' ? COMMON_TAGS_ES : COMMON_TAGS;
    const seen = new Set<string>();
    const merged: string[] = [];

    for (const tag of [...existingSuggestions, ...defaults]) {
      const trimmed = tag.trim();
      const normalized = trimmed.toLowerCase();
      if (!trimmed || seen.has(normalized)) continue;
      seen.add(normalized);
      merged.push(trimmed);
    }

    return merged;
  }, [existingSuggestions, language]);
  const existingTags = new Set(tags.map(tag => tag.trim().toLowerCase()));

  const filteredSuggestions = availableSuggestions.filter(
    tag => tag.toLowerCase().includes(input.toLowerCase()) && !existingTags.has(tag.trim().toLowerCase())
  ).slice(0, 8);

  const addTag = (tag: string) => {
    const trimmed = tag.trim();
    if (trimmed && !existingTags.has(trimmed.toLowerCase())) onTagsChange([...tags, trimmed]);
    setInput('');
    setShowSuggestions(false);
  };

  const removeTag = (tag: string) => onTagsChange(tags.filter(t => t !== tag));

  return (
    <View>
      <View style={styles.tagsContainer}>
        {tags.map(tag => (
          <Pressable key={tag} style={styles.tag} onPress={() => removeTag(tag)}>
            <Text style={styles.tagText}>{tag}</Text>
            <Text style={styles.tagRemove}> ×</Text>
          </Pressable>
        ))}
      </View>
      <TextInput
        style={styles.input}
        value={input}
        onChangeText={text => { setInput(text); setShowSuggestions(text.length > 0); }}
        onFocus={() => setShowSuggestions(true)}
        onBlur={() => setTimeout(() => setShowSuggestions(false), 200)}
        onSubmitEditing={() => addTag(input)}
        placeholder={effectivePlaceholder}
        placeholderTextColor={colors.textTertiary}
        returnKeyType="done"
      />
      {showSuggestions && filteredSuggestions.length > 0 && (
        <ScrollView
          horizontal
          style={styles.suggestions}
          showsHorizontalScrollIndicator={false}
          keyboardShouldPersistTaps="handled"
        >
          {filteredSuggestions.map(tag => (
            <Pressable key={tag} style={styles.suggestion} onPressIn={() => addTag(tag)}>
              <Text style={styles.suggestionText}>{tag}</Text>
            </Pressable>
          ))}
        </ScrollView>
      )}
    </View>
  );
}

const createStyles = (colors: ThemeColors) => StyleSheet.create({
  tagsContainer: { flexDirection: 'row', flexWrap: 'wrap', gap: Spacing.xs, marginBottom: Spacing.sm },
  tag: { flexDirection: 'row', backgroundColor: colors.primary, borderRadius: BorderRadius.full, paddingHorizontal: Spacing.md, paddingVertical: Spacing.xs },
  tagText: { color: '#fff', fontSize: FontSize.sm },
  tagRemove: { color: 'rgba(255,255,255,0.7)', fontSize: FontSize.sm, fontWeight: '600' },
  input: { backgroundColor: colors.surfaceVariant, borderRadius: BorderRadius.sm, paddingHorizontal: Spacing.md, paddingVertical: Spacing.sm, fontSize: FontSize.md, color: colors.text },
  suggestions: { marginTop: Spacing.xs, maxHeight: 40 },
  suggestion: { backgroundColor: colors.surfaceVariant, borderRadius: BorderRadius.full, paddingHorizontal: Spacing.md, paddingVertical: Spacing.xs, marginRight: Spacing.xs },
  suggestionText: { fontSize: FontSize.sm, color: colors.primary },
});
