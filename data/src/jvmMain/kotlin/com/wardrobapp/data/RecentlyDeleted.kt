package com.wardrobapp.data

/**
 * The deletes that can still be undone, by id, for one wardrobe.
 *
 * In jvmMain rather than beside the rows it holds, for `synchronized`: the
 * browser deletes through the server, which keeps this for it, so no
 * platform without a JVM ever needs one.
 *
 * In memory, and bounded: this is a few seconds' grace, not a recycle bin,
 * and a process that dies in the window loses the undo and nothing else --
 * the photos it was holding are orphans the tidy sweep collects. Bounded by
 * count rather than time because nothing here has a clock; whoever offers
 * the undo decides how long it stands and calls [discard] when it is over,
 * and this only has to stop growing if they never do. Past the bound the
 * oldest is let go, and its photos are handed back to be deleted.
 *
 * One per wardrobe, shared by every source that deletes from it: on the
 * phone the sources are made per screen and the screen that deleted a
 * garment is gone by the time Undo is tapped, so the entry has to outlive
 * it.
 */
class RecentlyDeleted(private val capacity: Int = 10) {

    private val lock = Any()
    private val garments = LinkedHashMap<String, DeletedGarment>()
    private val outfits = LinkedHashMap<String, DeletedOutfit>()

    /** Keep [deleted] for a while; the photos of whatever this pushed out, to delete now. */
    fun put(deleted: DeletedGarment): List<String> = synchronized(lock) {
        garments.remove(deleted.id)
        garments[deleted.id] = deleted
        val evicted = ArrayList<String>()
        while (garments.size > capacity) {
            val oldest = garments.entries.first()
            garments.remove(oldest.key)
            evicted += oldest.value.photos
        }
        evicted
    }

    fun put(deleted: DeletedOutfit): Unit = synchronized(lock) {
        outfits.remove(deleted.id)
        outfits[deleted.id] = deleted
        while (outfits.size > capacity) outfits.remove(outfits.keys.first())
    }

    /** The garment delete to undo, taken out so it can be undone once; null if it is not here. */
    fun takeGarment(id: String): DeletedGarment? = synchronized(lock) { garments.remove(id) }

    fun takeOutfit(id: String): DeletedOutfit? = synchronized(lock) { outfits.remove(id) }

    /** Let go of a garment delete whose window has closed; its photos, to delete now. */
    fun discardGarment(id: String): List<String> = synchronized(lock) { garments.remove(id)?.photos ?: emptyList() }

    fun discardOutfit(id: String): Unit = synchronized(lock) { outfits.remove(id) }
}
