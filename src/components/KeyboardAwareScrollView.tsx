import React from 'react';
import { Platform, type ScrollViewProps } from 'react-native';
import { KeyboardAwareScrollView as KCKeyboardAwareScrollView } from 'react-native-keyboard-controller';

type KeyboardAwareScrollViewProps = ScrollViewProps & {
  /** Extra space kept between the focused input and the top of the keyboard. */
  bottomOffset?: number;
};

/**
 * Keyboard-aware ScrollView backed by react-native-keyboard-controller, the
 * approach Expo recommends for keyboard handling. Unlike the built-in
 * KeyboardAvoidingView, it works correctly under Expo SDK 55 edge-to-edge mode
 * (where the Android window no longer resizes for the keyboard) and
 * automatically scrolls the focused TextInput above the keyboard.
 *
 * Requires <KeyboardProvider> mounted near the app root (see app/_layout.tsx).
 */
export function KeyboardAwareScrollView({
  children,
  keyboardShouldPersistTaps = 'handled',
  keyboardDismissMode = Platform.OS === 'ios' ? 'interactive' : 'on-drag',
  bottomOffset = 24,
  ...props
}: KeyboardAwareScrollViewProps) {
  return (
    <KCKeyboardAwareScrollView
      {...props}
      bottomOffset={bottomOffset}
      keyboardShouldPersistTaps={keyboardShouldPersistTaps}
      keyboardDismissMode={keyboardDismissMode}
    >
      {children}
    </KCKeyboardAwareScrollView>
  );
}
