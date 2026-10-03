package com.wardrobapp.presentation

/**
 * The three screens somebody sees before Home, in order.
 *
 * An enum rather than three navigation routes because the flow has exactly one
 * shape -- forward, or out -- and holding it here means the answer to "what does
 * Next do on the last screen" is written once and tested without a device. The
 * screen that renders a step reads [next] and [previous]; it decides nothing.
 *
 * The order is the argument the flow makes: what the app is, then the one thing
 * every wardrobe starts with, then what ratings buy. Reordering the entries
 * reorders the flow, which is why they are not sorted alphabetically or by name.
 */
enum class OnboardingStep {
    WELCOME,
    ADDING,
    LEARNING,
}

/**
 * The step a forward button leads to, or null when the flow is over.
 *
 * Null rather than wrapping round to [OnboardingStep.WELCOME]: the last screen's
 * button dismisses the flow, and a cycle would make that the caller's problem to
 * remember.
 */
val OnboardingStep.next: OnboardingStep?
    get() = OnboardingStep.entries.getOrNull(ordinal + 1)

/**
 * The step back leads to, or null when back leaves the flow.
 *
 * Null on the first screen is the same answer "Not now" gives, and deliberately:
 * back out of a flow that has not been agreed to is not a way *into* the app that
 * has to be different from declining it.
 */
val OnboardingStep.previous: OnboardingStep?
    get() = OnboardingStep.entries.getOrNull(ordinal - 1)
