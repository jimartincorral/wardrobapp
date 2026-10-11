package com.wardrobapp.presentation

/**
 * Where the phone's style model is, for the card that offers it.
 *
 * The model reads a garment's style from its photo and lets the looks
 * somebody saves move the suggestions. It is 85 MB, so the app does not carry
 * it: it is downloaded when somebody asks, and only then. Everything the card
 * says follows from this -- what the download is, how big, whether it is
 * worth it on this phone, how far it has got -- so that nobody spends 85 MB
 * of data without knowing they are, and nobody saves looks believing they do
 * something they do not yet.
 */
data class StyleModelState(
    val status: Status,
    /** The model's size, for the card to say before anybody taps anything. */
    val bytes: Long,
    /**
     * Whether this phone syncs with Home Assistant, which reads photos with
     * the same model and sends the results here: a paired phone gets the
     * style without the download, and the card says so rather than selling
     * it 85 MB it does not need.
     */
    val paired: Boolean = false,
    /** Whether the phone is on a network that may cost money, for the card to say so beside the button. */
    val metered: Boolean = false,
) {
    sealed interface Status {
        /** Not on this phone. */
        data object Absent : Status

        /** Arriving; [fraction] is 0..1, or null before the size is known. */
        data class Downloading(val fraction: Float?) : Status

        /** On this phone and in use. */
        data object Ready : Status

        /** The last attempt stopped; [message] says why, where there is anything to say. */
        data class Failed(val message: String?) : Status
    }

    /** The size, as the screen writes megabytes. */
    val megabytes: String get() = formatMegabytes(bytes)

    /** What the card offers. */
    val offer: Offer
        get() = when (status) {
            Status.Absent -> if (paired) Offer.DOWNLOAD_BESIDE_HOME_ASSISTANT else Offer.DOWNLOAD
            is Status.Downloading -> Offer.DOWNLOADING
            Status.Ready -> Offer.REMOVE
            is Status.Failed -> Offer.RETRY
        }

    /**
     * Whether the looks screen shows the card, with its warning that saved
     * looks change nothing yet: only where that is true -- no model here, and
     * no Home Assistant to read them instead. Through the download too, so
     * the progress stays where the tap was.
     */
    val showsOnLooks: Boolean
        get() = !paired && status != Status.Ready

    enum class Offer {
        /** No model and nothing else to read photos: the download is the feature. */
        DOWNLOAD,

        /** No model, but Home Assistant reads photos already: the download is optional. */
        DOWNLOAD_BESIDE_HOME_ASSISTANT,
        DOWNLOADING,
        REMOVE,
        RETRY,
    }
}
