package com.wardrobapp.app

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wardrobapp.domain.ImportFailureReason
import com.wardrobapp.domain.ImportWarning
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.OutfitReason
import com.wardrobapp.domain.Season
import com.wardrobapp.domain.UnsafeUrlReason
import com.wardrobapp.domain.DuplicateReason
import com.wardrobapp.presentation.BackupFrequency
import com.wardrobapp.presentation.GarmentCaption
import com.wardrobapp.presentation.LanguageChoice
import com.wardrobapp.presentation.ThemeChoice

/**
 * The wardrobe's vocabulary, in the reader's language.
 *
 * Android resources have no dynamic keys: the React Native app writes
 * `t("categories." + id)` and gets a translation for whatever id it holds, and
 * `R.string` cannot do that. So the mapping is written out -- and the risk that
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

@get:StringRes
val Season.labelRes: Int
    get() = when (this) {
        Season.SPRING -> R.string.season_spring
        Season.SUMMER -> R.string.season_summer
        Season.FALL -> R.string.season_fall
        Season.WINTER -> R.string.season_winter
        Season.ALL_SEASON -> R.string.season_all_season
    }

/**
 * Why an outfit came up, in words.
 *
 * The engine decides which reasons apply and this decides how they read, for the
 * usual reason: :domain has no resources and no business holding a sentence in
 * one language.
 */
@get:StringRes
val OutfitReason.labelRes: Int
    get() = when (this) {
        OutfitReason.LEARNED -> R.string.reason_learned
        OutfitReason.COLOURS -> R.string.reason_colours
        OutfitReason.OCCASION -> R.string.reason_occasion
        OutfitReason.SEASON -> R.string.reason_season
        OutfitReason.COHERENT -> R.string.reason_coherent
    }

@get:StringRes
val Occasion.labelRes: Int
    get() = when (this) {
        Occasion.CASUAL -> R.string.occasion_casual
        Occasion.WORK -> R.string.occasion_work
        Occasion.FORMAL -> R.string.occasion_formal
        Occasion.SPORT -> R.string.occasion_sport
        Occasion.LOUNGE -> R.string.occasion_lounge
    }

/** Category ids, as `GARMENT_CATEGORIES` and every garment row hold them. */
internal val CATEGORY_LABELS: Map<String, Int> = mapOf(
    "tops" to R.string.category_tops,
    "bottoms" to R.string.category_bottoms,
    "dresses" to R.string.category_dresses,
    "midlayer" to R.string.category_midlayer,
    "outerwear" to R.string.category_outerwear,
    "shoes" to R.string.category_shoes,
    "accessories" to R.string.category_accessories,
    "activewear" to R.string.category_activewear,
    "underwear" to R.string.category_underwear,
    "loungewear" to R.string.category_loungewear,
)

/**
 * Garment types, keyed on the label stored in the database.
 *
 * Keyed on the label rather than a slug because the label *is* what is stored --
 * the same reason the React Native app's `SUBCATEGORY_KEY_MAP` is keyed that way.
 */
internal val SUBCATEGORY_LABELS: Map<String, Int> = mapOf(
    "T-Shirt" to R.string.subcategory_tshirt,
    "Blouse" to R.string.subcategory_blouse,
    "Shirt" to R.string.subcategory_shirt,
    "Tank Top" to R.string.subcategory_tank_top,
    "Sweater" to R.string.subcategory_sweater,
    "Hoodie" to R.string.subcategory_hoodie,
    "Crop Top" to R.string.subcategory_crop_top,
    "Polo" to R.string.subcategory_polo,
    "Jeans" to R.string.subcategory_jeans,
    "Pants" to R.string.subcategory_pants,
    "Shorts" to R.string.subcategory_shorts,
    "Skirt" to R.string.subcategory_skirt,
    "Leggings" to R.string.subcategory_leggings,
    "Sweatpants" to R.string.subcategory_sweatpants,
    "Chinos" to R.string.subcategory_chinos,
    "Mini" to R.string.subcategory_mini,
    "Midi" to R.string.subcategory_midi,
    "Maxi" to R.string.subcategory_maxi,
    "Cocktail" to R.string.subcategory_cocktail,
    "Sundress" to R.string.subcategory_sundress,
    "Jumpsuit" to R.string.subcategory_jumpsuit,
    "Romper" to R.string.subcategory_romper,
    "Jacket" to R.string.subcategory_jacket,
    "Coat" to R.string.subcategory_coat,
    "Blazer" to R.string.subcategory_blazer,
    "Overshirt" to R.string.subcategory_overshirt,
    "Cardigan" to R.string.subcategory_cardigan,
    "Vest" to R.string.subcategory_vest,
    "Poncho" to R.string.subcategory_poncho,
    "Cape" to R.string.subcategory_cape,
    "Windbreaker" to R.string.subcategory_windbreaker,
    "Parka" to R.string.subcategory_parka,
    "Sneakers" to R.string.subcategory_sneakers,
    "Boots" to R.string.subcategory_boots,
    "Sandals" to R.string.subcategory_sandals,
    "Heels" to R.string.subcategory_heels,
    "Flats" to R.string.subcategory_flats,
    "Loafers" to R.string.subcategory_loafers,
    "Athletic" to R.string.subcategory_athletic,
    "Hat" to R.string.subcategory_hat,
    "Scarf" to R.string.subcategory_scarf,
    "Foulard" to R.string.subcategory_foulard,
    "Belt" to R.string.subcategory_belt,
    "Bag" to R.string.subcategory_bag,
    "Wallet" to R.string.subcategory_wallet,
    "Gloves" to R.string.subcategory_gloves,
    "Earrings" to R.string.subcategory_earrings,
    "Necklaces" to R.string.subcategory_necklaces,
    "Bracelets" to R.string.subcategory_bracelets,
    "Rings" to R.string.subcategory_rings,
    "Jewelry" to R.string.subcategory_jewelry,
    "Watch" to R.string.subcategory_watch,
    "Eyewear" to R.string.subcategory_eyewear,
    "Sunglasses" to R.string.subcategory_sunglasses,
    "Tie" to R.string.subcategory_tie,
    "Sports Bra" to R.string.subcategory_sports_bra,
    "Workout Top" to R.string.subcategory_workout_top,
    "Workout Shorts" to R.string.subcategory_workout_shorts,
    "Yoga Pants" to R.string.subcategory_yoga_pants,
    "Track Suit" to R.string.subcategory_track_suit,
    "Bra" to R.string.subcategory_bra,
    "Briefs" to R.string.subcategory_briefs,
    "Boxers" to R.string.subcategory_boxers,
    "Bodysuit" to R.string.subcategory_bodysuit,
    "Shapewear" to R.string.subcategory_shapewear,
    "Socks" to R.string.subcategory_socks,
    "Tights" to R.string.subcategory_tights,
    "Thermal" to R.string.subcategory_thermal,
    "Pajama Set" to R.string.subcategory_pajama_set,
    "Pajama Top" to R.string.subcategory_pajama_top,
    "Pajama Bottoms" to R.string.subcategory_pajama_bottoms,
    "Nightgown" to R.string.subcategory_nightgown,
    "Robe" to R.string.subcategory_robe,
    "Lounge Set" to R.string.subcategory_lounge_set,
)

/** Palette keys, as `GARMENT_COLORS` holds them. */
internal val COLOR_LABELS: Map<String, Int> = mapOf(
    "black" to R.string.color_black,
    "white" to R.string.color_white,
    "gray" to R.string.color_gray,
    "navy" to R.string.color_navy,
    "blue" to R.string.color_blue,
    "lightBlue" to R.string.color_light_blue,
    "red" to R.string.color_red,
    "burgundy" to R.string.color_burgundy,
    "pink" to R.string.color_pink,
    "green" to R.string.color_green,
    "olive" to R.string.color_olive,
    "khaki" to R.string.color_khaki,
    "brown" to R.string.color_brown,
    "tan" to R.string.color_tan,
    "beige" to R.string.color_beige,
    "cream" to R.string.color_cream,
    "yellow" to R.string.color_yellow,
    "orange" to R.string.color_orange,
    "purple" to R.string.color_purple,
    "lavender" to R.string.color_lavender,
    "coral" to R.string.color_coral,
    "teal" to R.string.color_teal,
    "gold" to R.string.color_gold,
    "silver" to R.string.color_silver,
    "multi" to R.string.color_multi,
)

/**
 * A category id as words.
 *
 * The fallback is the id itself rather than a blank or a crash: a row holding a
 * category this build does not know about -- from a restored backup written by a
 * later version -- should still name itself.
 */
@Composable
internal fun categoryLabel(id: String): String =
    CATEGORY_LABELS[id]?.let { stringResource(it) } ?: id.humanised()

/** A garment type as words, from the English label stored in its row. */
@Composable
internal fun garmentTypeLabel(stored: String): String =
    SUBCATEGORY_LABELS[stored]?.let { stringResource(it) } ?: stored

/**
 * A palette key as words.
 *
 * Unknown keys are shown as stored, which is what a hand-entered colour is: a
 * hex, and the honest thing to show for it.
 */
@Composable
internal fun paletteLabel(key: String): String =
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
@get:StringRes
val LanguageChoice.labelRes: Int
    get() = when (this) {
        LanguageChoice.SYSTEM -> R.string.language_automatic
        LanguageChoice.ENGLISH -> R.string.language_english
        LanguageChoice.SPANISH -> R.string.language_spanish
    }

/**
 * What to call each theme option.
 *
 * "Automatic" rather than "System", which is what the app this replaced calls it: the
 * word matches the language picker's own first option, and two settings offering
 * the same idea under two different names is how a screen reads as two screens.
 */
@get:StringRes
val ThemeChoice.labelRes: Int
    get() = when (this) {
        ThemeChoice.SYSTEM -> R.string.theme_automatic
        ThemeChoice.LIGHT -> R.string.theme_light
        ThemeChoice.DARK -> R.string.theme_dark
    }

/**
 * What to call each backup frequency.
 *
 * Adjectives rather than "Every day": the row reads as an answer to "how often",
 * which is the heading above it, and "Daily / Weekly / Monthly" scans as one set
 * where "Every day / Every week" repeats a word three times.
 */
@get:StringRes
val BackupFrequency.labelRes: Int
    get() = when (this) {
        BackupFrequency.DAILY -> R.string.backup_frequency_daily
        BackupFrequency.WEEKLY -> R.string.backup_frequency_weekly
        BackupFrequency.MONTHLY -> R.string.backup_frequency_monthly
    }

/**
 * Why two garments were called much the same.
 *
 * Here rather than beside either of the two screens that ask, because both do:
 * the warning when a garment is being added, and the list of what the wardrobe
 * already holds twice. Worded once, so they cannot come to describe the same
 * finding differently.
 */
@Composable
internal fun DuplicateReason.label(): String = stringResource(
    when (this) {
        DuplicateReason.SIMILAR_TAGS -> R.string.duplicate_reason_tags
        DuplicateReason.SIMILAR_COLOR -> R.string.duplicate_reason_colour
        DuplicateReason.SAME_SIZE -> R.string.duplicate_reason_size
        DuplicateReason.OVERALL_SIMILARITY -> R.string.duplicate_reason_overall
    }
)

/**
 * What to call each thing a grid cell can say under a photo.
 *
 * Nouns for the field itself rather than sentences: the menu section above them
 * says what is being chosen, so "Brand" answers it where "Show the brand" would
 * repeat it three times.
 */
@get:StringRes
val GarmentCaption.labelRes: Int
    get() = when (this) {
        GarmentCaption.BRAND -> R.string.wardrobe_caption_brand
        GarmentCaption.TYPE -> R.string.wardrobe_caption_type
        GarmentCaption.CATEGORY -> R.string.wardrobe_caption_category
    }

/**
 * Why a link was refused, in the reader's language.
 *
 * The resource names match the case names by convention, which
 * `ImportMessageParityTest` relies on to hold each of these to the sentence
 * :domain produces. Same arrangement as the archive failures, and for the same
 * reason: the English in :domain is the fallback for a caller with no
 * resources, and the
 * English here is what the Spanish was translated from.
 */
fun Context.unsafeUrlText(reason: UnsafeUrlReason): String = when (reason) {
    UnsafeUrlReason.UrlRequired -> getString(R.string.unsafe_url_required)

    UnsafeUrlReason.NotAWebAddress -> getString(R.string.unsafe_not_a_web_address)

    UnsafeUrlReason.SchemeNotAllowed -> getString(R.string.unsafe_scheme_not_allowed)

    UnsafeUrlReason.CredentialsInUrl -> getString(R.string.unsafe_credentials_in_url)

    is UnsafeUrlReason.HostIsLocal -> getString(R.string.unsafe_host_is_local, reason.host)

    UnsafeUrlReason.RedirectUnreadable -> getString(R.string.unsafe_redirect_unreadable)

    is UnsafeUrlReason.RedirectedToLocalHost ->
        getString(R.string.unsafe_redirected_to_local_host, reason.host)
}

/** Why an import produced nothing, in the reader's language. */
fun Context.importFailureText(reason: ImportFailureReason): String = when (reason) {
    ImportFailureReason.PageTimedOut -> getString(R.string.import_page_timed_out)

    ImportFailureReason.PageTooLarge -> getString(R.string.import_page_too_large)

    is ImportFailureReason.PageNotLoaded ->
        getString(R.string.import_page_not_loaded, reason.status)

    ImportFailureReason.NotAWebPage -> getString(R.string.import_not_a_web_page)

    ImportFailureReason.NoImagesFound -> getString(R.string.import_no_images_found)

    ImportFailureReason.NoFetchableImages -> getString(R.string.import_no_fetchable_images)

    ImportFailureReason.NoImagesDownloaded -> getString(R.string.import_no_images_downloaded)
}

/**
 * What an import wants to mention, in the reader's language.
 *
 * The counted ones go through `getQuantityString` rather than a formatted
 * sentence: :domain spells out its own singular and plural because a fixture
 * compares that English, and Android is where a language with other plural rules
 * than English gets them right.
 */
fun Context.importWarningText(warning: ImportWarning): String = when (warning) {
    ImportWarning.StructuredDataUnreadable ->
        getString(R.string.import_warning_structured_data_unreadable)

    is ImportWarning.ImagesCapped ->
        getString(R.string.import_warning_images_capped, warning.listed, warning.used)

    is ImportWarning.ImagesBlocked -> resources.getQuantityString(
        R.plurals.import_warning_images_blocked,
        warning.count,
        warning.count,
    )

    is ImportWarning.ImagesFailed -> resources.getQuantityString(
        R.plurals.import_warning_images_failed,
        warning.count,
        warning.count,
    )
}
