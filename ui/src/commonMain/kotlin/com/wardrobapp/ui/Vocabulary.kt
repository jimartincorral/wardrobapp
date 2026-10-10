package com.wardrobapp.ui

import androidx.compose.runtime.Composable
import com.wardrobapp.domain.ImportFailureReason
import com.wardrobapp.domain.ImportWarning
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.OutfitReason
import com.wardrobapp.domain.Fit
import com.wardrobapp.domain.Formality
import com.wardrobapp.domain.Pattern
import com.wardrobapp.domain.Weight
import com.wardrobapp.domain.Season
import com.wardrobapp.domain.UnsafeUrlReason
import com.wardrobapp.presentation.ErrorFallback
import com.wardrobapp.presentation.ErrorTitle
import com.wardrobapp.presentation.GarmentCaption
import com.wardrobapp.presentation.LanguageChoice
import com.wardrobapp.presentation.ThemeChoice
import com.wardrobapp.ui.resources.Res
import com.wardrobapp.ui.resources.category_accessories
import com.wardrobapp.ui.resources.category_activewear
import com.wardrobapp.ui.resources.category_bottoms
import com.wardrobapp.ui.resources.category_dresses
import com.wardrobapp.ui.resources.category_loungewear
import com.wardrobapp.ui.resources.category_midlayer
import com.wardrobapp.ui.resources.category_outerwear
import com.wardrobapp.ui.resources.category_shoes
import com.wardrobapp.ui.resources.category_tops
import com.wardrobapp.ui.resources.category_underwear
import com.wardrobapp.ui.resources.color_beige
import com.wardrobapp.ui.resources.color_black
import com.wardrobapp.ui.resources.color_blue
import com.wardrobapp.ui.resources.color_brown
import com.wardrobapp.ui.resources.color_burgundy
import com.wardrobapp.ui.resources.color_coral
import com.wardrobapp.ui.resources.color_cream
import com.wardrobapp.ui.resources.color_gold
import com.wardrobapp.ui.resources.color_gray
import com.wardrobapp.ui.resources.color_green
import com.wardrobapp.ui.resources.color_khaki
import com.wardrobapp.ui.resources.color_lavender
import com.wardrobapp.ui.resources.color_light_blue
import com.wardrobapp.ui.resources.color_multi
import com.wardrobapp.ui.resources.color_navy
import com.wardrobapp.ui.resources.color_olive
import com.wardrobapp.ui.resources.color_orange
import com.wardrobapp.ui.resources.color_pink
import com.wardrobapp.ui.resources.color_purple
import com.wardrobapp.ui.resources.color_red
import com.wardrobapp.ui.resources.color_silver
import com.wardrobapp.ui.resources.color_tan
import com.wardrobapp.ui.resources.color_teal
import com.wardrobapp.ui.resources.color_white
import com.wardrobapp.ui.resources.color_yellow
import com.wardrobapp.ui.resources.error_background_not_removed
import com.wardrobapp.ui.resources.error_crop_failed
import com.wardrobapp.ui.resources.error_garment_not_deleted
import com.wardrobapp.ui.resources.error_garment_not_saved
import com.wardrobapp.ui.resources.error_no_camera
import com.wardrobapp.ui.resources.error_not_undone
import com.wardrobapp.ui.resources.error_outfit_not_deleted
import com.wardrobapp.ui.resources.error_outfit_not_saved
import com.wardrobapp.ui.resources.error_photo_not_imported
import com.wardrobapp.ui.resources.error_photo_required
import com.wardrobapp.ui.resources.error_rating_not_saved
import com.wardrobapp.ui.resources.error_title_background
import com.wardrobapp.ui.resources.error_title_photo
import com.wardrobapp.ui.resources.error_wardrobe_unreadable
import com.wardrobapp.ui.resources.form_error_title
import com.wardrobapp.ui.resources.import_no_fetchable_images
import com.wardrobapp.ui.resources.import_no_images_downloaded
import com.wardrobapp.ui.resources.import_no_images_found
import com.wardrobapp.ui.resources.import_not_a_web_page
import com.wardrobapp.ui.resources.import_page_not_loaded
import com.wardrobapp.ui.resources.import_page_timed_out
import com.wardrobapp.ui.resources.import_page_too_large
import com.wardrobapp.ui.resources.import_warning_images_blocked
import com.wardrobapp.ui.resources.import_warning_images_capped
import com.wardrobapp.ui.resources.import_warning_images_failed
import com.wardrobapp.ui.resources.import_warning_structured_data_unreadable
import com.wardrobapp.ui.resources.language_automatic
import com.wardrobapp.ui.resources.language_english
import com.wardrobapp.ui.resources.language_spanish
import com.wardrobapp.ui.resources.occasion_casual
import com.wardrobapp.ui.resources.occasion_formal
import com.wardrobapp.ui.resources.occasion_lounge
import com.wardrobapp.ui.resources.occasion_sport
import com.wardrobapp.ui.resources.occasion_work
import com.wardrobapp.ui.resources.reason_coherent
import com.wardrobapp.ui.resources.weight_heavy
import com.wardrobapp.ui.resources.weight_mid
import com.wardrobapp.ui.resources.weight_light
import com.wardrobapp.ui.resources.fit_oversized
import com.wardrobapp.ui.resources.fit_relaxed
import com.wardrobapp.ui.resources.fit_regular
import com.wardrobapp.ui.resources.fit_fitted
import com.wardrobapp.ui.resources.pattern_texture
import com.wardrobapp.ui.resources.pattern_print
import com.wardrobapp.ui.resources.pattern_checks
import com.wardrobapp.ui.resources.pattern_stripes
import com.wardrobapp.ui.resources.pattern_solid
import com.wardrobapp.ui.resources.formality_formal
import com.wardrobapp.ui.resources.formality_smart
import com.wardrobapp.ui.resources.formality_smart_casual
import com.wardrobapp.ui.resources.formality_casual
import com.wardrobapp.ui.resources.formality_lounge
import com.wardrobapp.ui.resources.reason_style
import com.wardrobapp.ui.resources.reason_taste
import com.wardrobapp.ui.resources.reason_colours
import com.wardrobapp.ui.resources.reason_learned
import com.wardrobapp.ui.resources.reason_occasion
import com.wardrobapp.ui.resources.reason_season
import com.wardrobapp.ui.resources.season_all_season
import com.wardrobapp.ui.resources.season_fall
import com.wardrobapp.ui.resources.season_spring
import com.wardrobapp.ui.resources.season_summer
import com.wardrobapp.ui.resources.season_winter
import com.wardrobapp.ui.resources.subcategory_athletic
import com.wardrobapp.ui.resources.subcategory_bag
import com.wardrobapp.ui.resources.subcategory_belt
import com.wardrobapp.ui.resources.subcategory_blazer
import com.wardrobapp.ui.resources.subcategory_blouse
import com.wardrobapp.ui.resources.subcategory_bodysuit
import com.wardrobapp.ui.resources.subcategory_boots
import com.wardrobapp.ui.resources.subcategory_boxers
import com.wardrobapp.ui.resources.subcategory_bra
import com.wardrobapp.ui.resources.subcategory_bracelets
import com.wardrobapp.ui.resources.subcategory_briefs
import com.wardrobapp.ui.resources.subcategory_cape
import com.wardrobapp.ui.resources.subcategory_cardigan
import com.wardrobapp.ui.resources.subcategory_chinos
import com.wardrobapp.ui.resources.subcategory_coat
import com.wardrobapp.ui.resources.subcategory_cocktail
import com.wardrobapp.ui.resources.subcategory_crop_top
import com.wardrobapp.ui.resources.subcategory_earrings
import com.wardrobapp.ui.resources.subcategory_eyewear
import com.wardrobapp.ui.resources.subcategory_flats
import com.wardrobapp.ui.resources.subcategory_foulard
import com.wardrobapp.ui.resources.subcategory_gloves
import com.wardrobapp.ui.resources.subcategory_hat
import com.wardrobapp.ui.resources.subcategory_heels
import com.wardrobapp.ui.resources.subcategory_hoodie
import com.wardrobapp.ui.resources.subcategory_jacket
import com.wardrobapp.ui.resources.subcategory_jeans
import com.wardrobapp.ui.resources.subcategory_jewelry
import com.wardrobapp.ui.resources.subcategory_jumpsuit
import com.wardrobapp.ui.resources.subcategory_leggings
import com.wardrobapp.ui.resources.subcategory_loafers
import com.wardrobapp.ui.resources.subcategory_lounge_set
import com.wardrobapp.ui.resources.subcategory_maxi
import com.wardrobapp.ui.resources.subcategory_midi
import com.wardrobapp.ui.resources.subcategory_mini
import com.wardrobapp.ui.resources.subcategory_necklaces
import com.wardrobapp.ui.resources.subcategory_nightgown
import com.wardrobapp.ui.resources.subcategory_overshirt
import com.wardrobapp.ui.resources.subcategory_pajama_bottoms
import com.wardrobapp.ui.resources.subcategory_pajama_set
import com.wardrobapp.ui.resources.subcategory_pajama_top
import com.wardrobapp.ui.resources.subcategory_pants
import com.wardrobapp.ui.resources.subcategory_parka
import com.wardrobapp.ui.resources.subcategory_polo
import com.wardrobapp.ui.resources.subcategory_poncho
import com.wardrobapp.ui.resources.subcategory_rings
import com.wardrobapp.ui.resources.subcategory_robe
import com.wardrobapp.ui.resources.subcategory_romper
import com.wardrobapp.ui.resources.subcategory_sandals
import com.wardrobapp.ui.resources.subcategory_scarf
import com.wardrobapp.ui.resources.subcategory_shapewear
import com.wardrobapp.ui.resources.subcategory_shirt
import com.wardrobapp.ui.resources.subcategory_shorts
import com.wardrobapp.ui.resources.subcategory_skirt
import com.wardrobapp.ui.resources.subcategory_sneakers
import com.wardrobapp.ui.resources.subcategory_socks
import com.wardrobapp.ui.resources.subcategory_sports_bra
import com.wardrobapp.ui.resources.subcategory_sundress
import com.wardrobapp.ui.resources.subcategory_sunglasses
import com.wardrobapp.ui.resources.subcategory_sweater
import com.wardrobapp.ui.resources.subcategory_sweatpants
import com.wardrobapp.ui.resources.subcategory_tank_top
import com.wardrobapp.ui.resources.subcategory_thermal
import com.wardrobapp.ui.resources.subcategory_tie
import com.wardrobapp.ui.resources.subcategory_tights
import com.wardrobapp.ui.resources.subcategory_track_suit
import com.wardrobapp.ui.resources.subcategory_tshirt
import com.wardrobapp.ui.resources.subcategory_vest
import com.wardrobapp.ui.resources.subcategory_wallet
import com.wardrobapp.ui.resources.subcategory_watch
import com.wardrobapp.ui.resources.subcategory_windbreaker
import com.wardrobapp.ui.resources.subcategory_workout_shorts
import com.wardrobapp.ui.resources.subcategory_workout_top
import com.wardrobapp.ui.resources.subcategory_yoga_pants
import com.wardrobapp.ui.resources.theme_automatic
import com.wardrobapp.ui.resources.theme_dark
import com.wardrobapp.ui.resources.theme_light
import com.wardrobapp.ui.resources.unsafe_credentials_in_url
import com.wardrobapp.ui.resources.unsafe_host_is_local
import com.wardrobapp.ui.resources.unsafe_not_a_web_address
import com.wardrobapp.ui.resources.unsafe_redirect_unreadable
import com.wardrobapp.ui.resources.unsafe_redirected_to_local_host
import com.wardrobapp.ui.resources.unsafe_scheme_not_allowed
import com.wardrobapp.ui.resources.unsafe_url_required
import com.wardrobapp.ui.resources.wardrobe_caption_brand
import com.wardrobapp.ui.resources.wardrobe_caption_category
import com.wardrobapp.ui.resources.wardrobe_caption_type
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The wardrobe's vocabulary, in the reader's language.
 *
 * String resources have no dynamic keys: the React Native app writes
 * `t("categories." + id)` and gets a translation for whatever id it holds, and
 * neither Android's `R.string` nor Compose Multiplatform's `Res.string`, which
 * these are now, can do that. So the mapping is written out -- and the risk that
 * comes with writing it out, a category, type or colour that quietly has no entry
 * and renders as its raw stored value, is covered by `StringResourceParityTest`,
 * which walks the same source lists these were generated from and fails if any
 * key is missing.
 *
 * The stored values themselves stay English. A garment's type is written to the
 * database as "T-Shirt" whichever language added it, because the domain keys its
 * occasion derivation on that exact string -- so this translates for display
 * only, exactly as `localizeSubcategory` does in the app this replaced.
 *
 * Anything absent falls back to the stored value rather than to a placeholder: a
 * colour someone typed by hand is better shown as they typed it than as a crash.
 */

val Season.labelRes: StringResource
    get() = when (this) {
        Season.SPRING -> Res.string.season_spring
        Season.SUMMER -> Res.string.season_summer
        Season.FALL -> Res.string.season_fall
        Season.WINTER -> Res.string.season_winter
        Season.ALL_SEASON -> Res.string.season_all_season
    }

/**
 * Why an outfit came up, in words.
 *
 * The engine decides which reasons apply and this decides how they read, for the
 * usual reason: :domain has no resources and no business holding a sentence in
 * one language.
 */
val OutfitReason.labelRes: StringResource
    get() = when (this) {
        OutfitReason.LEARNED -> Res.string.reason_learned
        OutfitReason.COLOURS -> Res.string.reason_colours
        OutfitReason.OCCASION -> Res.string.reason_occasion
        OutfitReason.SEASON -> Res.string.reason_season
        OutfitReason.COHERENT -> Res.string.reason_coherent
        OutfitReason.TASTE -> Res.string.reason_taste
        OutfitReason.STYLE -> Res.string.reason_style
    }

/** A garment's style attributes, in words; see GarmentAttributes for what they are for. */
val Formality.labelRes: StringResource
    get() = when (this) {
        Formality.LOUNGE -> Res.string.formality_lounge
        Formality.CASUAL -> Res.string.formality_casual
        Formality.SMART_CASUAL -> Res.string.formality_smart_casual
        Formality.SMART -> Res.string.formality_smart
        Formality.FORMAL -> Res.string.formality_formal
    }

val Pattern.labelRes: StringResource
    get() = when (this) {
        Pattern.SOLID -> Res.string.pattern_solid
        Pattern.STRIPES -> Res.string.pattern_stripes
        Pattern.CHECKS -> Res.string.pattern_checks
        Pattern.PRINT -> Res.string.pattern_print
        Pattern.TEXTURE -> Res.string.pattern_texture
    }

val Fit.labelRes: StringResource
    get() = when (this) {
        Fit.FITTED -> Res.string.fit_fitted
        Fit.REGULAR -> Res.string.fit_regular
        Fit.RELAXED -> Res.string.fit_relaxed
        Fit.OVERSIZED -> Res.string.fit_oversized
    }

val Weight.labelRes: StringResource
    get() = when (this) {
        Weight.LIGHT -> Res.string.weight_light
        Weight.MID -> Res.string.weight_mid
        Weight.HEAVY -> Res.string.weight_heavy
    }

val Occasion.labelRes: StringResource
    get() = when (this) {
        Occasion.CASUAL -> Res.string.occasion_casual
        Occasion.WORK -> Res.string.occasion_work
        Occasion.FORMAL -> Res.string.occasion_formal
        Occasion.SPORT -> Res.string.occasion_sport
        Occasion.LOUNGE -> Res.string.occasion_lounge
    }

/** Category ids, as `GARMENT_CATEGORIES` and every garment row hold them. */
internal val CATEGORY_LABELS: Map<String, StringResource> = mapOf(
    "tops" to Res.string.category_tops,
    "bottoms" to Res.string.category_bottoms,
    "dresses" to Res.string.category_dresses,
    "midlayer" to Res.string.category_midlayer,
    "outerwear" to Res.string.category_outerwear,
    "shoes" to Res.string.category_shoes,
    "accessories" to Res.string.category_accessories,
    "activewear" to Res.string.category_activewear,
    "underwear" to Res.string.category_underwear,
    "loungewear" to Res.string.category_loungewear,
)

/**
 * Garment types, keyed on the label stored in the database.
 *
 * Keyed on the label rather than a slug because the label *is* what is stored --
 * the same reason the React Native app's `SUBCATEGORY_KEY_MAP` is keyed that way.
 */
internal val SUBCATEGORY_LABELS: Map<String, StringResource> = mapOf(
    "T-Shirt" to Res.string.subcategory_tshirt,
    "Blouse" to Res.string.subcategory_blouse,
    "Shirt" to Res.string.subcategory_shirt,
    "Tank Top" to Res.string.subcategory_tank_top,
    "Sweater" to Res.string.subcategory_sweater,
    "Hoodie" to Res.string.subcategory_hoodie,
    "Crop Top" to Res.string.subcategory_crop_top,
    "Polo" to Res.string.subcategory_polo,
    "Jeans" to Res.string.subcategory_jeans,
    "Pants" to Res.string.subcategory_pants,
    "Shorts" to Res.string.subcategory_shorts,
    "Skirt" to Res.string.subcategory_skirt,
    "Leggings" to Res.string.subcategory_leggings,
    "Sweatpants" to Res.string.subcategory_sweatpants,
    "Chinos" to Res.string.subcategory_chinos,
    "Mini" to Res.string.subcategory_mini,
    "Midi" to Res.string.subcategory_midi,
    "Maxi" to Res.string.subcategory_maxi,
    "Cocktail" to Res.string.subcategory_cocktail,
    "Sundress" to Res.string.subcategory_sundress,
    "Jumpsuit" to Res.string.subcategory_jumpsuit,
    "Romper" to Res.string.subcategory_romper,
    "Jacket" to Res.string.subcategory_jacket,
    "Coat" to Res.string.subcategory_coat,
    "Blazer" to Res.string.subcategory_blazer,
    "Overshirt" to Res.string.subcategory_overshirt,
    "Cardigan" to Res.string.subcategory_cardigan,
    "Vest" to Res.string.subcategory_vest,
    "Poncho" to Res.string.subcategory_poncho,
    "Cape" to Res.string.subcategory_cape,
    "Windbreaker" to Res.string.subcategory_windbreaker,
    "Parka" to Res.string.subcategory_parka,
    "Sneakers" to Res.string.subcategory_sneakers,
    "Boots" to Res.string.subcategory_boots,
    "Sandals" to Res.string.subcategory_sandals,
    "Heels" to Res.string.subcategory_heels,
    "Flats" to Res.string.subcategory_flats,
    "Loafers" to Res.string.subcategory_loafers,
    "Athletic" to Res.string.subcategory_athletic,
    "Hat" to Res.string.subcategory_hat,
    "Scarf" to Res.string.subcategory_scarf,
    "Foulard" to Res.string.subcategory_foulard,
    "Belt" to Res.string.subcategory_belt,
    "Bag" to Res.string.subcategory_bag,
    "Wallet" to Res.string.subcategory_wallet,
    "Gloves" to Res.string.subcategory_gloves,
    "Earrings" to Res.string.subcategory_earrings,
    "Necklaces" to Res.string.subcategory_necklaces,
    "Bracelets" to Res.string.subcategory_bracelets,
    "Rings" to Res.string.subcategory_rings,
    "Jewelry" to Res.string.subcategory_jewelry,
    "Watch" to Res.string.subcategory_watch,
    "Eyewear" to Res.string.subcategory_eyewear,
    "Sunglasses" to Res.string.subcategory_sunglasses,
    "Tie" to Res.string.subcategory_tie,
    "Sports Bra" to Res.string.subcategory_sports_bra,
    "Workout Top" to Res.string.subcategory_workout_top,
    "Workout Shorts" to Res.string.subcategory_workout_shorts,
    "Yoga Pants" to Res.string.subcategory_yoga_pants,
    "Track Suit" to Res.string.subcategory_track_suit,
    "Bra" to Res.string.subcategory_bra,
    "Briefs" to Res.string.subcategory_briefs,
    "Boxers" to Res.string.subcategory_boxers,
    "Bodysuit" to Res.string.subcategory_bodysuit,
    "Shapewear" to Res.string.subcategory_shapewear,
    "Socks" to Res.string.subcategory_socks,
    "Tights" to Res.string.subcategory_tights,
    "Thermal" to Res.string.subcategory_thermal,
    "Pajama Set" to Res.string.subcategory_pajama_set,
    "Pajama Top" to Res.string.subcategory_pajama_top,
    "Pajama Bottoms" to Res.string.subcategory_pajama_bottoms,
    "Nightgown" to Res.string.subcategory_nightgown,
    "Robe" to Res.string.subcategory_robe,
    "Lounge Set" to Res.string.subcategory_lounge_set,
)

/** Palette keys, as `GARMENT_COLORS` holds them. */
internal val COLOR_LABELS: Map<String, StringResource> = mapOf(
    "black" to Res.string.color_black,
    "white" to Res.string.color_white,
    "gray" to Res.string.color_gray,
    "navy" to Res.string.color_navy,
    "blue" to Res.string.color_blue,
    "lightBlue" to Res.string.color_light_blue,
    "red" to Res.string.color_red,
    "burgundy" to Res.string.color_burgundy,
    "pink" to Res.string.color_pink,
    "green" to Res.string.color_green,
    "olive" to Res.string.color_olive,
    "khaki" to Res.string.color_khaki,
    "brown" to Res.string.color_brown,
    "tan" to Res.string.color_tan,
    "beige" to Res.string.color_beige,
    "cream" to Res.string.color_cream,
    "yellow" to Res.string.color_yellow,
    "orange" to Res.string.color_orange,
    "purple" to Res.string.color_purple,
    "lavender" to Res.string.color_lavender,
    "coral" to Res.string.color_coral,
    "teal" to Res.string.color_teal,
    "gold" to Res.string.color_gold,
    "silver" to Res.string.color_silver,
    "multi" to Res.string.color_multi,
)

/**
 * A category id as words.
 *
 * The fallback is the id itself rather than a blank or a crash: a row holding a
 * category this build does not know about -- from a restored backup written by a
 * later version -- should still name itself.
 */
@Composable
fun categoryLabel(id: String): String =
    CATEGORY_LABELS[id]?.let { stringResource(it) } ?: id.humanised()

/** A garment type as words, from the English label stored in its row. */
@Composable
fun garmentTypeLabel(stored: String): String =
    SUBCATEGORY_LABELS[stored]?.let { stringResource(it) } ?: stored

/**
 * A palette key as words.
 *
 * Unknown keys are shown as stored, which is what a hand-entered colour is: a
 * hex, and the honest thing to show for it.
 */
@Composable
fun paletteLabel(key: String): String =
    COLOR_LABELS[key]?.let { stringResource(it) } ?: key.humanised()

/** "loungewear" as "Loungewear", "lightBlue" as "Light blue". */
private fun String.humanised(): String =
    replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replace('-', ' ')
        .replaceFirstChar { it.uppercase() }

/**
 * What to call each language option.
 *
 * English and Español are each named in their own language, as the app this replaced
 * names them: a language you cannot read is not worth offering in a language you
 * cannot read. "Automatic" is the exception, since it is the only one whose
 * meaning depends on the reader's current language.
 */
val LanguageChoice.labelRes: StringResource
    get() = when (this) {
        LanguageChoice.SYSTEM -> Res.string.language_automatic
        LanguageChoice.ENGLISH -> Res.string.language_english
        LanguageChoice.SPANISH -> Res.string.language_spanish
    }

/**
 * What to call each theme option.
 *
 * "Automatic" rather than "System", which is what the app this replaced calls it: the
 * word matches the language picker's own first option, and two settings offering
 * the same idea under two different names is how a screen reads as two screens.
 */
val ThemeChoice.labelRes: StringResource
    get() = when (this) {
        ThemeChoice.SYSTEM -> Res.string.theme_automatic
        ThemeChoice.LIGHT -> Res.string.theme_light
        ThemeChoice.DARK -> Res.string.theme_dark
    }

/**
 * What to call each thing a grid cell can say under a photo.
 *
 * Nouns for the field itself rather than sentences: the menu section above them
 * says what is being chosen, so "Brand" answers it where "Show the brand" would
 * repeat it three times.
 */
val GarmentCaption.labelRes: StringResource
    get() = when (this) {
        GarmentCaption.BRAND -> Res.string.wardrobe_caption_brand
        GarmentCaption.TYPE -> Res.string.wardrobe_caption_type
        GarmentCaption.CATEGORY -> Res.string.wardrobe_caption_category
    }

/**
 * Why a link was refused, in the reader's language.
 *
 * Composable, and no longer an extension on Context, since the strings moved
 * to :ui's Compose Multiplatform resources: those are read in composition.
 *
 * The resource names match the case names by convention, which
 * `ImportMessageParityTest` relies on to hold each of these to the sentence
 * :domain produces. Same arrangement as the archive failures, and for the same
 * reason: the English in :domain is the fallback for a caller with no
 * resources, and the
 * English here is what the Spanish was translated from.
 */
@Composable
fun unsafeUrlText(reason: UnsafeUrlReason): String = when (reason) {
    UnsafeUrlReason.UrlRequired -> stringResource(Res.string.unsafe_url_required)

    UnsafeUrlReason.NotAWebAddress -> stringResource(Res.string.unsafe_not_a_web_address)

    UnsafeUrlReason.SchemeNotAllowed -> stringResource(Res.string.unsafe_scheme_not_allowed)

    UnsafeUrlReason.CredentialsInUrl -> stringResource(Res.string.unsafe_credentials_in_url)

    is UnsafeUrlReason.HostIsLocal -> stringResource(Res.string.unsafe_host_is_local, reason.host)

    UnsafeUrlReason.RedirectUnreadable -> stringResource(Res.string.unsafe_redirect_unreadable)

    is UnsafeUrlReason.RedirectedToLocalHost ->
        stringResource(Res.string.unsafe_redirected_to_local_host, reason.host)
}

/** Why an import produced nothing, in the reader's language. */
@Composable
fun importFailureText(reason: ImportFailureReason): String = when (reason) {
    ImportFailureReason.PageTimedOut -> stringResource(Res.string.import_page_timed_out)

    ImportFailureReason.PageTooLarge -> stringResource(Res.string.import_page_too_large)

    is ImportFailureReason.PageNotLoaded ->
        stringResource(Res.string.import_page_not_loaded, reason.status)

    ImportFailureReason.NotAWebPage -> stringResource(Res.string.import_not_a_web_page)

    ImportFailureReason.NoImagesFound -> stringResource(Res.string.import_no_images_found)

    ImportFailureReason.NoFetchableImages -> stringResource(Res.string.import_no_fetchable_images)

    ImportFailureReason.NoImagesDownloaded -> stringResource(Res.string.import_no_images_downloaded)
}

/**
 * What an import wants to mention, in the reader's language.
 *
 * The counted ones go through `pluralStringResource` rather than a formatted
 * sentence: :domain spells out its own singular and plural because a fixture
 * compares that English, and the resources are where a language with other
 * plural rules than English gets them right.
 */
@Composable
fun importWarningText(warning: ImportWarning): String = when (warning) {
    ImportWarning.StructuredDataUnreadable ->
        stringResource(Res.string.import_warning_structured_data_unreadable)

    is ImportWarning.ImagesCapped ->
        stringResource(Res.string.import_warning_images_capped, warning.listed, warning.used)

    is ImportWarning.ImagesBlocked -> pluralStringResource(
        Res.plurals.import_warning_images_blocked,
        warning.count,
        warning.count,
    )

    is ImportWarning.ImagesFailed -> pluralStringResource(
        Res.plurals.import_warning_images_failed,
        warning.count,
        warning.count,
    )
}

/**
 * What a screen says when a failure has nothing readable of its own.
 *
 * Named one to one with the resources, because these were the resource ids until
 * the screens' state moved into common code; see [ErrorFallback].
 */
val ErrorFallback.messageRes: StringResource
    get() = when (this) {
        ErrorFallback.BACKGROUND_NOT_REMOVED -> Res.string.error_background_not_removed
        ErrorFallback.PHOTO_NOT_CROPPED -> Res.string.error_crop_failed
        ErrorFallback.GARMENT_NOT_SAVED -> Res.string.error_garment_not_saved
        ErrorFallback.NO_CAMERA -> Res.string.error_no_camera
        ErrorFallback.OUTFIT_NOT_DELETED -> Res.string.error_outfit_not_deleted
        ErrorFallback.OUTFIT_NOT_SAVED -> Res.string.error_outfit_not_saved
        ErrorFallback.PHOTO_NOT_IMPORTED -> Res.string.error_photo_not_imported
        ErrorFallback.PHOTO_REQUIRED -> Res.string.error_photo_required
        ErrorFallback.RATING_NOT_SAVED -> Res.string.error_rating_not_saved
        ErrorFallback.WARDROBE_UNREADABLE -> Res.string.error_wardrobe_unreadable
        ErrorFallback.GARMENT_NOT_DELETED -> Res.string.error_garment_not_deleted
        ErrorFallback.NOT_UNDONE -> Res.string.error_not_undone
    }

/** The garment form's error dialog title; see [ErrorTitle]. */
val ErrorTitle.labelRes: StringResource
    get() = when (this) {
        ErrorTitle.SAVE -> Res.string.form_error_title
        ErrorTitle.PHOTO -> Res.string.error_title_photo
        ErrorTitle.BACKGROUND -> Res.string.error_title_background
    }
